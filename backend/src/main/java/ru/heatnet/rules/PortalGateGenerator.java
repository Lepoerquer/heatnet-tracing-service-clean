package ru.heatnet.rules;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.calc.reference.RestrictionRule;
import ru.heatnet.rules.model.PortalGate;

/**
 * Поперечные створы для площадных препятствий (дорога, трамвай).
 * Ось препятствия строит сервис (протокол 16.09, п. 5).
 */
public final class PortalGateGenerator {

    private static final double PORTAL_STEP_M = 15.0;

    private final GeometryFactory geometryFactory;

    public PortalGateGenerator(GeometryFactory geometryFactory) {
        this.geometryFactory = geometryFactory;
    }

    public List<PortalGate> generate(String restrictionId, Geometry geometry, RestrictionRule rule) {
        List<PortalGate> all = new ArrayList<>();
        for (Polygon polygon : polygonsOf(geometry)) {
            all.addAll(generateForPolygon(restrictionId, polygon, rule));
        }
        return all;
    }

    private List<PortalGate> generateForPolygon(String restrictionId, Polygon polygon, RestrictionRule rule) {
        Envelope env = polygon.getEnvelopeInternal();
        double width = env.getWidth();
        double height = env.getHeight();
        boolean horizontalAxis = width >= height;
        double axisAngleDeg = horizontalAxis ? 0.0 : 90.0;

        List<PortalGate> gates = new ArrayList<>();
        if (horizontalAxis) {
            double y = env.getMinY();
            while (y <= env.getMaxY() + 1e-6) {
                addPortalIfValid(restrictionId, polygon, axisAngleDeg, gates,
                        new Coordinate(env.getMinX() - 5.0, y),
                        new Coordinate(env.getMaxX() + 5.0, y));
                y += PORTAL_STEP_M;
            }
        } else {
            double x = env.getMinX();
            while (x <= env.getMaxX() + 1e-6) {
                addPortalIfValid(restrictionId, polygon, axisAngleDeg, gates,
                        new Coordinate(x, env.getMinY() - 5.0),
                        new Coordinate(x, env.getMaxY() + 5.0));
                x += PORTAL_STEP_M;
            }
        }
        return gates;
    }

    /**
     * §4: угол пересечения полигона — к ребру границы в точке входа, градусы [0, 90].
     */
    public static double crossingAngleToBoundaryDeg(LineString segment, Polygon polygon) {
        return crossingAngleToBoundaryDeg(segment, polygon, segment.intersection(polygon.getBoundary()));
    }

    /**
     * AUDIT-13 (Claude, 25.09): то же по уже вычисленному пересечению {@code inter = segment ∩ граница полигона}
     * (движок M2 считает его для проверки «звено пересекает границу» и не повторяет оверлей ради угла).
     */
    static double crossingAngleToBoundaryDeg(LineString segment, Polygon polygon, Geometry inter) {
        if (inter.isEmpty()) {
            return 90.0;
        }
        Coordinate entry = inter.getCoordinates()[0];
        Coordinate[] ring = polygon.getExteriorRing().getCoordinates();
        double best = Double.MAX_VALUE;
        Coordinate a = ring[0];
        Coordinate b = ring[1];
        for (int i = 0; i < ring.length - 1; i++) {
            double d = new org.locationtech.jts.geom.LineSegment(ring[i], ring[i + 1]).distance(entry);
            if (d < best) {
                best = d;
                a = ring[i];
                b = ring[i + 1];
            }
        }
        double edge = Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x));
        Coordinate p = segment.getCoordinateN(0);
        Coordinate q = segment.getCoordinateN(segment.getNumPoints() - 1);
        double seg = Math.toDegrees(Math.atan2(q.y - p.y, q.x - p.x));
        double diff = Math.abs(seg - edge) % 180.0;
        return diff > 90.0 ? 180.0 - diff : diff;
    }

    public boolean isCrossingAngleToBoundaryValid(LineString segment, Polygon polygon, RestrictionRule rule) {
        Double minAngle = rule.getMinCrossingAngleDeg();
        if (minAngle == null) {
            return true;
        }
        return crossingAngleToBoundaryDeg(segment, polygon) + 1e-9 >= minAngle;
    }

    static List<Polygon> polygonsOf(Geometry geometry) {
        List<Polygon> result = new ArrayList<>();
        if (geometry instanceof Polygon) {
            result.add((Polygon) geometry);
            return result;
        }
        if (geometry != null) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                Geometry part = geometry.getGeometryN(i);
                if (part instanceof Polygon && !part.isEmpty()) {
                    result.add((Polygon) part);
                }
            }
        }
        return result;
    }

    /**
     * Угол между направлением отрезка и осью препятствия, градусы [0, 90].
     */
    public static double crossingAngleDeg(LineString segment, double axisAngleDeg) {
        Coordinate a = segment.getCoordinateN(0);
        Coordinate b = segment.getCoordinateN(segment.getNumPoints() - 1);
        double segAngle = Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x));
        double diff = Math.abs(segAngle - axisAngleDeg) % 180.0;
        if (diff > 90.0) {
            diff = 180.0 - diff;
        }
        return diff;
    }

    public boolean isCrossingAngleValid(LineString segment, double axisAngleDeg, RestrictionRule rule) {
        Double minAngle = rule.getMinCrossingAngleDeg();
        if (minAngle == null) {
            return true;
        }
        return crossingAngleDeg(segment, axisAngleDeg) + 1e-9 >= minAngle;
    }

    private void addPortalIfValid(String restrictionId, Polygon polygon, double axisAngleDeg,
                                  List<PortalGate> gates, Coordinate start, Coordinate end) {
        LineString candidate = geometryFactory.createLineString(new Coordinate[] {start, end});
        Geometry clipped = candidate.intersection(polygon);
        if (clipped.isEmpty() || clipped.getLength() < 1.0) {
            return;
        }
        LineString gateLine;
        if (clipped instanceof LineString) {
            gateLine = (LineString) clipped;
        } else {
            gateLine = longestLine(clipped);
            if (gateLine == null) {
                return;
            }
        }
        double crossingAngle = axisAngleDeg == 0.0 ? 90.0 : 0.0;
        gates.add(new PortalGate(restrictionId, gateLine, axisAngleDeg, crossingAngle));
    }

    private LineString longestLine(Geometry geometry) {
        LineString best = null;
        double bestLen = 0.0;
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry g = geometry.getGeometryN(i);
            if (g instanceof LineString) {
                LineString ls = (LineString) g;
                if (ls.getLength() > bestLen) {
                    bestLen = ls.getLength();
                    best = ls;
                }
            }
        }
        return best;
    }
}
