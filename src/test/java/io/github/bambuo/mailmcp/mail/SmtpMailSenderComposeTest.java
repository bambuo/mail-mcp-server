package io.github.bambuo.mailmcp.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.bambuo.mailmcp.mail.config.MailProperties;
import io.github.bambuo.mailmcp.mail.config.MailSessionFactory;
import io.github.bambuo.mailmcp.mail.model.MailRequests;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the assembled MIME without an SMTP server: body shape, part order, encoded filenames and
 * threading headers are all decided before the transport is touched.
 */
class SmtpMailSenderComposeTest {

    @TempDir
    Path dir;

    MailProperties props;
    SmtpMailSender sender;

    @BeforeEach
    void setUp() {
        props = new MailProperties(
                "me@example.com", "app-password", "me@example.com", "Me",
                dir.toString(), false, 1_000_000L, 1_000_000L, 50, 200,
                new MailProperties.Imap("localhost", 993, MailProperties.Security.SSL, Duration.ofSeconds(5)),
                new MailProperties.Smtp("localhost", 465, MailProperties.Security.SSL, true, Duration.ofSeconds(5)));
        sender = new SmtpMailSender(props, new MailSessionFactory(props), new AttachmentStore(props), null);
    }

    @Test
    @DisplayName("text plus html becomes multipart/alternative with the plain part first")
    void alternativeOrder() throws Exception {
        MimeMessage msg = sender.compose("Hi", List.of("you@example.com"), List.of(), List.of(),
                "plain text", "<p>html</p>", List.of(), Map.of());
        // Part headers are only materialised by updateHeaders(), which dispatch() triggers via saveChanges().
        msg.saveChanges();

        assertThat(msg.getContent()).isInstanceOf(MimeMultipart.class);
        assertThat(msg.isMimeType("multipart/alternative")).isTrue();
        MimeMultipart body = (MimeMultipart) msg.getContent();
        assertThat(body.getCount()).isEqualTo(2);
        assertThat(body.getBodyPart(0).isMimeType("text/plain")).isTrue();
        assertThat(body.getBodyPart(1).isMimeType("text/html")).isTrue();
    }

    @Test
    @DisplayName("no attachment keeps the message single-part")
    void singlePartWhenNoFiles() throws Exception {
        MimeMessage msg = sender.compose("Hi", List.of("you@example.com"), List.of(), List.of(),
                "just text", null, List.of(), Map.of());

        assertThat(msg.isMimeType("text/plain")).isTrue();
        assertThat(msg.getContent()).isEqualTo("just text");
    }

    @Test
    @DisplayName("a non-ASCII filename survives encode-write-read round trip")
    void filenameRoundTrip() throws Exception {
        Path file = dir.resolve("报告.pdf");
        Files.writeString(file, "hi");

        MimeMessage msg = sender.compose("Hi", List.of("you@example.com"), List.of(), List.of(),
                "body", null, List.of(file.toString()), Map.of());
        msg.saveChanges();

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        msg.writeTo(out);
        MimeMessage reparsed = new MimeMessage(Session.getInstance(new java.util.Properties()),
                new java.io.ByteArrayInputStream(out.toByteArray()));
        var detail = MimeReader.detail(reparsed, "INBOX", 1);

        assertThat(detail.attachments()).singleElement().satisfies(a -> {
            assertThat(a.fileName()).isEqualTo("报告.pdf");
            assertThat(a.contentType()).startsWith("application/");
        });
        assertThat(new String(MimeReader.readBytes(MimeReader.findPart(reparsed, "2")), StandardCharsets.UTF_8))
                .isEqualTo("hi");
    }

    @Test
    @DisplayName("threading headers reach the wire")
    void threadingHeaders() throws Exception {
        MimeMessage msg = sender.compose("Re: Hi", List.of("you@example.com"), List.of(), List.of(),
                "body", null, List.of(),
                Map.of("In-Reply-To", "<orig@example.com>", "References", "<a@example.com> <orig@example.com>"));
        msg.saveChanges();

        assertThat(msg.getHeader("In-Reply-To")).containsExactly("<orig@example.com>");
        assertThat(msg.getHeader("References")).containsExactly("<a@example.com> <orig@example.com>");
        assertThat(msg.getMessageID()).isNotBlank();
    }

    @Test
    @DisplayName("a file outside the attachment root is refused before anything is sent")
    void blocksPathOutsideRoot() throws Exception {
        Path outside = Files.createTempFile("elsewhere", ".txt");
        try {
            assertThatThrownBy(() -> sender.compose("Hi", List.of("you@example.com"), List.of(), List.of(),
                    "body", null, List.of(outside.toString()), Map.of()))
                    .isInstanceOf(MailOperationException.class)
                    .extracting(e -> ((MailOperationException) e).code)
                    .isEqualTo("ATTACHMENT_PATH_NOT_ALLOWED");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @DisplayName("an oversized attachment is refused")
    void blocksOversizedAttachment() throws Exception {
        Path big = dir.resolve("big.txt");
        Files.write(big, new byte[2048]);
        MailProperties small = new MailProperties(props.username(), props.password(), props.from(),
                props.fromName(), props.attachDir(), false, 100L, props.maxDownloadBytes(), props.maxPageSize(), props.maxScanMessages(),
                props.imap(), props.smtp());
        SmtpMailSender capped = new SmtpMailSender(small, new MailSessionFactory(small),
                new AttachmentStore(small), null);

        assertThatThrownBy(() -> capped.compose("Hi", List.of("you@example.com"), List.of(), List.of(),
                "body", null, List.of(big.toString()), Map.of()))
                .isInstanceOf(MailOperationException.class)
                .extracting(e -> ((MailOperationException) e).code)
                .isEqualTo("ATTACHMENT_TOO_LARGE");
    }

    @Test
    @DisplayName("an empty recipient list is rejected at compose time")
    void rejectsBlankBodyPair() {
        MailRequests.Outbound ob = new MailRequests.Outbound(List.of("you@example.com"), null, null, " ", null, null,
                null, null);

        assertThatThrownBy(() -> sender.send(ob))
                .isInstanceOf(MailOperationException.class)
                .hasMessageContaining("subject is required");
    }
}
