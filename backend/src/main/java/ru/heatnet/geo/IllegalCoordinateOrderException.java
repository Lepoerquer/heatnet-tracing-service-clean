package ru.heatnet.geo;

/**
 * Координаты не в порядке RFC 7946 [lon, lat].
 */
public class IllegalCoordinateOrderException extends RuntimeException {

    public IllegalCoordinateOrderException(String message) {
        super(message);
    }
}
