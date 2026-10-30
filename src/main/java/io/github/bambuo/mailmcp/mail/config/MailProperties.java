package io.github.bambuo.mailmcp.mail.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mail account settings, bound from the same keys the .env file uses.
 *
 * @param username login account, also the default SMTP "from" address
 * @param password application-specific password (not the mailbox login password for QQ/163/Gmail)
 * @param from display sender address; defaults to {@code username}
 * @param fromName display sender name
 * @param attachDir directory used both to save downloaded attachments and as the only place
 *        outgoing attachments may be read from unless {@code allowAnyAttachPath} is set
 * @param allowAnyAttachPath when true, SendMessage may read attachments from any local path
 * @param maxAttachBytes cap for a single outgoing attachment
 * @param maxDownloadBytes cap for a single attachment returned as base64
 * @param maxPageSize cap for one list/search page
 * @param maxScanMessages how many messages a filtered page (hasAttachment) may inspect to fill itself
 * @param imap inbound settings
 * @param smtp outbound settings
 */
@ConfigurationProperties(prefix = "mail")
public record MailProperties(
        String username,
        String password,
        String from,
        String fromName,
        String attachDir,
        boolean allowAnyAttachPath,
        long maxAttachBytes,
        long maxDownloadBytes,
        int maxPageSize,
        int maxScanMessages,
        Imap imap,
        Smtp smtp) {

    public String senderAddress() {
        return (from == null || from.isBlank()) ? username : from;
    }

    /** IMAP (inbound) connection settings. */
    public record Imap(String host, int port, Security security, Duration timeout) {}

    /** SMTP (outbound) connection settings. */
    public record Smtp(String host, int port, Security security, boolean auth, Duration timeout) {}

    /** Transport security mode. */
    public enum Security {
        /** Implicit TLS on connect (993 / 465). */
        SSL,
        /** Plain connect, then upgrade with STARTTLS (143 / 587). */
        STARTTLS,
        /** No TLS. */
        NONE
    }
}
