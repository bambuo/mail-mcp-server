package io.github.bambuo.mailmcp.mail.model;

import java.time.LocalDate;
import java.util.List;

/** Inputs for the read side of the mail surface. */
public final class MailRequests {

    private MailRequests() {}

    /**
     * @param folder logical folder, default inbox
     * @param limit page size
     * @param cursor uid from the previous page; results are strictly older than this uid
     * @param seen null keeps both, true keeps read, false keeps unread
     * @param hasAttachment when true, only messages carrying an attachment are kept
     * @param since inclusive lower date bound
     * @param before exclusive upper date bound
     * @param from sender substring
     * @param to recipient substring
     */
    public record ListQuery(String folder, int limit, String cursor, Boolean seen, boolean hasAttachment,
            LocalDate since, LocalDate before, String from, String to) {}

    /** Adds full-text criteria on top of {@link ListQuery}. */
    public record SearchQuery(ListQuery list, String keyword, SearchIn searchIn) {}

    /** Where a keyword is matched. */
    public enum SearchIn {
        ALL, SUBJECT, CONTENT
    }

    /** Outgoing message payload shared by send, reply and forward. */
    public record Outbound(
            List<String> to,
            List<String> cc,
            List<String> bcc,
            String subject,
            String text,
            String html,
            List<String> attachments,
            String replyTo) {}

    /** Reply against a stored message; threading headers are derived from the original. */
    public record Reply(String folder, String uid, boolean replyAll, String text, String html,
            List<String> cc, List<String> bcc, List<String> attachments, String subjectOverride) {}

    /** One downloaded attachment. */
    public record Downloaded(String partId, String fileName, String contentType, long size, String sha1,
            String base64, String savedPath) {}
}
