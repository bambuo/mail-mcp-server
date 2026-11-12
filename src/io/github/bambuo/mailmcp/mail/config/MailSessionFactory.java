package io.github.bambuo.mailmcp.mail.config;

import java.util.Properties;

import jakarta.mail.Session;
import org.springframework.stereotype.Component;

/**
 * Builds the Jakarta Mail {@link Session} used for both IMAP and SMTP.
 *
 * <p>One session carries both protocol property sets because a single MCP process serves one
 * mailbox, and {@link Session} is documented as safe to share.
 */
@Component
public class MailSessionFactory {

    private final MailProperties props;
    private final Session session;

    public MailSessionFactory(MailProperties props) {
        this.props = props;
        this.session = Session.getInstance(buildProperties(props), null);
    }

    public Session session() {
        return session;
    }

    /** Protocol name for the store: {@code imaps} under implicit TLS, otherwise {@code imap}. */
    public String storeProtocol() {
        return props.imap().security() == MailProperties.Security.SSL ? "imaps" : "imap";
    }

    /** Protocol name for the transport: {@code smtps} under implicit TLS, otherwise {@code smtp}. */
    public String transportProtocol() {
        return props.smtp().security() == MailProperties.Security.SSL ? "smtps" : "smtp";
    }

    static Properties buildProperties(MailProperties m) {
        Properties p = new Properties();
        applyStore(p, m);
        applyTransport(p, m);
        // Never rewrite the subject: QQ/163 encode non-ASCII headers as RFC 2047 already.
        p.setProperty("mail.mime.decodetext.strict", "false");
        p.setProperty("mail.mime.charset", "UTF-8");
        p.setProperty("mail.stashbuffersize", "16384");
        if (m.imap().timeout() != null) {
            long ms = m.imap().timeout().toMillis();
            for (String proto : new String[] {"imap", "imaps"}) {
                p.setProperty("mail." + proto + ".connectiontimeout", String.valueOf(ms));
                p.setProperty("mail." + proto + ".timeout", String.valueOf(ms));
            }
        }
        if (m.smtp().timeout() != null) {
            long ms = m.smtp().timeout().toMillis();
            for (String proto : new String[] {"smtp", "smtps"}) {
                p.setProperty("mail." + proto + ".connectiontimeout", String.valueOf(ms));
                p.setProperty("mail." + proto + ".writetimeout", String.valueOf(ms));
                p.setProperty("mail." + proto + ".timeout", String.valueOf(ms));
            }
        }
        return p;
    }

    private static void applyStore(Properties p, MailProperties m) {
        String proto = m.imap().security() == MailProperties.Security.SSL ? "imaps" : "imap";
        p.setProperty("mail." + proto + ".host", m.imap().host());
        p.setProperty("mail." + proto + ".port", String.valueOf(m.imap().port()));
        if (m.imap().security() == MailProperties.Security.SSL) {
            p.setProperty("mail.imaps.ssl.enable", "true");
        }
        if (m.imap().security() == MailProperties.Security.STARTTLS) {
            p.setProperty("mail.imap.starttls.enable", "true");
            p.setProperty("mail.imap.starttls.required", "true");
        }
        p.setProperty("mail." + proto + ".partialfetch", "true");
        // 16 KB (the Jakarta default) means a 3 MB attachment arrives in ~180 round trips; 1 MB keeps
        // partial fetch for skipping big bodies while making a download a handful of fetches.
        p.setProperty("mail." + proto + ".fetchsize", "1048576");
        // QQ's IMAP server advertises UNDO only intermittently; leaving it off keeps expunge deterministic.
        p.setProperty("mail." + proto + ".sasl.mechanisms", "PLAIN");
    }

    private static void applyTransport(Properties p, MailProperties m) {
        String proto = m.smtp().security() == MailProperties.Security.SSL ? "smtps" : "smtp";
        p.setProperty("mail." + proto + ".host", m.smtp().host());
        p.setProperty("mail." + proto + ".port", String.valueOf(m.smtp().port()));
        p.setProperty("mail." + proto + ".auth", String.valueOf(m.smtp().auth()));
        if (m.smtp().security() == MailProperties.Security.SSL) {
            p.setProperty("mail.smtps.ssl.enable", "true");
        }
        if (m.smtp().security() == MailProperties.Security.STARTTLS) {
            p.setProperty("mail.smtp.starttls.enable", "true");
            p.setProperty("mail.smtp.starttls.required", "true");
        }
    }
}
