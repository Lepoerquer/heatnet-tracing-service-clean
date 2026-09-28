package ru.heatnet.rules;

/** Ошибка движка пространственных ограничений M2. */
public class RulesException extends RuntimeException {

    public RulesException(String message) {
        super(message);
    }

    public RulesException(String message, Throwable cause) {
        super(message, cause);
    }
}
