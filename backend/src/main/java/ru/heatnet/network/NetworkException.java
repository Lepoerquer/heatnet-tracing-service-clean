package ru.heatnet.network;

/** Ошибка построения топологии M4. */
public class NetworkException extends RuntimeException {

    public NetworkException(String message) {
        super(message);
    }

    public NetworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
