package io.github.bambuo.mailmcp.mail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.bambuo.mailmcp.mail.model.MailTypes;
import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeUtility;

/**
 * Turns a Jakarta Mail {@link Message} into the flat records the MCP surface returns.
 *
 * <p>Attachment identity is the dotted path into the MIME tree ({@code "3"}, {@code "1.2"}), so a
 * client can list attachments once and download by that id later. The path is only stable while the
 * server does not rewrite the message, which holds for stored mail.
 */
public final class MimeReader {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private MimeReader() {}

    public static String iso(Date date) {
        return date == null ? null : ISO.format(date.toInstant().atZone(ZoneId.systemDefault()));
    }

    /**
     * Header-only row. Deliberately does not open the body: on a remote IMAP server each body fetch costs
     * about a second, and a page of twenty would take half a minute. {@code hasAttachment} is therefore a
     * Content-Type heuristic (a multipart/mixed envelope); {@link #detail} answers exactly.
     */
    public static MailTypes.Summary summary(Message m, String folderPath, long uid)
            throws MessagingException {
        return new MailTypes.Summary(
                String.valueOf(uid),
                folderPath,
                decode(m.getSubject()),
                firstAddress(m.getFrom()),
                addresses(m.getRecipients(Message.RecipientType.TO)),
                addresses(m.getRecipients(Message.RecipientType.CC)),
                m.isSet(Flags.Flag.SEEN),
                looksMultipart(m.getContentType()),
                Math.max(m.getSize(), 0),
                iso(effectiveDate(m)),
                header(m, "Message-ID"),
                header(m, "In-Reply-To"));
    }

    /** True for a multipart/mixed envelope, which is how mail clients wrap attachments. */
    static boolean looksMultipart(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            return "mixed".equalsIgnoreCase(new jakarta.mail.internet.ContentType(contentType).getSubType());
        } catch (MessagingException e) {
            return false;
        }
    }

    public static MailTypes.Detail detail(Message m, String folderPath, long uid)
            throws MessagingException, IOException {
        Walk walk = walk(m, "");
        return new MailTypes.Detail(
                String.valueOf(uid),
                folderPath,
                decode(m.getSubject()),
                firstAddress(m.getFrom()),
                addresses(m.getRecipients(Message.RecipientType.TO)),
                addresses(m.getRecipients(Message.RecipientType.CC)),
                addresses(m.getRecipients(Message.RecipientType.BCC)),
                m.isSet(Flags.Flag.SEEN),
                m.isSet(Flags.Flag.FLAGGED),
                m.isSet(Flags.Flag.ANSWERED),
                iso(effectiveDate(m)),
                Math.max(m.getSize(), 0),
                header(m, "Message-ID"),
                splitList(header(m, "References")),
                header(m, "In-Reply-To"),
                walk.body().text(),
                walk.body().html(),
                walk.attachments(),
                headers(m));
    }

    /**
     * @param partId dotted MIME path, e.g. {@code "3"} or {@code "1.2"}
     * @return the located part, or null when the message no longer has that path
     */
    public static Part findPart(Part root, String partId) throws MessagingException, IOException {
        Part current = root;
        for (String token : partId.split("\\.")) {
            int n;
            try {
                n = Integer.parseInt(token.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            Object content = current.getContent();
            if (!(content instanceof Multipart mp) || n < 1 || n > mp.getCount()) {
                return null;
            }
            current = mp.getBodyPart(n - 1);
        }
        return current;
    }

    public static byte[] readBytes(Part part) throws MessagingException, IOException {
        try (InputStream in = part.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    record Body(String text, String html) {}

    record Walk(Body body, List<MailTypes.Attachment> attachments) {}

    /**
     * Depth-first MIME walk. {@code id} is this part's path from the message root; the root itself
     * uses the empty string and its children are {@code "1"..n}.
     */
    private static Walk walk(Part part, String id) throws MessagingException, IOException {
        String contentType = lower(part.getContentType());

        if (contentType.startsWith("multipart/") && part.getContent() instanceof Multipart mp) {
            StringBuilder text = new StringBuilder();
            StringBuilder html = new StringBuilder();
            List<MailTypes.Attachment> attachments = new ArrayList<>();
            for (int i = 1; i <= mp.getCount(); i++) {
                // Jakarta's getBodyPart is 0-based while part ids shown to clients are 1-based.
                String childId = id.isEmpty() ? String.valueOf(i) : id + "." + i;
                Walk sub = walk(mp.getBodyPart(i - 1), childId);
                attachments.addAll(sub.attachments());
                if (sub.body().text() != null) {
                    append(text, sub.body().text());
                }
                if (sub.body().html() != null) {
                    append(html, sub.body().html());
                }
            }
            return new Walk(new Body(orNull(text), orNull(html)), attachments);
        }

        boolean attachment = isAttachment(part, contentType);
        if (attachment) {
            return new Walk(new Body(null, null),
                    List.of(describe(part, id, contentType)));
        }

        if (contentType.startsWith("text/")) {
            String content = textOf(part);
            return new Walk(contentType.startsWith("text/html")
                    ? new Body(null, content) : new Body(content, null), List.of());
        }
        return new Walk(new Body(null, null), List.of());
    }

    private static boolean isAttachment(Part part, String contentType) throws MessagingException {
        String disposition = disposition(part);
        if ("attachment".equals(disposition)) {
            return true;
        }
        if (filename(part) != null) {
            return true;
        }
        // An unnamed binary/image part is only an attachment when it is not an inline body asset.
        return !contentType.startsWith("text/") && !hasContentId(part);
    }

    private static MailTypes.Attachment describe(Part part, String partId, String contentType)
            throws MessagingException {
        String name = filename(part);
        boolean inline = "inline".equals(disposition(part)) || hasContentId(part);
        return new MailTypes.Attachment(
                partId,
                decode(name != null ? name : guessName(contentType, partId)),
                baseType(contentType),
                Math.max(part.getSize(), 0),
                inline);
    }

    private static boolean hasContentId(Part part) throws MessagingException {
        String[] values = part.getHeader("Content-ID");
        return values != null && values.length > 0 && !values[0].isBlank();
    }

    private static String guessName(String contentType, String partId) {
        String sub = baseType(contentType);
        int slash = sub.indexOf('/');
        String ext = slash > 0 ? sub.substring(slash + 1) : "bin";
        return (partId.isEmpty() ? "part" : partId.replace('.', '_')) + "." + ext.replaceAll("[^a-zA-Z0-9.+-]", "_");
    }

    private static String baseType(String contentType) {
        int semi = contentType.indexOf(';');
        return (semi > 0 ? contentType.substring(0, semi) : contentType).trim();
    }

    private static String textOf(Part part) throws MessagingException, IOException {
        Object content = part.getContent();
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof InputStream in) {
            return new String(in.readAllBytes(), charsetOf(part));
        }
        return null;
    }

    private static Charset charsetOf(Part part) {
        String value = null;
        try {
            value = new jakarta.mail.internet.ContentType(part.getContentType()).getParameter("charset");
        } catch (MessagingException e) {
            return StandardCharsets.UTF_8;
        }
        if (value == null || value.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(value);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static void append(StringBuilder sb, String value) {
        if (!sb.isEmpty()) {
            sb.append("\n");
        }
        sb.append(value);
    }

    private static String orNull(StringBuilder sb) {
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String decode(String text) {
        if (text == null) {
            return null;
        }
        try {
            return MimeUtility.decodeText(text);
        } catch (IOException e) {
            return text;
        }
    }

    /** Filename from Content-Disposition, falling back to the Content-Type {@code name} parameter. */
    private static String filename(Part part) {
        String fromDisposition = dispositionParameter(part, "filename");
        if (fromDisposition != null && !fromDisposition.isBlank()) {
            return fromDisposition;
        }
        try {
            String fromType = new jakarta.mail.internet.ContentType(part.getContentType()).getParameter("name");
            return (fromType == null || fromType.isBlank()) ? null : fromType;
        } catch (MessagingException e) {
            return null;
        }
    }

    /**
     * Reads one parameter out of Content-Disposition. Prefers the RFC 2231 {@code tag*} form because it
     * carries the charset, which is how QQ names its non-ASCII attachments.
     */
    private static String dispositionParameter(Part part, String tag) {
        String[] values = headerValues(part, "Content-Disposition");
        if (values == null) {
            return null;
        }
        String joined = String.join(" ", values);
        String encoded = findParameter(joined, tag + "*");
        if (encoded != null) {
            return decodeRfc2231(encoded);
        }
        String plain = findParameter(joined, tag);
        return plain == null ? null : decode(plain);
    }

    private static String[] headerValues(Part part, String name) {
        try {
            return part.getHeader(name);
        } catch (MessagingException e) {
            return null;
        }
    }

    private static final java.util.regex.Pattern PARAM = java.util.regex.Pattern.compile(
            "(?i)(?:^|;)\\s*([a-z0-9!*_-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^;,\\s]+))");

    private static String findParameter(String header, String tag) {
        var matcher = PARAM.matcher(header);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (key.equalsIgnoreCase(tag)) {
                return matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            }
        }
        return null;
    }

    /** Turns {@code utf-8''%E4%B8%AD%E6%96%87.pdf} into the decoded string. */
    private static String decodeRfc2231(String value) {
        int split = value.indexOf("''");
        if (split < 0) {
            return value;
        }
        Charset charset;
        try {
            charset = Charset.forName(value.substring(0, split));
        } catch (RuntimeException e) {
            charset = StandardCharsets.UTF_8;
        }
        String encoded = value.substring(split + 2);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%' && i + 2 < encoded.length()) {
                try {
                    out.write(Integer.parseInt(encoded.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException e) {
                    // not a hex escape after all: fall through and keep the literal character
                }
            }
            out.write(c);
        }
        return new String(out.toByteArray(), charset);
    }

    private static String disposition(Part part) {
        String value = first(headerValues(part, "Content-Disposition"));
        if (value == null) {
            return "";
        }
        String v = value.toLowerCase(Locale.ROOT);
        if (v.startsWith("attachment")) {
            return "attachment";
        }
        return v.startsWith("inline") ? "inline" : "";
    }

    private static String first(String[] values) {
        return (values == null || values.length == 0) ? null : values[0];
    }

    private static String header(Message m, String name) throws MessagingException {
        return first(m.getHeader(name));
    }

    private static Map<String, String> headers(Message m) throws MessagingException {
        Map<String, String> out = new LinkedHashMap<>();
        var iterator = m.getAllHeaders();
        while (iterator.hasMoreElements()) {
            jakarta.mail.Header h = iterator.nextElement();
            out.merge(h.getName(), h.getValue(), (a, b) -> a + ", " + b);
        }
        return out;
    }

    private static Date effectiveDate(Message m) throws MessagingException {
        Date sent = m.getSentDate();
        return sent != null ? sent : m.getReceivedDate();
    }

    private static MailTypes.Address firstAddress(Address[] from) {
        List<MailTypes.Address> parsed = addresses(from);
        return parsed.isEmpty() ? null : parsed.get(0);
    }

    private static List<MailTypes.Address> addresses(Address[] addresses) {
        if (addresses == null) {
            return List.of();
        }
        List<MailTypes.Address> out = new ArrayList<>(addresses.length);
        for (Address a : addresses) {
            if (a instanceof jakarta.mail.internet.InternetAddress ia) {
                out.add(new MailTypes.Address(decode(ia.getPersonal()), ia.getAddress()));
            } else if (a != null) {
                out.add(new MailTypes.Address(null, a.toString()));
            }
        }
        return out;
    }

    private static List<String> splitList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.trim().split("\\s+"));
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
