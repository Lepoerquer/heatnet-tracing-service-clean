package ru.heatnet.routing;

/** Ошибка конфигурации или данных маршрутизации. */
public class RoutingException extends RuntimeException {

    public RoutingException(String message) {
        super(message);
    }
}
