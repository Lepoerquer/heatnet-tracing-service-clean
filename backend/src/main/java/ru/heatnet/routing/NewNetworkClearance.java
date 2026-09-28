package ru.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;

/**
 * AUDIT-12 (Claude, 24.09). §2.1 приложения: «Новые участки не должны пересекаться между собой вне общего
 * узла». Точная топологическая проверка JTS ({@code intersection}) не видит «почти наложения»: две ветки,
 * выходящие из одной камеры под углом 0,01°, топологически касаются только в камере, но на протяжении
 * десятков метров идут в миллиметрах друг от друга — фактически одна труба поверх другой (найдено на
 * синтетическом наборе с дорогами: участки vC_stseg_1233/1248 совпадают на 16,5 м, расстояние 7 мм).
 * Здесь такие случаи считаются пересечением: вне окрестности допустимой точки касания (узел ответвления)
 * новая линия не должна подходить к другой ближе {@link #MIN_GAP_M}.
 */
public final class NewNetworkClearance {

    /** Минимальный зазор между осями новых участков вне общего узла, м. */
    public static final double MIN_GAP_M = 0.3;
    /** Окрестность общего узла, где зазор не проверяется, м (как допуск касания у семени). */
    public static final double NODE_RADIUS_M = 1.05;

    private NewNetworkClearance() {
    }

    /**
     * @return true, если {@code line} (без окрестностей {@code touch1}/{@code touch2}) подходит к {@code other}
     *         ближе {@link #MIN_GAP_M}
     */
    public static boolean tooClose(LineString line, LineString other, Coordinate touch1, Coordinate touch2) {
        if (line == null || other == null || line.isEmpty() || other.isEmpty()) {
            return false;
        }
        double d;
        try {
            d = line.distance(other);
        } catch (RuntimeException ex) {
            return false;
        }
        if (d >= MIN_GAP_M) {
            return false;
        }
        Geometry probe = trim(line, touch1);
        probe = probe instanceof LineString ? trim((LineString) probe, touch2) : cut(probe, touch2);
        if (probe == null || probe.isEmpty()) {
            return false;
        }
        try {
            return probe.distance(other) < MIN_GAP_M;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * То же для двух прямых отрезков, без JTS-оверлея (горячий цикл поиска пути). Отрезок {@code a→b}
     * укорачивается на {@link #NODE_RADIUS_M} у концов, совпадающих с точками допустимого касания.
     */
    public static boolean tooCloseSegments(Coordinate a, Coordinate b, Coordinate p0, Coordinate p1,
                                           Coordinate touch1, Coordinate touch2) {
        org.locationtech.jts.geom.LineSegment other = new org.locationtech.jts.geom.LineSegment(p0, p1);
        org.locationtech.jts.geom.LineSegment seg = new org.locationtech.jts.geom.LineSegment(a, b);
        if (seg.distance(other) >= MIN_GAP_M) {
            return false;
        }
        double len = a.distance(b);
        double from = 0.0;
        double to = len;
        boolean interiorTouch = false;
        for (Coordinate t : new Coordinate[] {touch1, touch2}) {
            if (t == null) {
                continue;
            }
            if (t.distance(a) <= 0.05) {
                from = Math.max(from, NODE_RADIUS_M);
            } else if (t.distance(b) <= 0.05) {
                to = Math.min(to, len - NODE_RADIUS_M);
            } else if (seg.distance(t) <= NODE_RADIUS_M) {
                interiorTouch = true;
            }
        }
        if (interiorTouch) {
            // редкий случай: точка касания в середине отрезка — общий путь через JTS
            org.locationtech.jts.geom.GeometryFactory gf = new org.locationtech.jts.geom.GeometryFactory();
            return tooClose(gf.createLineString(new Coordinate[] {a, b}),
                    gf.createLineString(new Coordinate[] {p0, p1}), touch1, touch2);
        }
        if (to - from <= 1e-9) {
            return false;
        }
        Coordinate s0 = new Coordinate(a.x + (b.x - a.x) * from / len, a.y + (b.y - a.y) * from / len);
        Coordinate s1 = new Coordinate(a.x + (b.x - a.x) * to / len, a.y + (b.y - a.y) * to / len);
        return new org.locationtech.jts.geom.LineSegment(s0, s1).distance(other) < MIN_GAP_M;
    }

    private static Geometry trim(LineString line, Coordinate touch) {
        if (touch == null || line == null || line.isEmpty()) {
            return line;
        }
        int n = line.getNumPoints();
        double len = line.getLength();
        if (line.getCoordinateN(0).distance(touch) <= 0.05) {
            return len <= NODE_RADIUS_M ? line.getFactory().createLineString() : subLine(line, NODE_RADIUS_M, len);
        }
        if (line.getCoordinateN(n - 1).distance(touch) <= 0.05) {
            return len <= NODE_RADIUS_M ? line.getFactory().createLineString() : subLine(line, 0.0, len - NODE_RADIUS_M);
        }
        return cut(line, touch);
    }

    private static Geometry cut(Geometry g, Coordinate touch) {
        if (touch == null || g == null || g.isEmpty()) {
            return g;
        }
        try {
            if (g.distance(g.getFactory().createPoint(touch)) > NODE_RADIUS_M) {
                return g;
            }
            return g.difference(g.getFactory().createPoint(touch).buffer(NODE_RADIUS_M, 8));
        } catch (RuntimeException ex) {
            return g;
        }
    }

    private static LineString subLine(LineString line, double from, double to) {
        java.util.List<Coordinate> out = new java.util.ArrayList<>();
        double acc = 0.0;
        for (int i = 0; i + 1 < line.getNumPoints(); i++) {
            Coordinate a = line.getCoordinateN(i);
            Coordinate b = line.getCoordinateN(i + 1);
            double l = a.distance(b);
            double s0 = acc;
            double s1 = acc + l;
            if (s1 >= from && s0 <= to && l > 1e-12) {
                double t0 = Math.max(0.0, (from - s0) / l);
                double t1 = Math.min(1.0, (to - s0) / l);
                Coordinate p0 = new Coordinate(a.x + (b.x - a.x) * t0, a.y + (b.y - a.y) * t0);
                Coordinate p1 = new Coordinate(a.x + (b.x - a.x) * t1, a.y + (b.y - a.y) * t1);
                if (out.isEmpty() || out.get(out.size() - 1).distance(p0) > 1e-9) {
                    out.add(p0);
                }
                if (out.get(out.size() - 1).distance(p1) > 1e-9) {
                    out.add(p1);
                }
            }
            acc = s1;
        }
        if (out.size() < 2) {
            return line.getFactory().createLineString();
        }
        return line.getFactory().createLineString(out.toArray(new Coordinate[0]));
    }
}
