package ru.heatnet.ingest;

import java.util.Objects;

/** Одно диагностическое сообщение ингеста. */
public final class IngestMessage {

    private final IngestSeverity severity;
    private final String code;
    private final String objectId;
    private final String message;

    public IngestMessage(IngestSeverity severity, String code, String objectId, String message) {
        this.severity = Objects.requireNonNull(severity, "severity");
        this.code = code;
        this.objectId = objectId;
        this.message = message;
    }

    public static IngestMessage info(String code, String objectId, String message) {
        return new IngestMessage(IngestSeverity.INFO, code, objectId, message);
    }

    public static IngestMessage warning(String code, String objectId, String message) {
        return new IngestMessage(IngestSeverity.WARNING, code, objectId, message);
    }

    public static IngestMessage error(String code, String objectId, String message) {
        return new IngestMessage(IngestSeverity.ERROR, code, objectId, message);
    }

    public IngestSeverity getSeverity() {
        return severity;
    }

    public String getCode() {
        return code;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return severity + " " + code + " [" + objectId + "]: " + message;
    }
}
