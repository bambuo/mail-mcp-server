package io.github.bambuo.mailmcp.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import io.github.bambuo.mailmcp.mail.config.MailProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttachmentStoreTest {

    @TempDir
    Path dir;

    MailProperties props;

    @BeforeEach
    void setUp() {
        props = new MailProperties("me@example.com", "pw", null, null, dir.toString(), false,
                1_000_000L, 1_000_000L, 50, 200,
                new MailProperties.Imap("h", 993, MailProperties.Security.SSL, Duration.ofSeconds(1)),
                new MailProperties.Smtp("h", 465, MailProperties.Security.SSL, true, Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("relative attachment names resolve inside the root")
    void relativeResolvesUnderRoot() throws IOException {
        Files.writeString(dir.resolve("note.txt"), "hi");
        AttachmentStore store = new AttachmentStore(props);

        assertThat(store.resolveForSend("note.txt")).isEqualTo(dir.resolve("note.txt").toRealPath());
    }

    @Test
    @DisplayName("absolute paths outside the root are refused unless opted in")
    void outsideRootBlocked() throws IOException {
        Path outside = Files.createTempFile("outside", ".txt");
        AttachmentStore store = new AttachmentStore(props);

        assertThatThrownBy(() -> store.resolveForSend(outside.toString()))
                .isInstanceOf(MailOperationException.class)
                .extracting(e -> ((MailOperationException) e).code)
                .isEqualTo("ATTACHMENT_PATH_NOT_ALLOWED");

        MailProperties anyPath = new MailProperties(props.username(), props.password(), props.from(),
                props.fromName(), props.attachDir(), true, props.maxAttachBytes(), props.maxDownloadBytes(),
                props.maxPageSize(), props.maxScanMessages(), props.imap(), props.smtp());
        assertThat(new AttachmentStore(anyPath).resolveForSend(outside.toString())).isNotNull();

        Files.deleteIfExists(outside);
    }

    @Test
    @DisplayName("a path that escapes the root through .. is blocked")
    void traversalBlocked() throws IOException {
        AttachmentStore store = new AttachmentStore(props);

        assertThatThrownBy(() -> store.resolveForSend("../../etc/passwd"))
                .isInstanceOf(MailOperationException.class)
                .extracting(e -> ((MailOperationException) e).code)
                .isEqualTo("ATTACHMENT_PATH_NOT_ALLOWED");
    }

    @Test
    @DisplayName("a missing file is reported as blocked, not as a crash")
    void missingFileBlocked() {
        AttachmentStore store = new AttachmentStore(props);

        assertThatThrownBy(() -> store.resolveForSend("nope.txt"))
                .isInstanceOf(MailOperationException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("downloaded names are flattened and made unique instead of overwriting")
    void saveIsSandboxedAndUnique() throws IOException {
        AttachmentStore store = new AttachmentStore(props);
        byte[] data = "payload".getBytes();

        Path first = store.saveForDownload("12/34", "../../evil name:*.txt", data);
        Path second = store.saveForDownload("12/34", "evil name__.txt", data);

        assertThat(first).startsWith(dir);
        assertThat(first.getFileName().toString()).doesNotContain("*").doesNotContain(":");
        assertThat(second).isNotEqualTo(first);
        assertThat(Files.exists(first)).isTrue();
        assertThat(Files.readString(second)).isEqualTo("payload");
    }

    @Test
    @DisplayName("sanitize keeps a usable name for unicode filenames")
    void sanitizeKeepsUnicode() {
        assertThat(AttachmentStore.sanitize("报告-2026.pdf")).isEqualTo("报告-2026.pdf");
        assertThat(AttachmentStore.sanitize(null)).isEqualTo("attachment");
        assertThat(AttachmentStore.sanitize("a/b/c.txt")).isEqualTo("c.txt");
    }

    @Test
    @DisplayName("inline download cap fires before bytes are returned")
    void downloadCapEnforced() {
        MailProperties tight = new MailProperties(props.username(), props.password(), props.from(),
                props.fromName(), props.attachDir(), false, props.maxAttachBytes(), 10L, props.maxPageSize(), props.maxScanMessages(),
                props.imap(), props.smtp());
        AttachmentStore store = new AttachmentStore(tight);

        assertThatThrownBy(() -> store.enforceDownloadSize(11)).isInstanceOf(MailOperationException.class);
        store.enforceDownloadSize(10);
    }
}
