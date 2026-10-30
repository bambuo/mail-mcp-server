package io.github.bambuo.mailmcp.mail;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.bambuo.mailmcp.mail.config.MailProperties;
import io.github.bambuo.mailmcp.mail.config.MailSessionFactory;
import io.github.bambuo.mailmcp.mail.model.MailRequests;
import io.github.bambuo.mailmcp.mail.model.MailTypes;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.BodyTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.RecipientStringTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SentDateTerm;
import jakarta.mail.search.SubjectTerm;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.springframework.stereotype.Component;

/**
 * Read side of the mail surface: every operation opens its own IMAP store and folder and closes them
 * on the way out. A pooled connection would be faster but JavaMail folders are not safe to share
 * across concurrent tool calls, and an MCP server gets exactly that.
 */
@Component
public class ImapMailRepository {

    /** How many candidate messages a filtered page may inspect before giving up on filling itself. */
    private static final int FILTER_SCAN_MULTIPLIER = 12;

    private final MailProperties props;
    private final MailSessionFactory sessionFactory;
    private final FolderResolver folderResolver;
    private final AttachmentStore attachments;

    public ImapMailRepository(MailProperties props, MailSessionFactory sessionFactory,
            FolderResolver folderResolver, AttachmentStore attachments) {
        this.props = props;
        this.sessionFactory = sessionFactory;
        this.folderResolver = folderResolver;
        this.attachments = attachments;
    }

    public MailTypes.Catalog catalog() {
        return withStore(store -> {
            Map<String, Object> limits = new LinkedHashMap<>();
            limits.put("maxPageSize", props.maxPageSize());
            limits.put("maxScanMessages", props.maxScanMessages());
            limits.put("maxDownloadBytes", props.maxDownloadBytes());
            limits.put("maxAttachBytes", props.maxAttachBytes());
            limits.put("attachmentReadRoots", List.of(attachments.root().toString()));
            limits.put("attachmentAnyPath", props.allowAnyAttachPath());
            ZoneId zone = ZoneId.systemDefault();
            return new MailTypes.Catalog(
                    props.senderAddress(),
                    props.fromName(),
                    folderResolver.describe(store),
                    limits,
                    MimeReader.iso(new Date()),
                    zone.getId());
        });
    }

    public MailTypes.Page list(MailRequests.ListQuery q) {
        return page(q, null, MailRequests.SearchIn.ALL);
    }

    public MailTypes.Page search(MailRequests.SearchQuery q) {
        return page(q.list(), q.keyword(), q.searchIn());
    }

    private MailTypes.Page page(MailRequests.ListQuery q, String keyword, MailRequests.SearchIn searchIn) {
        int limit = clampLimit(q.limit());
        return withFolder(q.folder(), false, ctx -> {
            SearchTerm term = buildTerm(q, keyword, searchIn);
            Message[] matched = term == null ? ctx.folder().getMessages() : ctx.folder().search(term);
            List<Message> newestFirst = newestFirst(matched);
            int offset = parseCursor(q.cursor());
            int total = newestFirst.size();
            if (offset >= total) {
                return new MailTypes.Page(List.of(), q.cursor(), false, ctx.path(), total);
            }
            int scanEnd = scanEnd(total, offset, limit, q.hasAttachment(), props.maxScanMessages());
            ctx.folder().fetch(newestFirst.subList(offset, scanEnd).toArray(new Message[0]), listProfile());

            List<MailTypes.Summary> items = new ArrayList<>(limit);
            int scanned = offset;
            for (; scanned < scanEnd && items.size() < limit; scanned++) {
                Message m = newestFirst.get(scanned);
                // Filter on the already-fetched Content-Type so a discarded candidate costs no round trip.
                if (q.hasAttachment() && !MimeReader.looksMultipart(m.getContentType())) {
                    continue;
                }
                items.add(MimeReader.summary(m, ctx.path(), ctx.uid(m)));
            }
            // A filtered page that ran out of scan budget still has rows the client has not seen.
            boolean hasMore = scanned < total;
            String nextCursor = hasMore ? String.valueOf(scanned) : null;
            return new MailTypes.Page(items, nextCursor, hasMore, ctx.path(), total);
        });
    }

    public MailTypes.Detail get(String folder, String uid) {
        return withFolder(folder, false, ctx -> {
            Message m = requireByUid(ctx, uid);
            ctx.folder().fetch(new Message[] {m}, listProfile());
            return MimeReader.detail(m, ctx.path(), parseUid(uid));
        });
    }

    public MailRequests.Downloaded download(String folder, String uid, String partId, boolean saveToDisk) {
        RawAttachment raw = attachmentBytes(folder, uid, partId);
        attachments.enforceDownloadSize(raw.data().length);
        String path = null;
        if (saveToDisk) {
            try {
                path = attachments.saveForDownload(sanitizeSegment(resolveFolder(folder) + "/" + uid),
                        raw.fileName(), raw.data())
                        .toString();
            } catch (IOException e) {
                throw new MailOperationException("ATTACHMENT_SAVE_FAILED",
                        "downloaded but could not be written to " + attachments.root() + ": " + e.getMessage(), e);
            }
        }
        return new MailRequests.Downloaded(partId, raw.fileName(), raw.contentType(), raw.data().length,
                AttachmentStore.sha1(raw.data()), Base64.getEncoder().encodeToString(raw.data()), path);
    }

    /** Raw bytes of one MIME part, used by forwarding as well as by {@link #download}. */
    public RawAttachment attachmentBytes(String folder, String uid, String partId) {
        return withFolder(folder, false, ctx -> {
            Message m = requireByUid(ctx, uid);
            ctx.folder().fetch(new Message[] {m}, listProfile());
            jakarta.mail.Part part = MimeReader.findPart(m, partId);
            if (part == null) {
                throw MailOperationException.attachmentNotFound(uid, partId);
            }
            return new RawAttachment(attachmentName(part, partId), baseType(part), MimeReader.readBytes(part));
        });
    }

    /** A downloaded part before it is encoded for the wire. */
    public record RawAttachment(String fileName, String contentType, byte[] data) {}

    public String resolveFolder(String logical) {
        return withStore(store -> {
            try {
                return folderResolver.resolve(store, logical);
            } catch (FolderResolver.UnknownFolderException e) {
                throw MailOperationException.unknownFolder(e.requested, String.join(", ", e.available));
            }
        });
    }

    /** Runs {@code op} against a fresh store, mapping provider failures onto tool error codes. */
    private <T> T withStore(StoreOperation<T> op) {
        requireConfigured();
        Store store = null;
        try {
            store = sessionFactory.session().getStore(sessionFactory.storeProtocol());
            store.connect(props.imap().host(), props.username(), props.password());
            return op.apply(store);
        } catch (AuthenticationFailedException e) {
            throw new MailOperationException("IMAP_AUTH_FAILED",
                    "login rejected by " + props.imap().host() + " for " + props.username()
                            + " (QQ/163/Gmail need an application-specific password): " + e.getMessage(), e);
        } catch (MessagingException e) {
            throw new MailOperationException("IMAP_OPERATION_FAILED",
                    props.imap().host() + ": " + e.getMessage(), e);
        } finally {
            closeQuietly(store);
        }
    }

    private <T> T withFolder(String logical, boolean writable, FolderOperation<T> op) {
        return withStore(store -> {
            String path;
            try {
                path = folderResolver.resolve(store, logical);
            } catch (FolderResolver.UnknownFolderException e) {
                throw MailOperationException.unknownFolder(e.requested, String.join(", ", e.available));
            }
            try (Folder folder = store.getFolder(path)) {
                folder.open(writable ? Folder.READ_WRITE : Folder.READ_ONLY);
                if (!(folder instanceof IMAPFolder imap)) {
                    throw new MailOperationException("FOLDER_NOT_IMAP",
                            "folder " + path + " is not an IMAP folder, uid paging is unavailable");
                }
                return op.apply(new Ctx(imap, path, store));
            } catch (IOException e) {
                throw new MailOperationException("MESSAGE_READ_FAILED",
                        "folder " + path + ": " + e.getMessage(), e);
            } catch (MessagingException e) {
                throw new MailOperationException("FOLDER_OPERATION_FAILED",
                        "folder " + path + ": " + e.getMessage(), e);
            }
        });
    }

    record Ctx(IMAPFolder folder, String path, Store store) {
        long uid(Message m) {
            try {
                long uid = folder.getUID(m);
                return uid < 0 ? m.getMessageNumber() : uid;
            } catch (MessagingException e) {
                return m.getMessageNumber();
            }
        }
    }

    private static Message requireByUid(Ctx ctx, String uid) throws MessagingException {
        long parsed = parseUid(uid);
        // Angus leaves a null slot for a uid the folder does not hold, so filter before touching it.
        for (Message m : ctx.folder().getMessagesByUID(new long[] {parsed})) {
            if (m != null && !m.isExpunged()) {
                return m;
            }
        }
        throw MailOperationException.notFound(ctx.path(), uid);
    }

    private static List<Message> newestFirst(Message[] matched) {
        List<Message> list = new ArrayList<>(Arrays.asList(matched));
        list.sort(Comparator.comparingInt(Message::getMessageNumber).reversed());
        return list;
    }

    private static SearchTerm buildTerm(MailRequests.ListQuery q, String keyword, MailRequests.SearchIn searchIn)
            throws MessagingException {
        List<SearchTerm> terms = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            SearchTerm text = switch (searchIn == null ? MailRequests.SearchIn.ALL : searchIn) {
                case SUBJECT -> new SubjectTerm(keyword);
                case CONTENT -> new BodyTerm(keyword);
                case ALL -> new OrTerm(new SubjectTerm(keyword), new BodyTerm(keyword));
            };
            terms.add(text);
        }
        if (q.from() != null && !q.from().isBlank()) {
            terms.add(new FromStringTerm(q.from()));
        }
        if (q.to() != null && !q.to().isBlank()) {
            terms.add(new RecipientStringTerm(Message.RecipientType.TO, q.to()));
        }
        if (q.seen() != null) {
            terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), q.seen()));
        }
        ZoneId zone = ZoneId.systemDefault();
        if (q.since() != null) {
            terms.add(new SentDateTerm(ComparisonTerm.GE, Date.from(q.since().atStartOfDay(zone).toInstant())));
        }
        if (q.before() != null) {
            terms.add(new SentDateTerm(ComparisonTerm.LT, Date.from(q.before().atStartOfDay(zone).toInstant())));
        }
        if (terms.isEmpty()) {
            return null;
        }
        return terms.size() == 1 ? terms.get(0) : new AndTerm(terms.toArray(new SearchTerm[0]));
    }

    /**
     * Exclusive upper index of the slice a page may inspect. A filtered page reads ahead (and discards)
     * so a mailbox full of attachment-free mail still produces a full page, bounded by
     * {@code maxScanMessages} because each inspected message costs a body fetch.
     */
    static int scanEnd(int total, int offset, int limit, boolean filtered, int maxScan) {
        int span = filtered ? Math.min(Math.max(limit * FILTER_SCAN_MULTIPLIER, 50), maxScan) : limit;
        return Math.min(total, offset + span);
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return 20;
        }
        return Math.min(limit, props.maxPageSize());
    }

    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException e) {
            throw MailOperationException.invalid("cursor must be the value returned by the previous page");
        }
    }

    static long parseUid(String uid) {
        try {
            return Long.parseLong(uid.trim());
        } catch (RuntimeException e) {
            throw MailOperationException.invalid("uid must be numeric, got '" + uid + "'");
        }
    }

    /**
     * Headers the row needs beyond ENVELOPE. They ride along in the same FETCH; asking for them later
     * would cost one round trip per message.
     */
    private static FetchProfile listProfile() {
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.ENVELOPE);
        fp.add(FetchProfile.Item.FLAGS);
        fp.add(FetchProfile.Item.CONTENT_INFO);
        fp.add("Message-ID");
        fp.add("In-Reply-To");
        return fp;
    }

    private static String attachmentName(jakarta.mail.Part part, String partId) throws MessagingException {
        try {
            String name = ((jakarta.mail.internet.MimeBodyPart) part).getFileName();
            if (name != null && !name.isBlank()) {
                return jakarta.mail.internet.MimeUtility.decodeText(name);
            }
        } catch (RuntimeException | IOException | MessagingException e) {
            // fall through to the content-type guess below
        }
        return partId.replace('.', '_') + "." + baseType(part).substring(baseType(part).indexOf('/') + 1);
    }

    private static String baseType(jakarta.mail.Part part) throws MessagingException {
        String ct = part.getContentType();
        int semi = ct.indexOf(';');
        return (semi > 0 ? ct.substring(0, semi) : ct).trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String sanitizeSegment(String value) {
        return value.replaceAll("[^A-Za-z0-9._+-]", "_");
    }

    private static void closeQuietly(Store store) {
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (MessagingException ignored) {
                // nothing useful to do on a failed close
            }
        }
    }

    private void requireConfigured() {
        if (isBlank(props.imap().host()) || isBlank(props.username()) || isBlank(props.password())) {
            throw MailOperationException.notConfigured("MAIL_IMAP_HOST, MAIL_USERNAME and MAIL_PASSWORD");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @FunctionalInterface
    private interface StoreOperation<T> {
        T apply(Store store) throws MessagingException;
    }

    @FunctionalInterface
    private interface FolderOperation<T> {
        T apply(Ctx ctx) throws MessagingException, IOException;
    }
}
