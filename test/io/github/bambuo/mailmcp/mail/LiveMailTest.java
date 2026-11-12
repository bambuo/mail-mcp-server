package io.github.bambuo.mailmcp.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.github.bambuo.mailmcp.mail.config.MailProperties;
import io.github.bambuo.mailmcp.mail.config.MailSessionFactory;
import io.github.bambuo.mailmcp.mail.model.MailRequests;
import io.github.bambuo.mailmcp.mail.model.MailTypes;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Read-only acceptance against the real mailbox. Opt in with {@code MAIL_IT=true}; credentials come from
 * the environment or from {@code .env} in the project root, and the test skips itself when they are absent.
 *
 * <p>Nothing here sends or mutates mail: those are calls a test has no business making.
 */
class LiveMailTest {

    private static final Map<String, String> FILE_ENV = readDotEnv(Path.of(".env"));

    @BeforeAll
    static void requireOptIn() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("MAIL_IT")),
                "set MAIL_IT=true to run the live mailbox checks");
        Assumptions.assumeTrue(!setting("MAIL_USERNAME", "").isBlank()
                && !setting("MAIL_PASSWORD", "").isBlank(), "MAIL_USERNAME/MAIL_PASSWORD are not configured");
    }

    @Test
    @DisplayName("catalog, one page of inbox mail, and the attachment part-id contract")
    void readsRealMailbox() {
        MailProperties props = new MailProperties(
                setting("MAIL_USERNAME", ""),
                setting("MAIL_PASSWORD", ""),
                setting("MAIL_FROM", ""),
                setting("MAIL_FROM_NAME", ""),
                setting("MAIL_ATTACH_DIR", "./attachments"),
                false,
                10L * 1024 * 1024,
                5L * 1024 * 1024,
                100,
                200,
                new MailProperties.Imap(setting("MAIL_IMAP_HOST", ""),
                        Integer.parseInt(setting("MAIL_IMAP_PORT", "993")),
                        security(setting("MAIL_IMAP_SECURITY", "ssl")), Duration.ofSeconds(60)),
                new MailProperties.Smtp(setting("MAIL_SMTP_HOST", ""),
                        Integer.parseInt(setting("MAIL_SMTP_PORT", "465")),
                        security(setting("MAIL_SMTP_SECURITY", "ssl")), true, Duration.ofSeconds(60)));

        ImapMailRepository repository = new ImapMailRepository(props, new MailSessionFactory(props),
                new FolderResolver(), new AttachmentStore(props));

        MailTypes.Catalog catalog = repository.catalog();
        assertThat(catalog.email()).isNotBlank();
        assertThat(catalog.folders()).anySatisfy(f -> {
            assertThat(f.logical()).isEqualTo("inbox");
            assertThat(f.path()).isEqualTo("INBOX");
        });
        assertThat(catalog.limits()).containsKeys("maxPageSize", "maxDownloadBytes", "attachmentAnyPath");

        MailTypes.Page page = repository.list(new MailRequests.ListQuery("inbox", 3, null, null, false,
                null, null, null, null));
        assertThat(page.items().size()).isLessThanOrEqualTo(3);
        assertThat(page.folder()).isEqualTo("INBOX");

        for (MailTypes.Summary summary : page.items()) {
            MailTypes.Detail detail = repository.get("inbox", summary.uid());
            assertThat(detail.uid()).isEqualTo(summary.uid());
            assertThat(detail.subject()).isEqualTo(summary.subject());

            for (MailTypes.Attachment attachment : detail.attachments()) {
                if (attachment.size() > 1_000_000) {
                    continue;
                }
                MailRequests.Downloaded downloaded =
                        repository.download("inbox", summary.uid(), attachment.partId(), false);
                assertThat(downloaded.size()).isEqualTo(attachment.size());
                assertThat(downloaded.sha1()).hasSize(40);
            }
        }
    }

    private static String setting(String key, String fallback) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env;
        }
        String file = FILE_ENV.get(key);
        return file != null && !file.isBlank() ? file : fallback;
    }

    private static MailProperties.Security security(String value) {
        return MailProperties.Security.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    /** Minimal {@code KEY=VALUE} reader so the test can reuse the app's .env without a dotenv library. */
    static Map<String, String> readDotEnv(Path path) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isReadable(path)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                String key = trimmed.substring(0, eq).strip();
                String value = trimmed.substring(eq + 1).strip();
                boolean quoted = value.length() > 1 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")));
                out.put(key, quoted ? value.substring(1, value.length() - 1) : value);
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }
}
