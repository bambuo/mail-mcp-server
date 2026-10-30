package io.github.bambuo.mailmcp.mail.model;

import java.util.List;
import java.util.Map;

/** Wire types shared by the mail layer and the MCP tool layer. */
public final class MailTypes {

    private MailTypes() {}

    /** Sender/recipient pair. */
    public record Address(String name, String email) {}

    /** List row: headers only, so a page costs one batched fetch instead of one body fetch per message. */
    public record Summary(
            String uid,
            String folder,
            String subject,
            Address from,
            List<Address> to,
            List<Address> cc,
            boolean seen,
            boolean hasAttachment,
            long sizeBytes,
            String date,
            String messageId,
            String inReplyTo) {}

    /** A MIME part that can be downloaded. {@code partId} is the path into the MIME tree. */
    public record Attachment(String partId, String fileName, String contentType, long size, boolean inline) {}

    /** Full message. {@code textBody}/{@code htmlBody} are decoded UTF-8; either may be null. */
    public record Detail(
            String uid,
            String folder,
            String subject,
            Address from,
            List<Address> to,
            List<Address> cc,
            List<Address> bcc,
            boolean seen,
            boolean flagged,
            boolean answered,
            String date,
            long sizeBytes,
            String messageId,
            List<String> references,
            String inReplyTo,
            String textBody,
            String htmlBody,
            List<Attachment> attachments,
            Map<String, String> headers) {}

    /** One page of summaries. {@code nextCursor} is the uid to pass to read the following page. */
    public record Page(List<Summary> items, String nextCursor, boolean hasMore, String folder, int totalMatching) {}

    /** A mailbox folder as the server exposes it. */
    public record Mailbox(String logical, String actual, String path, long total, long unseen, boolean selectable) {}

    /** Result of list_folders: identity, the folders the other tools can address, and the enforced limits. */
    public record Catalog(
            String email,
            String displayName,
            List<Mailbox> folders,
            Map<String, Object> limits,
            String serverTime,
            String timezone) {}

    /** Outcome of an outbound operation. */
    public record SendOutcome(String messageId, List<Address> to, List<Address> cc, List<Address> bcc,
            String subject, int attachmentCount, String sentAt) {}
}
