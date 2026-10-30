package io.github.bambuo.mailmcp.mail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.bambuo.mailmcp.mail.config.MailProperties;
import io.github.bambuo.mailmcp.mail.config.MailSessionFactory;
import io.github.bambuo.mailmcp.mail.model.MailRequests;
import io.github.bambuo.mailmcp.mail.model.MailTypes;
import jakarta.activation.FileDataSource;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import org.springframework.stereotype.Component;

/**
 * Outbound mail: a new message, and a reply that carries the original's threading headers.
 *
 * <p>Reply reads the original through {@link ImapMailRepository}, so the whole surface addresses stored
 * mail by folder plus uid.
 */
@Component
public class SmtpMailSender {

    private final MailProperties props;
    private final MailSessionFactory sessionFactory;
    private final AttachmentStore attachments;
    private final ImapMailRepository repository;

    public SmtpMailSender(MailProperties props, MailSessionFactory sessionFactory, AttachmentStore attachments,
            ImapMailRepository repository) {
        this.props = props;
        this.sessionFactory = sessionFactory;
        this.attachments = attachments;
        this.repository = repository;
    }

    public MailTypes.SendOutcome send(MailRequests.Outbound ob) {
        requireRecipients(ob.to());
        String subject = requireText(ob.subject(), "subject");
        List<String> files = orList(ob.attachments());
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (isText(ob.replyTo())) {
            headers.put("Reply-To", ob.replyTo().trim());
        }
        try {
            MimeMessage msg = compose(subject, ob.to(), ob.cc(), ob.bcc(), ob.text(), ob.html(), files, headers);
            return dispatch(msg, subject, ob.to(), ob.cc(), ob.bcc(), files.size());
        } catch (MessagingException | IOException e) {
            throw sendFailed(e);
        }
    }

    /**
     * Assembles a whole outbound message: sender, recipients, subject, body shape, attachments and any
     * extra headers. Kept separate from {@link #dispatch} so the assembled MIME can be inspected without
     * a live SMTP server.
     */
    MimeMessage compose(String subject, List<String> to, List<String> cc, List<String> bcc, String text,
            String html, List<String> files, Map<String, String> headers) throws MessagingException, IOException {
        MimeMessage msg = newMessage();
        applyRecipients(msg, to, cc, bcc);
        msg.setSubject(subject);
        writeBody(msg, text, html, files);
        for (var header : headers.entrySet()) {
            msg.setHeader(header.getKey(), header.getValue());
        }
        return msg;
    }

    /**
     * Replies in place. {@code In-Reply-To} and {@code References} come from the original so the reply
     * stays in the same conversation thread on the reader's side.
     */
    public MailTypes.SendOutcome reply(MailRequests.Reply r) {
        MailTypes.Detail original = repository.get(r.folder(), r.uid());
        List<String> to = replyRecipients(original, r.replyAll());
        if (to.isEmpty()) {
            throw MailOperationException.invalid("nothing to reply to: the original has no sender address");
        }
        List<String> cc = distinct(concat(r.cc(), r.replyAll() ? carbonCopyRecipients(original) : List.of()));
        List<String> bcc = orList(r.bcc());
        List<String> files = orList(r.attachments());
        String subject = withPrefix(r.subjectOverride(), original.subject(), "Re: ");
        String inReplyTo = original.messageId();
        List<String> references = new ArrayList<>(original.references());
        if (isText(inReplyTo) && !references.contains(inReplyTo)) {
            references.add(inReplyTo);
        }
        try {
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            if (isText(inReplyTo)) {
                headers.put("In-Reply-To", inReplyTo);
            }
            if (!references.isEmpty()) {
                headers.put("References", String.join(" ", references));
            }
            MimeMessage msg = compose(subject, to, cc, bcc, r.text(), r.html(), files, headers);
            return dispatch(msg, subject, to, cc, bcc, files.size());
        } catch (MessagingException | IOException e) {
            throw sendFailed(e);
        }
    }

    private MimeMessage newMessage() throws MessagingException, IOException {
        MimeMessage msg = new MimeMessage(sessionFactory.session());
        String personal = props.fromName() == null ? "" : props.fromName();
        msg.setFrom(new jakarta.mail.internet.InternetAddress(
                props.senderAddress(), MimeUtility.encodeText(personal, "UTF-8", "B")));
        msg.setSentDate(new Date());
        return msg;
    }

    /**
     * Builds the body, wrapping it in multipart/mixed only when files ride along, so a plain mail stays a
     * single-part message most clients render inline.
     */
    private void writeBody(MimeMessage msg, String text, String html, List<String> files)
            throws MessagingException, IOException {
        if (files.isEmpty()) {
            setContent(msg, text, html);
            return;
        }
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart bodyPart = new MimeBodyPart();
        setContent(bodyPart, text, html);
        mixed.addBodyPart(bodyPart);
        for (String file : files) {
            mixed.addBodyPart(filePart(attachments.resolveForSend(file)));
        }
        msg.setContent(mixed);
    }

    private static void setContent(Part part, String text, String html) throws MessagingException {
        boolean hasText = isText(text);
        boolean hasHtml = isText(html);
        if (hasText && hasHtml) {
            MimeMultipart alternative = new MimeMultipart("alternative");
            MimeBodyPart plain = new MimeBodyPart();
            plain.setContent(text, "text/plain; charset=UTF-8");
            MimeBodyPart rich = new MimeBodyPart();
            rich.setContent(html, "text/html; charset=UTF-8");
            // RFC 2046: the plain part must come first so readers that cannot render HTML pick it up.
            alternative.addBodyPart(plain);
            alternative.addBodyPart(rich);
            part.setContent(alternative);
            return;
        }
        if (hasHtml) {
            part.setContent(html, "text/html; charset=UTF-8");
            return;
        }
        part.setContent(hasText ? text : "", "text/plain; charset=UTF-8");
    }

    private static MimeBodyPart filePart(Path file) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new jakarta.activation.DataHandler(new FileDataSource(file.toFile())));
        // Raw name: Jakarta encodes it per RFC 2231 when the headers are written.
        part.setFileName(file.getFileName().toString());
        return part;
    }

    private MailTypes.SendOutcome dispatch(MimeMessage msg, String subject, List<String> to, List<String> cc,
            List<String> bcc, int attachmentCount) throws MessagingException, IOException {
        List<jakarta.mail.Address> recipients = new ArrayList<>();
        collect(recipients, to);
        collect(recipients, cc);
        collect(recipients, bcc);
        if (recipients.isEmpty()) {
            throw MailOperationException.invalid("message has no deliverable recipient");
        }
        if (!isText(props.smtp().host()) || !isText(props.username()) || !isText(props.password())) {
            throw MailOperationException.notConfigured("MAIL_SMTP_HOST, MAIL_USERNAME and MAIL_PASSWORD");
        }
        // saveChanges assigns Message-ID and Date, so read the id only afterwards.
        msg.saveChanges();
        try (jakarta.mail.Transport transport =
                sessionFactory.session().getTransport(sessionFactory.transportProtocol())) {
            transport.connect(props.smtp().host(), props.username(), props.password());
            transport.sendMessage(msg, recipients.toArray(new jakarta.mail.Address[0]));
        } catch (AuthenticationFailedException e) {
            throw new MailOperationException("SMTP_AUTH_FAILED",
                    "smtp login rejected for " + props.username()
                            + " (QQ/163/Gmail need an application-specific password): " + describe(e), e);
        } catch (SendFailedException e) {
            throw new MailOperationException("RECIPIENT_REJECTED",
                    "server rejected " + nameOf(e.getInvalidAddresses()) + ": " + describe(e), e);
        }
        return new MailTypes.SendOutcome(msg.getMessageID(), asAddresses(to), asAddresses(cc), asAddresses(bcc),
                subject, attachmentCount, MimeReader.iso(msg.getSentDate()));
    }

    private static void collect(List<jakarta.mail.Address> sink, List<String> values) throws MessagingException {
        for (String v : values) {
            if (isText(v)) {
                sink.add(new jakarta.mail.internet.InternetAddress(v.trim()));
            }
        }
    }

    private static void applyRecipients(MimeMessage msg, List<String> to, List<String> cc, List<String> bcc)
            throws MessagingException {
        setRecipients(msg, Message.RecipientType.TO, to);
        setRecipients(msg, Message.RecipientType.CC, cc);
        setRecipients(msg, Message.RecipientType.BCC, bcc);
    }

    private static void setRecipients(MimeMessage msg, Message.RecipientType type, List<String> values)
            throws MessagingException {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<jakarta.mail.Address> parsed = new ArrayList<>(values.size());
        collect(parsed, values);
        if (!parsed.isEmpty()) {
            msg.setRecipients(type, parsed.toArray(new jakarta.mail.Address[0]));
        }
    }

    /** The original sender, plus the whole thread when reply-all is asked for. */
    private List<String> replyRecipients(MailTypes.Detail original, boolean replyAll) {
        Set<String> out = new LinkedHashSet<>();
        if (original.from() != null && isText(original.from().email())) {
            out.add(original.from().email());
        }
        if (replyAll) {
            addOthers(out, original.to());
            addOthers(out, original.cc());
        }
        out.removeIf(this::isSelf);
        return new ArrayList<>(out);
    }

    private List<String> carbonCopyRecipients(MailTypes.Detail original) {
        Set<String> out = new LinkedHashSet<>();
        addOthers(out, original.cc());
        out.removeIf(this::isSelf);
        return new ArrayList<>(out);
    }

    private void addOthers(Set<String> sink, List<MailTypes.Address> addresses) {
        for (MailTypes.Address a : addresses) {
            if (a != null && isText(a.email())) {
                sink.add(a.email());
            }
        }
    }

    private boolean isSelf(String email) {
        String v = email.toLowerCase(Locale.ROOT);
        return v.equals(lower(props.senderAddress()))
                || (props.username() != null && v.equals(lower(props.username())));
    }

    private static String nameOf(jakarta.mail.Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "(no address reported)";
        }
        return String.join(", ", java.util.Arrays.stream(addresses).map(String::valueOf).toList());
    }

    private static String withPrefix(String override, String originalSubject, String prefix) {
        if (isText(override)) {
            return override.strip();
        }
        String base = isText(originalSubject) ? originalSubject.strip() : "(no subject)";
        return base.startsWith(prefix) ? base : prefix + base;
    }

    private static List<String> orList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a == null ? List.of() : a);
        out.addAll(b == null ? List.of() : b);
        return out;
    }

    private static List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private static List<MailTypes.Address> asAddresses(List<String> values) {
        return values.stream().filter(SmtpMailSender::isText).map(v -> new MailTypes.Address(null, v.trim())).toList();
    }

    private static boolean isText(String value) {
        return value != null && !value.isBlank();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String what) {
        if (!isText(value)) {
            throw MailOperationException.invalid(what + " is required");
        }
        return value.strip();
    }

    private static void requireRecipients(List<String> to) {
        if (to == null || to.isEmpty()) {
            throw MailOperationException.invalid("at least one 'to' recipient is required");
        }
    }

    private static String describe(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static MailOperationException sendFailed(Exception e) {
        return new MailOperationException("SMTP_SEND_FAILED", describe(e), e);
    }
}
