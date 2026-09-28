package ru.heatnet.network;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import ru.heatnet.geo.GeoUtils;

/** Извлечение подлиний маршрута M3 в UTM с сохранением промежуточных вершин. */
final class RouteGeometryUtils {

    private RouteGeometryUtils() {
    }

    static LineString extractSubLine(GeometryFactory gf, LineString path, double fromM, double toM, double toleranceM) {
        if (path == null || path.getNumPoints() < 2) {
            return gf.createLineString(new Coordinate[0]);
        }
        double total = accumulatedLength(path);
        double a = Math.max(0, Math.min(fromM, total));
        double b = Math.max(a, Math.min(toM, total));
        if (b - a < toleranceM) {
            Coordinate p = pointAtDistance(path, a);
            return gf.createLineString(new Coordinate[] {copy(p), copy(p)});
        }

        List<Coordinate> coords = new ArrayList<>();
        coords.add(copy(pointAtDistance(path, a)));

        double acc = 0;
        for (int i = 0; i < path.getNumPoints() - 1; i++) {
            Coordinate p0 = path.getCoordinateN(i);
            Coordinate p1 = path.getCoordinateN(i + 1);
            double segLen = GeoUtils.distanceMeters(p0, p1);
            double nextAcc = acc + segLen;
            if (nextAcc > a + toleranceM && nextAcc < b - toleranceM) {
                Coordinate last = coords.get(coords.size() - 1);
                if (GeoUtils.distanceMeters(last, p1) > toleranceM) {
                    coords.add(copy(p1));
                }
            }
            acc = nextAcc;
        }

        Coordinate end = pointAtDistance(path, b);
        Coordinate last = coords.get(coords.size() - 1);
        if (GeoUtils.distanceMeters(last, end) > toleranceM || coords.size() < 2) {
            coords.add(copy(end));
        }
        if (coords.size() < 2) {
            return gf.createLineString(new Coordinate[] {copy(pointAtDistance(path, a)), copy(end)});
        }
        return gf.createLineString(coords.toArray(new Coordinate[0]));
    }

    static Coordinate pointAtDistance(LineString path, double targetM) {
        double acc = 0;
        for (int i = 0; i < path.getNumPoints() - 1; i++) {
            Coordinate a = path.getCoordinateN(i);
            Coordinate b = path.getCoordinateN(i + 1);
            double segLen = GeoUtils.distanceMeters(a, b);
            if (acc + segLen >= targetM - 1e-9) {
                if (segLen < 1e-9) {
                    return copy(a);
                }
                double t = Math.max(0, Math.min(1, (targetM - acc) / segLen));
                return new Coordinate(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
            }
            acc += segLen;
        }
        return copy(path.getCoordinateN(path.getNumPoints() - 1));
    }

    static LineString splitAtPoint(LineString line, Coordinate splitPoint, double toleranceM, GeometryFactory gf) {
        if (line == null || line.getNumPoints() < 2) {
            return line;
        }
        List<Coordinate> coords = new ArrayList<>();
        boolean inserted = false;
        coords.add(copy(line.getCoordinateN(0)));
        double acc = 0;
        double target = positionAlong(line, splitPoint);
        for (int i = 0; i < line.getNumPoints() - 1; i++) {
            Coordinate a = line.getCoordinateN(i);
            Coordinate b = line.getCoordinateN(i + 1);
            double segLen = GeoUtils.distanceMeters(a, b);
            if (!inserted && acc + segLen >= target - toleranceM && acc <= target + toleranceM) {
                Coordinate mid = copy(splitPoint);
                if (GeoUtils.distanceMeters(coords.get(coords.size() - 1), mid) > toleranceM) {
                    coords.add(mid);
                }
                inserted = true;
            }
            acc += segLen;
            if (GeoUtils.distanceMeters(coords.get(coords.size() - 1), b) > toleranceM) {
                coords.add(copy(b));
            }
        }
        if (coords.size() < 2) {
            return line;
        }
        return gf.createLineString(coords.toArray(new Coordinate[0]));
    }

    static Coordinate pointAlongLine(LineString line, double offsetM) {
        if (line == null || line.getNumPoints() < 2) {
            return line == null ? null : copy(line.getCoordinateN(0));
        }
        double target = Math.max(0, Math.min(offsetM, accumulatedLength(line)));
        return pointAtDistance(line, target);
    }

    static double accumulatedLength(LineString path) {
        double acc = 0;
        for (int i = 0; i < path.getNumPoints() - 1; i++) {
            acc += GeoUtils.distanceMeters(path.getCoordinateN(i), path.getCoordinateN(i + 1));
        }
        return acc;
    }

    static double positionAlong(LineString path, Coordinate point) {
        double best = 0;
        double bestDist = Double.MAX_VALUE;
        double acc = 0;
        for (int i = 0; i < path.getNumPoints() - 1; i++) {
            Coordinate a = path.getCoordinateN(i);
            Coordinate b = path.getCoordinateN(i + 1);
            double segLen = GeoUtils.distanceMeters(a, b);
            Coordinate proj = projectOnSegment(a, b, point);
            double d = GeoUtils.distanceMeters(point, proj);
            if (d < bestDist) {
                bestDist = d;
                best = acc + (segLen < 1e-9 ? 0 : GeoUtils.distanceMeters(a, proj));
            }
            acc += segLen;
        }
        return best;
    }

    private static Coordinate projectOnSegment(Coordinate a, Coordinate b, Coordinate p) {
        double abx = b.x - a.x;
        double aby = b.y - a.y;
        double len2 = abx * abx + aby * aby;
        if (len2 < 1e-12) {
            return a;
        }
        double t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        return new Coordinate(a.x + t * abx, a.y + t * aby);
    }

    private static Coordinate copy(Coordinate c) {
        return new Coordinate(c.x, c.y, c.getZ());
    }
}
