package io.github.bambuo.mailmcp.mail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import io.github.bambuo.mailmcp.mail.config.MailProperties;
import org.springframework.stereotype.Component;

/**
 * Owns every filesystem touchpoint: where downloads land and where outgoing attachments may be read from.
 *
 * <p>Outgoing reads are confined to {@code mail.attach-dir} unless {@code MAIL_ALLOW_ANY_ATTACH_PATH} is
 * turned on, so a prompt-injected tool call cannot exfiltrate arbitrary local files as an attachment.
 */
@Component
public class AttachmentStore {

    private final Path root;
    private final boolean allowAnyPath;
    private final long maxOutgoingBytes;
    private final long maxDownloadBytes;

    public AttachmentStore(MailProperties props) {
        this.root = realRoot(props.attachDir());
        this.allowAnyPath = props.allowAnyAttachPath();
        this.maxOutgoingBytes = props.maxAttachBytes();
        this.maxDownloadBytes = props.maxDownloadBytes();
    }

    /**
     * The root is resolved through links: on macOS {@code /var} is a link to {@code /private/var}, and
     * comparing a real file path against an unresolved root would reject every attachment.
     */
    private static Path realRoot(String configured) {
        Path path = Paths.get(configured == null || configured.isBlank() ? "./attachments" : configured)
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(path);
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    public Path root() {
        return root;
    }

    /** @return the validated file to attach to an outgoing message */
    public Path resolveForSend(String requested) {
        if (requested == null || requested.isBlank()) {
            throw MailOperationException.invalid("attachment path must not be blank");
        }
        Path candidate = Paths.get(requested);
        if (!candidate.isAbsolute()) {
            candidate = root.resolve(candidate).normalize();
        } else {
            candidate = candidate.normalize();
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw MailOperationException.attachmentBlocked(requested, "file does not exist or is unreadable");
        }
        if (!allowAnyPath && !real.startsWith(root)) {
            throw MailOperationException.attachmentBlocked(requested,
                    "outside " + root + " (set MAIL_ALLOW_ANY_ATTACH_PATH=true to allow any local path)");
        }
        long size;
        try {
            size = Files.size(real);
        } catch (IOException e) {
            throw MailOperationException.attachmentBlocked(requested, "size could not be read");
        }
        if (maxOutgoingBytes > 0 && size > maxOutgoingBytes) {
            throw new MailOperationException("ATTACHMENT_TOO_LARGE",
                    real.getFileName() + " is " + size + " bytes, over the " + maxOutgoingBytes + " byte limit");
        }
        return real;
    }

    /** Writes a downloaded attachment under the attachments root, never overwriting an existing file. */
    public Path saveForDownload(String subDir, String fileName, byte[] data) throws IOException {
        Path dir = root.resolve(subDir);
        Files.createDirectories(dir);
        Path target = uniquePath(dir, sanitize(fileName));
        try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            out.write(data);
        }
        return target;
    }

    public void enforceDownloadSize(long bytes) {
        if (maxDownloadBytes > 0 && bytes > maxDownloadBytes) {
            throw new MailOperationException("ATTACHMENT_TOO_LARGE",
                    "attachment is " + bytes + " bytes, over the " + maxDownloadBytes
                            + " byte inline limit; it has not been downloaded");
        }
    }

    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    static String sanitize(String fileName) {
        String base = fileName == null || fileName.isBlank() ? "attachment" : fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").trim();
        return base.isBlank() ? "attachment" : (base.length() > 120 ? base.substring(0, 120) : base);
    }

    private static Path uniquePath(Path dir, String fileName) {
        Path target = dir.resolve(fileName);
        if (!Files.exists(target)) {
            return target;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            Path candidate = dir.resolve(stem + "-" + i + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new MailOperationException("ATTACHMENT_COLLISION", "no free filename under " + dir);
    }
}
