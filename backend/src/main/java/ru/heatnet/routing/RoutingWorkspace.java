package ru.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import ru.heatnet.calc.reference.RulesConfig;

/**
 * Адаптивная рабочая область: BBox around from/to + margin из config/rules.yaml.
 */
final class RoutingWorkspace {

    private final Envelope envelope;

    RoutingWorkspace(Point fromUtm, Point toUtm, RulesConfig rules) {
        this(fromUtm.getCoordinate(), toUtm.getCoordinate(), rules.getRoutingWorkspaceMarginM());
    }

    RoutingWorkspace(Coordinate from, Coordinate to, double marginM) {
        Envelope env = new Envelope(from);
        env.expandToInclude(to);
        env.expandBy(marginM);
        this.envelope = env;
    }

    Envelope envelope() {
        return envelope;
    }

    boolean contains(Coordinate coordinate) {
        return envelope.contains(coordinate);
    }

    boolean intersects(Geometry geometry) {
        return geometry != null && !geometry.isEmpty() && geometry.getEnvelopeInternal().intersects(envelope);
    }
}
