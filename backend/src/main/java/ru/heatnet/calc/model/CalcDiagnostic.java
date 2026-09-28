package ru.heatnet.calc.model;

/** Диагностика расчёта: применённый дефолт или нарушение, не остановившее расчёт. */
public final class CalcDiagnostic {

    public enum Severity { INFO, WARNING, ERROR }

    private final Severity severity;
    private final String code;
    private final String objectId;
    private final String message;

    public CalcDiagnostic(Severity severity, String code, String objectId, String message) {
        this.severity = severity;
        this.code = code;
        this.objectId = objectId;
        this.message = message;
    }

    public static CalcDiagnostic info(String code, String objectId, String message) {
        return new CalcDiagnostic(Severity.INFO, code, objectId, message);
    }

    public static CalcDiagnostic warning(String code, String objectId, String message) {
        return new CalcDiagnostic(Severity.WARNING, code, objectId, message);
    }

    public static CalcDiagnostic error(String code, String objectId, String message) {
        return new CalcDiagnostic(Severity.ERROR, code, objectId, message);
    }

    public Severity getSeverity() {
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
