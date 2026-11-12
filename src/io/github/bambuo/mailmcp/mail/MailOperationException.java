package io.github.bambuo.mailmcp.mail;

/**
 * Signals a failure the caller can act on. The {@code code} travels to the MCP client as the tool
 * error name, so keep it stable and machine-readable.
 */
public class MailOperationException extends RuntimeException {

    public final String code;

    public MailOperationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public MailOperationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public static MailOperationException unknownFolder(String requested, String available) {
        return new MailOperationException("UNKNOWN_FOLDER",
                "Folder '" + requested + "' does not exist for this account. Available: " + available);
    }

    public static MailOperationException notFound(String folder, String uid) {
        return new MailOperationException("MESSAGE_NOT_FOUND",
                "No message with uid " + uid + " in folder '" + folder + "'.");
    }

    public static MailOperationException attachmentNotFound(String uid, String partId) {
        return new MailOperationException("ATTACHMENT_NOT_FOUND",
                "Message " + uid + " has no attachment with part id '" + partId + "'.");
    }

    public static MailOperationException invalid(String what) {
        return new MailOperationException("INVALID_ARGUMENT", what);
    }

    public static MailOperationException notConfigured(String keys) {
        return new MailOperationException("MAIL_NOT_CONFIGURED",
                keys + " must be set before this call can work (see .env.example)");
    }

    public static MailOperationException attachmentBlocked(String path, String reason) {
        return new MailOperationException("ATTACHMENT_PATH_NOT_ALLOWED",
                "Refusing to read attachment '" + path + "': " + reason);
    }
}
