package ru.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;

/** Вершина visibility graph. */
final class VisibilityVertex {

    enum Kind {
        START,
        END,
        OBSTACLE,
        PORTAL
    }

    private final int id;
    private final Coordinate coordinate;
    private final Kind kind;

    VisibilityVertex(int id, Coordinate coordinate, Kind kind) {
        this.id = id;
        this.coordinate = new Coordinate(coordinate);
        this.kind = kind;
    }

    int id() {
        return id;
    }

    Coordinate coordinate() {
        return coordinate;
    }

    Kind kind() {
        return kind;
    }
}
