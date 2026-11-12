package io.github.bambuo.mailmcp.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.bambuo.mailmcp.mail.model.MailTypes;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import org.springframework.stereotype.Component;

/**
 * Maps the logical folder names the MCP surface exposes ({@code inbox}, {@code sent}, ...) onto the
 * real folder paths a provider uses, which differ per provider and per account language: QQ in
 * Chinese mode names its trash {@code 已删除} while English mode uses {@code Deleted Messages}.
 *
 * <p>Resolution reads the live folder list, so an account whose Sent folder is called something not
 * in the candidate table still resolves when the name contains a known marker.
 */
@Component
public class FolderResolver {

    /** Logical name -> candidate real names, best match first. */
    private static final Map<String, List<String>> CANDIDATES = Map.of(
            "inbox", List.of("INBOX"),
            "sent", List.of("Sent Messages", "Sent", "Sent Items", "已发送"),
            "trash", List.of("Trash", "Deleted Messages", "Deleted", "Bin", "已删除", "回收站"),
            "spam", List.of("Spam", "Junk", "Bulk", "垃圾邮件", "垃圾"),
            "drafts", List.of("Drafts", "Draft", "草稿箱"),
            "archive", List.of("Archive", "Other", "其他文件夹"));

    /** Markers used for the fuzzy pass when no candidate matches exactly. */
    private static final Map<String, List<String>> MARKERS = Map.of(
            "sent", List.of("sent", "已发送", "发件"),
            "trash", List.of("trash", "deleted", "bin", "已删除", "删除"),
            "spam", List.of("spam", "junk", "bulk", "垃圾"),
            "drafts", List.of("draft", "草稿"),
            "archive", List.of("archive", "其他"));

    /**
     * @param logical one of inbox/sent/trash/spam/drafts/archive, or a literal folder path
     * @return the provider folder path to open
     * @throws UnknownFolderException when the account has nothing matching the logical name
     */
    public String resolve(Store store, String logical) throws MessagingException {
        String key = normalize(logical);
        if (key.isEmpty()) {
            return "INBOX";
        }
        if (!CANDIDATES.containsKey(key)) {
            // Caller passed a literal path; verify it exists before handing it back.
            if (store.getFolder(logical).exists()) {
                return logical;
            }
            throw new UnknownFolderException(logical, listFolderPaths(store));
        }
        List<String> available = listFolderPaths(store);
        for (String candidate : CANDIDATES.get(key)) {
            for (String path : available) {
                if (leaf(path).equalsIgnoreCase(candidate)) {
                    return path;
                }
            }
        }
        for (String marker : MARKERS.getOrDefault(key, List.of())) {
            for (String path : available) {
                if (leaf(path).toLowerCase(Locale.ROOT).contains(marker)) {
                    return path;
                }
            }
        }
        if ("inbox".equals(key)) {
            return "INBOX";
        }
        throw new UnknownFolderException(logical, available);
    }

    /** Describes every folder the account exposes, with counts, for GetMe and diagnostics. */
    public List<MailTypes.Mailbox> describe(Store store) throws MessagingException {
        List<MailTypes.Mailbox> out = new ArrayList<>();
        for (String path : listFolderPaths(store)) {
            Folder f = store.getFolder(path);
            boolean selectable = (f.getType() & Folder.HOLDS_MESSAGES) != 0;
            long total = -1;
            long unseen = -1;
            boolean opened = false;
            try {
                opened = tryOpen(f, Folder.READ_ONLY);
                if (opened) {
                    total = f.getMessageCount();
                    unseen = f.getUnreadMessageCount();
                }
            } finally {
                if (opened && f.isOpen()) {
                    f.close(false);
                }
            }
            out.add(new MailTypes.Mailbox(logicalFor(path), leaf(path), path, total, unseen, selectable));
        }
        return out;
    }

    /** Lists folder paths depth-first, skipping the special {@code *} namespace root. */
    public List<String> listFolderPaths(Store store) throws MessagingException {
        List<String> paths = new ArrayList<>();
        collect(store, store.getDefaultFolder(), paths);
        return paths;
    }

    private void collect(Store store, Folder parent, List<String> paths) throws MessagingException {
        for (Folder child : parent.list()) {
            if ((child.getType() & Folder.HOLDS_MESSAGES) != 0) {
                paths.add(child.getFullName());
            }
            if ((child.getType() & Folder.HOLDS_FOLDERS) != 0) {
                collect(store, child, paths);
            }
        }
    }

    private static String logicalFor(String path) {
        String leaf = leaf(path);
        if (leaf.equalsIgnoreCase("INBOX")) {
            return "inbox";
        }
        for (Map.Entry<String, List<String>> e : CANDIDATES.entrySet()) {
            for (String candidate : e.getValue()) {
                if (leaf.equalsIgnoreCase(candidate)) {
                    return e.getKey();
                }
            }
        }
        for (Map.Entry<String, List<String>> e : MARKERS.entrySet()) {
            for (String marker : e.getValue()) {
                if (leaf.toLowerCase(Locale.ROOT).contains(marker)) {
                    return e.getKey();
                }
            }
        }
        return "other";
    }

    private static boolean tryOpen(Folder f, int mode) {
        try {
            f.open(mode);
            return true;
        } catch (MessagingException | RuntimeException e) {
            return false;
        }
    }

    static String leaf(String path) {
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('.'));
        return i < 0 ? path : path.substring(i + 1);
    }

    static String normalize(String logical) {
        return logical == null ? "" : logical.trim().toLowerCase(Locale.ROOT);
    }

    /** Thrown when a logical folder cannot be mapped; carries what the account does have. */
    public static class UnknownFolderException extends MessagingException {
        public final String requested;
        public final List<String> available;

        UnknownFolderException(String requested, List<String> available) {
            super("Folder '" + requested + "' not found. Available: " + String.join(", ", available));
            this.requested = requested;
            this.available = List.copyOf(available);
        }
    }
}
