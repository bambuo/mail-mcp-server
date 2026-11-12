package io.github.bambuo.mailmcp;

import io.github.bambuo.mailmcp.mail.config.MailProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Mail over MCP. One build, three transports, selected by profile:
 *
 * <ul>
 *   <li>{@code -Dspring.profiles.active=stdio} &mdash; JSON-RPC over stdin/stdout, for MCP clients that
 *       spawn the process</li>
 *   <li>{@code -Dspring.profiles.active=sse} &mdash; legacy HTTP + SSE endpoints</li>
 *   <li>default &mdash; Streamable HTTP on {@code /mcp}</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(MailProperties.class)
public class MailMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(MailMcpApplication.class, args);
    }
}
