package io.github.bambuo.mailmcp.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import io.github.bambuo.mailmcp.mail.config.MailProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Cursor arithmetic is the paging contract the client depends on, so it is tested without a server. */
class PagingContractTest {

    @Test
    @DisplayName("an unfiltered page reads exactly limit rows")
    void unfilteredSpan() {
        assertThat(ImapMailRepository.scanEnd(100, 0, 20, false, 200)).isEqualTo(20);
        assertThat(ImapMailRepository.scanEnd(100, 20, 20, false, 200)).isEqualTo(40);
    }

    @Test
    @DisplayName("a filtered page reads ahead, capped by the scan budget")
    void filteredSpan() {
        assertThat(ImapMailRepository.scanEnd(1000, 0, 20, true, 200)).isEqualTo(200);
        assertThat(ImapMailRepository.scanEnd(1000, 0, 1, true, 200)).isEqualTo(50);
        assertThat(ImapMailRepository.scanEnd(1000, 0, 20, true, 60)).isEqualTo(60);
    }

    @Test
    @DisplayName("the scan never runs past the end of the result set")
    void clampedToTotal() {
        assertThat(ImapMailRepository.scanEnd(5, 0, 20, false, 200)).isEqualTo(5);
        assertThat(ImapMailRepository.scanEnd(100, 90, 20, false, 200)).isEqualTo(100);
        assertThat(ImapMailRepository.scanEnd(100, 150, 20, true, 200)).isEqualTo(100);
    }

    @Test
    @DisplayName("uids are parsed as numbers and rejected otherwise")
    void uidParsing() {
        assertThat(ImapMailRepository.parseUid(" 42 ")).isEqualTo(42L);
        assertThatThrownBy(() -> ImapMailRepository.parseUid("abc"))
                .isInstanceOf(MailOperationException.class)
                .extracting(e -> ((MailOperationException) e).code)
                .isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("folder mapping prefers the candidate table and falls back to markers")
    void folderNameMatching() {
        assertThat(FolderResolver.leaf("Mail/Deleted Messages")).isEqualTo("Deleted Messages");
        assertThat(FolderResolver.leaf("INBOX")).isEqualTo("INBOX");
        assertThat(FolderResolver.normalize("  SeNt ")).isEqualTo("sent");
    }

    @Test
    @DisplayName("a blank logical folder means INBOX")
    void blankFolderIsInbox() {
        assertThat(FolderResolver.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("sender address falls back to the username when no From is configured")
    void senderFallback() {
        MailProperties noFrom = new MailProperties("me@example.com", "pw", null, null, "./attachments",
                false, 1L, 1L, 10, 200, new MailProperties.Imap("h", 993, MailProperties.Security.SSL,
                        Duration.ofSeconds(1)),
                new MailProperties.Smtp("h", 465, MailProperties.Security.SSL, true, Duration.ofSeconds(1)));
        assertThat(noFrom.senderAddress()).isEqualTo("me@example.com");

        MailProperties withFrom = new MailProperties("me@example.com", "pw", "alias@example.com", "Me",
                "./attachments", false, 1L, 1L, 10, 200, noFrom.imap(), noFrom.smtp());
        assertThat(withFrom.senderAddress()).isEqualTo("alias@example.com");
    }
}
