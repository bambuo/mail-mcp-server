package io.github.bambuo.mailmcp.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import io.github.bambuo.mailmcp.mail.model.MailTypes;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The MIME walk is where the part-id contract is decided, so it is tested against real parsed messages
 * rather than mocks: a wrong index shows up here as a mismatched id, not as a client-side 404 later.
 */
class MimeReaderTest {

    private final Session session = Session.getInstance(new Properties());

    private MimeMessage message(String raw) throws Exception {
        return new MimeMessage(session,
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("multipart/alternative yields both bodies and no attachments")
    void alternativeBody() throws Exception {
        MimeMessage m = message("""
                Subject: =?UTF-8?B?5rWL6K+V6YKu5Lu2?=
                From: Sender <sender@example.com>
                To: me@example.com
                Content-Type: multipart/alternative; boundary="B1"

                --B1
                Content-Type: text/plain; charset=UTF-8

                plain body text
                --B1
                Content-Type: text/html; charset=UTF-8

                <p>html body</p>
                --B1--
                """);

        MailTypes.Detail d = MimeReader.detail(m, "INBOX", 7);

        assertThat(d.subject()).isEqualTo("测试邮件");
        assertThat(d.textBody()).isEqualTo("plain body text");
        assertThat(d.htmlBody()).isEqualTo("<p>html body</p>");
        assertThat(d.attachments()).isEmpty();
        assertThat(d.uid()).isEqualTo("7");
        assertThat(d.from().email()).isEqualTo("sender@example.com");
    }

    @Test
    @DisplayName("nested multiparts keep addressable dotted part ids and 0-based mapping")
    void nestedPartIds() throws Exception {
        MimeMessage m = message("""
                Subject: files
                Content-Type: multipart/mixed; boundary="M"

                --M
                Content-Type: multipart/related; boundary="R"

                --R
                Content-Type: text/html; charset=UTF-8

                <img src="cid:pic1">
                --R
                Content-ID: <pic1>
                Content-Type: image/png
                Content-Disposition: inline; filename="pic.png"
                Content-Transfer-Encoding: base64

                aGk=
                --R--
                --M
                Content-Type: application/pdf
                Content-Disposition: attachment; filename*=utf-8''%E6%8A%A5%E5%91%8A.pdf
                Content-Transfer-Encoding: base64

                aGk=
                --M--
                """);

        MailTypes.Detail d = MimeReader.detail(m, "INBOX", 11);

        assertThat(d.htmlBody()).contains("cid:pic1");
        assertThat(d.attachments()).extracting(MailTypes.Attachment::partId)
                .containsExactly("1.2", "2");
        assertThat(d.attachments()).extracting(MailTypes.Attachment::fileName)
                .containsExactly("pic.png", "报告.pdf");
        assertThat(d.attachments()).extracting(MailTypes.Attachment::contentType)
                .containsExactly("image/png", "application/pdf");

        // The part ids handed to the client must resolve back to the same parts.
        var pdf = MimeReader.findPart(m, "2");
        assertThat(pdf).isNotNull();
        assertThat(new String(MimeReader.readBytes(pdf), StandardCharsets.UTF_8)).isEqualTo("hi");
        assertThat(MimeReader.findPart(m, "5")).isNull();
        assertThat(MimeReader.findPart(m, "1.2.1")).isNull();
    }

    @Test
    @DisplayName("a stored message/rfc822 part is reported as a downloadable attachment")
    void embeddedMessageIsAttachment() throws Exception {
        MimeMessage m = message("""
                Subject: digest
                Content-Type: multipart/mixed; boundary="M"

                --M
                Content-Type: text/plain; charset=UTF-8

                see attached
                --M
                Content-Type: message/rfc822

                Subject: inner
                Content-Type: text/plain

                inner body
                --M--
                """);

        MailTypes.Detail d = MimeReader.detail(m, "INBOX", 3);

        assertThat(d.textBody()).isEqualTo("see attached");
        assertThat(d.attachments()).hasSize(1);
        assertThat(d.attachments().get(0).partId()).isEqualTo("2");
        assertThat(d.attachments().get(0).contentType()).isEqualTo("message/rfc822");
    }

    @Test
    @DisplayName("the header-only summary flags a multipart/mixed envelope as carrying an attachment")
    void summaryFlagsAttachmentsFromHeaders() throws Exception {
        MimeMessage plain = message("""
                Subject: plain
                Content-Type: text/plain; charset=UTF-8

                body
                """);
        MimeMessage mixed = message("""
                Subject: with file
                Content-Type: multipart/mixed; boundary="M"

                --M
                Content-Type: text/plain; charset=UTF-8

                body
                --M
                Content-Type: application/pdf; name="a.pdf"
                Content-Transfer-Encoding: base64

                aGk=
                --M--
                """);

        MailTypes.Summary plainSummary = MimeReader.summary(plain, "INBOX", 1);
        MailTypes.Summary mixedSummary = MimeReader.summary(mixed, "INBOX", 2);

        assertThat(plainSummary.hasAttachment()).isFalse();
        assertThat(mixedSummary.hasAttachment()).isTrue();
        assertThat(mixedSummary.subject()).isEqualTo("with file");
        assertThat(mixedSummary.uid()).isEqualTo("2");
        assertThat(mixedSummary.folder()).isEqualTo("INBOX");
    }

    @Test
    @DisplayName("looksMultipart accepts only the mixed envelope")
    void looksMultipartHeuristic() {
        assertThat(MimeReader.looksMultipart("multipart/mixed; boundary=x")).isTrue();
        assertThat(MimeReader.looksMultipart("multipart/alternative; boundary=x")).isFalse();
        assertThat(MimeReader.looksMultipart("text/plain")).isFalse();
        assertThat(MimeReader.looksMultipart(null)).isFalse();
    }

    @Test
    @DisplayName("a flagged attachment by filename alone is still listed")
    void filenameWithoutDisposition() throws Exception {
        MimeMessage m = message("""
                Subject: note
                Content-Type: multipart/mixed; boundary="M"

                --M
                Content-Type: text/plain; charset=UTF-8

                body
                --M
                Content-Type: application/octet-stream; name="data.bin"
                Content-Transfer-Encoding: base64

                aGk=
                --M--
                """);

        MailTypes.Detail d = MimeReader.detail(m, "INBOX", 4);

        assertThat(d.attachments()).singleElement()
                .satisfies(a -> {
                    assertThat(a.partId()).isEqualTo("2");
                    assertThat(a.fileName()).isEqualTo("data.bin");
                    assertThat(a.inline()).isFalse();
                });
    }
}
