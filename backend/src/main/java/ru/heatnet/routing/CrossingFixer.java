package ru.heatnet.routing;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

import ru.heatnet.rules.SpatialConstraintEngine;
import ru.heatnet.rules.model.CrossingResult;

/**
 * Страховка: если после сглаживания угол пересечения road/tram &lt; 45° — вставляет локальный перпендикуляр.
 */
final class CrossingFixer {

    private final SpatialConstraintEngine engine;
    private final int dn;

    CrossingFixer(SpatialConstraintEngine engine, int dn) {
        this.engine = engine;
        this.dn = dn;
    }

    LineString fix(LineString path) {
        if (path == null || path.getNumPoints() < 2) {
            return path;
        }
        List<Coordinate> coords = new ArrayList<>();
        Coordinate[] points = path.getCoordinates();
        coords.add(points[0]);
        for (int i = 0; i < points.length - 1; i++) {
            Coordinate a = coords.get(coords.size() - 1);
            Coordinate b = points[i + 1];
            LineString segment = RoutingGeometry.line(a, b);
            List<CrossingResult> crossings = engine.findCrossings(segment, dn);
            if (needsPerpendicularFix(segment, crossings)) {
                Coordinate mid = segment.getCentroid().getCoordinate();
                Coordinate axis = pickPerpendicular(a, b);
                Coordinate p1 = offset(mid, axis, 5.0);
                Coordinate p2 = offset(mid, axis, -5.0);
                if (!engine.isSegmentBlocked(RoutingGeometry.line(a, p1), dn)
                        && !engine.isSegmentBlocked(RoutingGeometry.line(p1, b), dn)) {
                    coords.add(p1);
                } else if (!engine.isSegmentBlocked(RoutingGeometry.line(a, p2), dn)
                        && !engine.isSegmentBlocked(RoutingGeometry.line(p2, b), dn)) {
                    coords.add(p2);
                }
            }
            coords.add(b);
        }
        return RoutingGeometry.polyline(coords);
    }

    private boolean needsPerpendicularFix(LineString segment, List<CrossingResult> crossings) {
        for (CrossingResult crossing : crossings) {
            if (!"road".equals(crossing.getRestrictionType())
                    && !"tram_tracks".equals(crossing.getRestrictionType())) {
                continue;
            }
            if (!engine.isRoadCrossingAngleValid(segment, crossing.getRestrictionId(), dn)) {
                return true;
            }
        }
        return false;
    }

    private static Coordinate pickPerpendicular(Coordinate a, Coordinate b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double len = Math.hypot(dx, dy);
        if (len < 1e-9) {
            return new Coordinate(0.0, 1.0);
        }
        return new Coordinate(-dy / len, dx / len);
    }

    private static Coordinate offset(Coordinate base, Coordinate dir, double meters) {
        return new Coordinate(base.x + dir.x * meters, base.y + dir.y * meters);
    }
}
