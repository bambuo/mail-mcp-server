# mail-mcp-server

用 Java 邮件库（Jakarta Mail / Angus）做收发，在 Spring Boot 下以 MCP 协议暴露。
一个 jar、三种传输：**stdio**、**HTTP + SSE（legacy）**、**Streamable HTTP**。

## 工具面（7 个）

| 工具 | 作用 | 关键参数 |
| --- | --- | --- |
| `list_folders` | 账号、文件夹（含逻辑名 inbox/sent/trash/spam/drafts）与数量、各类上限 | — |
| `list_messages` | 按最新优先列信（只读头部，不拉正文） | `folder` `limit` `cursor` `read` `hasAttachment` `since` `before` `from` `to` |
| `search_messages` | 关键字 + 同样的过滤条件 | `query` `searchIn`(ALL/SUBJECT/CONTENT) + 同上 |
| `get_message` | 正文（text/html）、全部头部、线程 id、附件清单 | `folder` `uid` |
| `download_attachment` | 附件 base64 + SHA-1，可选落盘 | `folder` `uid` `attachmentId` `saveToDisk` |
| `send_message` | 发新邮件，支持附件 | `to` `cc` `bcc` `subject` `text` `html` `attachments` `replyTo` |
| `reply_message` | 回复，自动带 `In-Reply-To` / `References` | `folder` `uid` `replyAll` `text` `html` `cc` `bcc` `attachments` `subject` |

字段名一律 camelCase（`nextCursor`、`hasAttachment`、`messageId`…）。工具失败返回
`{"error":{"code":"...","message":"..."}}`，常见 code：`MAIL_NOT_CONFIGURED`、`IMAP_AUTH_FAILED`、
`UNKNOWN_FOLDER`、`MESSAGE_NOT_FOUND`、`ATTACHMENT_NOT_FOUND`、`ATTACHMENT_PATH_NOT_ALLOWED`、
`ATTACHMENT_TOO_LARGE`、`SMTP_AUTH_FAILED`、`RECIPIENT_REJECTED`、`SMTP_SEND_FAILED`。

写操作（发送/回复）**直接执行，没有二次确认**——这是最初约定的口径，客户端侧要自己把关。

## 技术栈（实际解析到的版本）

- 构建：Kotlin Toolchain 0.13.0（`module.yaml` + `./kotlin`，不依赖 Maven/Gradle）
- Java 21（源码级别 17+ 即可），Spring Boot 4.1.2
- `org.springframework.ai:spring-ai-starter-mcp-server-webmvc:2.0.2` + `spring-ai-starter-mcp-server:2.0.2`
  （stdio 自动配置也在同一个 starter 家族里）
- 传递依赖：Spring AI MCP 2.0.2 → MCP Java SDK 2.1.0（`mcp-core` + `mcp-json-jackson3`，Jackson 3）
- `jakarta.mail:jakarta.mail-api:2.1.5` + `org.eclipse.angus:angus-mail:2.0.5`

## 配置

复制 `.env.example` 为 `.env` 填入账号。程序通过 `spring.config.import=optional:file:.env[.properties]`
自动读取，也可以用真实环境变量覆盖。

| 键 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `MAIL_IMAP_HOST` / `MAIL_IMAP_PORT` | 是 | — / 993 | 收件服务器 |
| `MAIL_IMAP_SECURITY` | 否 | `ssl` | `ssl` / `starttls` / `none` |
| `MAIL_SMTP_HOST` / `MAIL_SMTP_PORT` | 发件时是 | — / 465 | 发件服务器 |
| `MAIL_SMTP_SECURITY` | 否 | `ssl` | 同上 |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | 是 | — | QQ/163/Gmail 用**应用专用密码** |
| `MAIL_FROM` / `MAIL_FROM_NAME` | 否 | 同 `MAIL_USERNAME` | 发件人显示 |
| `MAIL_ATTACH_DIR` | 否 | `./attachments` | 附件下载目录，也是发件附件白名单根目录 |
| `MAIL_ALLOW_ANY_ATTACH_PATH` | 否 | `false` | `true` 时允许把任意本地文件当附件发出 |
| `MAIL_MAX_ATTACH_BYTES` / `MAIL_MAX_DOWNLOAD_BYTES` | 否 | 10MB / 5MB | 发件附件上限 / 内联下载上限 |
| `MAIL_MAX_PAGE_SIZE` / `MAIL_MAX_SCAN_MESSAGES` | 否 | 100 / 200 | 单页上限 / 附件过滤的扫描窗口 |
| `MCP_HTTP_ADDRESS` / `MCP_HTTP_PORT` | 否 | `127.0.0.1` / 8080 | HTTP 监听地址与端口 |

## 启动

```bash
./kotlin package --format=executable-jar
cp build/tasks/_mail-mcp-server_executableJarJvm/mail-mcp-server-jvm-executable.jar build/mail-mcp-server.jar
JAR=build/mail-mcp-server.jar

# 1) Streamable HTTP（默认），端点 POST/GET/DELETE /mcp
java -jar "$JAR"

# 2) legacy HTTP + SSE，端点 GET /sse 与 POST /mcp/message
java -jar "$JAR" --spring.profiles.active=sse

# 3) stdio（MCP 客户端拉起进程用这个），JSON-RPC 走 stdin/stdout，日志只写文件
java -jar "$JAR" --spring.profiles.active=stdio

# 开发期不打包直接跑，工作目录为项目根（自动读取 .env）
./kotlin run
./kotlin run -- --spring.profiles.active=sse
```

stdio 客户端配置（路径换成实际绝对路径；凭据也可以写在客户端 `env` 里）：

仓库里已备好可直接改的 [mcp-config.example.json](mcp-config.example.json)，里面同时给了
`mail-stdio`、`mail-http`、`mail-sse` 三条，按需要删掉不用的一条：

- `mail-stdio`：客户端拉起进程，凭据走 `env`；`cwd` 指到项目根时也会自动加载 `.env`（`env` 优先）
- `mail-http` / `mail-sse`：客户端只连 URL，服务需先自己起（SSE 是另一套协议，得单独进程、另开端口）

VS Code 那类把顶层键写成 `servers` 并加 `"type": "stdio"` / `"type": "http"`，其余字段同名。

改之前先确认 `build/mail-mcp-server.jar` 存在（`./kotlin package --format=executable-jar` 后复制生成）。

## 设计取舍

- **一个进程一种传输**：Spring AI 的 `spring.ai.mcp.server.protocol` 是单值，SSE 与 Streamable
  的自动配置互斥；要同进程同时挂两个端点只能放弃自动配置、手工装配 `McpServer`。
- **stdio 下 stdout 归协议**：`logback-spring.xml` 在该 profile 只留文件 appender，banner 关闭，
  `web-application-type=none`；否则任何一行日志都会破坏 JSON-RPC 帧。
- **列表只读头部**：远端 IMAP 每拉一次正文约 1 秒，`list_messages` 用一次批量 FETCH
  （ENVELOPE/FLAGS/CONTENT_INFO + `Message-ID` + `In-Reply-To`），不解析正文。
- **`hasAttachment` 是启发式**：按顶层 `Content-Type: multipart/mixed` 判断，可能把"mixed 包着
  纯正文"的营销邮件误标为有附件；确切结果以 `get_message` 的 `attachments` 为准。
- **附件白名单**：发件附件默认只能从 `MAIL_ATTACH_DIR` 读取（含软链解析），防止被提示注入后
  把本机任意文件当附件外发；下载落盘文件名做扁平化与去重，不覆盖已有文件。
- **HTTP 无鉴权**：默认只监听 `127.0.0.1`。要对外暴露请自行加反代鉴权，这个进程能读你的邮箱、
  也能以你的名义发信。

## 实测数据（QQ 邮箱，2026-10-31）

| 操作 | 耗时 | 说明 |
| --- | --- | --- |
| `list_folders` | ~5 s | 打开每个文件夹取计数，每类一次往返 |
| `list_messages` 20 条 | ~4–5 s | 1 次 header FETCH + 每条 1 次 UID 往返 |
| `list_messages` + `hasAttachment`（扫 200 封） | ~2.7 s | 全走已拉取的头部，不拉正文 |
| `download_attachment` 2.9 MB PDF | ~10 s | `fetchsize` 调到 1 MB 后；默认 16 KB 时需要 180 次往返 |
| `get_message`（53 KB HTML） | ~2 s | 分片 FETCH |

## 测试与验收

```bash
./kotlin test                                   # 27 个单测：MIME 遍历/partId、发件组装、
                                                # 附件路径白名单、分页边界、命名解析
MAIL_IT=true ./kotlin test --include-classes=io.github.bambuo.mailmcp.mail.LiveMailTest
                                                # 只读打真实邮箱：目录、分页、附件 partId 与下载校验
node scripts/mcp_probe.mjs list                 # 对运行中的 Streamable HTTP 做 initialize + tools/list
node scripts/mcp_probe.mjs get_message '{"uid":"123"}'
node scripts/stdio_probe.mjs                    # 拉起 stdio 模式，断言 stdout 全是合法 JSON-RPC
```

## 已知限制

- 分页 `cursor` 是"匹配结果里的偏移量"，两次调用之间到达的新邮件会让边界前后挪几封；
  需要稳定顺序时用 `since`/`before` 把区间钉死。
- `searchIn=CONTENT` 是服务端的正文文本搜索，不搜附件内容，也不做分词。
- 逻辑文件夹靠候选名 + 关键字匹配（INBOX/Sent Messages/Drafts/Deleted Messages/Junk 等，
  含中文名）；映射结果在 `list_folders` 里可见，认不出的一律 `logical=other` 但可用真实路径访问。
- 不实现删除、转发：按最初方案边界（`.env.example` 只定义了收发与附件目录）。
