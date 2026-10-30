package io.github.bambuo.mailmcp.mcp;

import java.time.LocalDate;
import java.util.List;

import io.github.bambuo.mailmcp.mail.AttachmentStore;
import io.github.bambuo.mailmcp.mail.ImapMailRepository;
import io.github.bambuo.mailmcp.mail.MailOperationException;
import io.github.bambuo.mailmcp.mail.SmtpMailSender;
import io.github.bambuo.mailmcp.mail.model.MailRequests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The MCP surface: seven tools over the mail layer, each returning a JSON document.
 *
 * <p>Tool names are snake_case; response fields keep the Java record names in camelCase. Failures come
 * back as {@code {"error":{"code","message"}}} rather than as thrown JSON-RPC errors, so a model reading
 * the result sees an actionable code instead of a transport-level fault.
 */
@Service
public class MailTools {

    private static final Logger log = LoggerFactory.getLogger(MailTools.class);

    private final ImapMailRepository inbox;
    private final SmtpMailSender sender;
    private final AttachmentStore attachments;
    private final ObjectMapper json;

    public MailTools(ImapMailRepository inbox, SmtpMailSender sender, AttachmentStore attachments,
            ObjectMapper json) {
        this.inbox = inbox;
        this.sender = sender;
        this.attachments = attachments;
        this.json = json;
    }

    @McpTool(name = "list_folders", description = """
            Bootstrap call: returns the authenticated account, every mailbox with the logical name the other
            tools accept (inbox/sent/trash/spam/drafts) plus message and unread counts, and the limits this
            server enforces (page size, attachment size, where attachments may be read from). Call first.""")
    public String listFolders() {
        return call(inbox::catalog);
    }

    @McpTool(name = "list_messages", description = """
            List messages newest first from headers only, so a page costs one round trip. Returns uid,
            subject, from, to, cc, seen, hasAttachment, sizeBytes, date, messageId and inReplyTo.
            hasAttachment is a Content-Type heuristic (multipart/mixed envelope); get_message is exact.
            Page through with the returned nextCursor and pass it back as cursor unchanged; it is an offset
            into the matching set, so mail arriving between two calls can shift the boundary by a few.""")
    public String listMessages(
            @McpToolParam(description = "Logical folder: inbox, sent, trash, spam, drafts, or a literal folder path. Default inbox.", required = false)
            String folder,
            @McpToolParam(description = "Messages per page, capped by maxPageSize from list_folders. Default 20.", required = false)
            Integer limit,
            @McpToolParam(description = "Opaque cursor from the previous page.", required = false)
            String cursor,
            @McpToolParam(description = "true = only read, false = only unread, omit = both.", required = false)
            Boolean read,
            @McpToolParam(description = "true = only messages with attachments. Scans up to maxScanMessages per page (see list_folders); narrow with since/before if a page comes back short.", required = false)
            Boolean hasAttachment,
            @McpToolParam(description = "Inclusive lower date bound, yyyy-MM-dd.", required = false)
            String since,
            @McpToolParam(description = "Exclusive upper date bound, yyyy-MM-dd.", required = false)
            String before,
            @McpToolParam(description = "Sender substring, e.g. someone@example.com.", required = false)
            String from,
            @McpToolParam(description = "Recipient substring.", required = false)
            String to) {
        return call(() -> inbox.list(new MailRequests.ListQuery(orDefault(folder, "inbox"), orZero(limit),
                cursor, read, Boolean.TRUE.equals(hasAttachment), day(since, "since"), day(before, "before"),
                from, to)));
    }

    @McpTool(name = "search_messages", description = """
            Search by keyword plus the same filters as list_messages, returning the same header-only rows.
            Subject matching is decoded; CONTENT runs a server-side text search over message bodies only,
            never over attachment contents.""")
    public String searchMessages(
            @McpToolParam(description = "Keyword to match.")
            String query,
            @McpToolParam(description = "ALL (default), SUBJECT or CONTENT.", required = false)
            String searchIn,
            @McpToolParam(description = "Logical folder. Default inbox.", required = false)
            String folder,
            @McpToolParam(description = "Messages per page. Default 20.", required = false)
            Integer limit,
            @McpToolParam(description = "Opaque cursor from the previous page.", required = false)
            String cursor,
            @McpToolParam(description = "true = only read, false = only unread.", required = false)
            Boolean read,
            @McpToolParam(description = "true = only messages with attachments. Scans up to maxScanMessages per page (see list_folders).", required = false)
            Boolean hasAttachment,
            @McpToolParam(description = "Inclusive lower date bound, yyyy-MM-dd.", required = false)
            String since,
            @McpToolParam(description = "Exclusive upper date bound, yyyy-MM-dd.", required = false)
            String before,
            @McpToolParam(description = "Sender substring.", required = false)
            String from,
            @McpToolParam(description = "Recipient substring.", required = false)
            String to) {
        return call(() -> {
            MailRequests.ListQuery base = new MailRequests.ListQuery(orDefault(folder, "inbox"), orZero(limit),
                    cursor, read, Boolean.TRUE.equals(hasAttachment), day(since, "since"), day(before, "before"),
                    from, to);
            return inbox.search(new MailRequests.SearchQuery(base, require(query, "query"),
                    searchIn == null || searchIn.isBlank() ? MailRequests.SearchIn.ALL
                            : MailRequests.SearchIn.valueOf(searchIn.trim().toUpperCase(java.util.Locale.ROOT))));
        });
    }

    @McpTool(name = "get_message", description = """
            Full message: textBody and/or htmlBody, every header, threading ids (messageId, inReplyTo,
            references) and the attachment list with part ids for download_attachment. Reading marks nothing;
            folders open read-only, so unread state is preserved.""")
    public String getMessage(
            @McpToolParam(description = "Logical folder. Default inbox.", required = false)
            String folder,
            @McpToolParam(description = "uid from a list/search result.")
            String uid) {
        return call(() -> inbox.get(orDefault(folder, "inbox"), uid));
    }

    @McpTool(name = "download_attachment", description = """
            Fetch one attachment as base64 with its SHA-1 so the caller can verify integrity. Set saveToDisk
            to also write it under the server's attachment directory; the response then carries savedPath.
            Refuses anything over maxDownloadBytes (reported by list_folders) before transferring.""")
    public String downloadAttachment(
            @McpToolParam(description = "Logical folder. Default inbox.", required = false)
            String folder,
            @McpToolParam(description = "uid of the message.")
            String uid,
            @McpToolParam(description = "part id from get_message attachments, e.g. \"3\" or \"1.2\".")
            String attachmentId,
            @McpToolParam(description = "true also writes the file to disk. Default false.", required = false)
            Boolean saveToDisk) {
        return call(() -> inbox.download(orDefault(folder, "inbox"), uid, attachmentId,
                Boolean.TRUE.equals(saveToDisk)));
    }

    @McpTool(name = "send_message", description = """
            Send a new message. Provide text, html, or both (sent as multipart/alternative with plain first).
            Attachments are local paths the server can read; only the attachment directory is allowed unless
            attachmentAnyPath is true in list_folders, so write the file there first.""")
    public String sendMessage(
            @McpToolParam(description = "Primary recipients.")
            List<String> to,
            @McpToolParam(description = "CC recipients.", required = false)
            List<String> cc,
            @McpToolParam(description = "BCC recipients.", required = false)
            List<String> bcc,
            @McpToolParam(description = "Subject line.")
            String subject,
            @McpToolParam(description = "Plain text body.", required = false)
            String text,
            @McpToolParam(description = "HTML body.", required = false)
            String html,
            @McpToolParam(description = "Local file paths to attach.", required = false)
            List<String> attachments,
            @McpToolParam(description = "Reply-To address.", required = false)
            String replyTo) {
        return call(() -> sender.send(new MailRequests.Outbound(to, cc, bcc, subject, text, html,
                attachments, replyTo)));
    }

    @McpTool(name = "reply_message", description = """
            Reply to a stored message. In-Reply-To and References are copied from it and the subject gains a
            Re: prefix, so the reply threads correctly. replyAll addresses the original sender plus its other
            recipients, minus this account.""")
    public String replyMessage(
            @McpToolParam(description = "Logical folder of the message being answered. Default inbox.", required = false)
            String folder,
            @McpToolParam(description = "uid of the message being answered.")
            String uid,
            @McpToolParam(description = "true = reply to sender plus all recipients.", required = false)
            Boolean replyAll,
            @McpToolParam(description = "Plain text body.", required = false)
            String text,
            @McpToolParam(description = "HTML body.", required = false)
            String html,
            @McpToolParam(description = "Extra CC addresses.", required = false)
            List<String> cc,
            @McpToolParam(description = "Extra BCC addresses.", required = false)
            List<String> bcc,
            @McpToolParam(description = "Local file paths to attach.", required = false)
            List<String> attachments,
            @McpToolParam(description = "Replace the derived Re: subject.", required = false)
            String subject) {
        return call(() -> sender.reply(new MailRequests.Reply(orDefault(folder, "inbox"), uid,
                Boolean.TRUE.equals(replyAll), text, html, cc, bcc, attachments, subject)));
    }

    private String call(java.util.function.Supplier<Object> body) {
        try {
            return json.writeValueAsString(body.get());
        } catch (MailOperationException e) {
            log.warn("tool call rejected: {} - {}", e.code, e.getMessage());
            return json.writeValueAsString(java.util.Map.of("error",
                    java.util.Map.of("code", e.code, "message", e.getMessage() == null ? e.code : e.getMessage())));
        } catch (RuntimeException e) {
            // Unexpected: the stack is the only way to find it, since the client only sees one line.
            log.error("tool call failed", e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return json.writeValueAsString(java.util.Map.of("error",
                    java.util.Map.of("code", "INTERNAL_ERROR", "message", message)));
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw MailOperationException.invalid(what + " is required");
        }
        return value;
    }

    private static LocalDate day(String value, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            throw MailOperationException.invalid(what + " must be yyyy-MM-dd, got '" + value + "'");
        }
    }
}
