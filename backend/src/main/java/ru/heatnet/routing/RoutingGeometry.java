package ru.heatnet.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.calc.reference.RulesConfig;

/** Утилиты геометрии маршрутизации в UTM. */
final class RoutingGeometry {

    private static final GeometryFactory GF = new GeometryFactory();

    private RoutingGeometry() {
    }

    static GeometryFactory factory() {
        return GF;
    }

    static LineString line(Coordinate a, Coordinate b) {
        return GF.createLineString(new Coordinate[] {copy(a), copy(b)});
    }

    static LineString polyline(List<Coordinate> coords) {
        if (coords == null || coords.isEmpty()) {
            return GF.createLineString(new Coordinate[0]);
        }
        Coordinate[] array = new Coordinate[coords.size()];
        for (int i = 0; i < coords.size(); i++) {
            array[i] = copy(coords.get(i));
        }
        return GF.createLineString(array);
    }

    static Point point(Coordinate c) {
        return GF.createPoint(copy(c));
    }

    static Coordinate copy(Coordinate c) {
        return new Coordinate(c.x, c.y);
    }

    /**
     * Угол между направлениями «к предыдущей» и «к следующей» вершине.
     * Прямая даёт 180°, разворот — 0°.
     */
    static double turnAngleDeg(Coordinate prev, Coordinate at, Coordinate next) {
        double ax = prev.x - at.x;
        double ay = prev.y - at.y;
        double bx = next.x - at.x;
        double by = next.y - at.y;
        double la = Math.hypot(ax, ay);
        double lb = Math.hypot(bx, by);
        if (la < 1e-9 || lb < 1e-9) {
            return 180.0;
        }
        double dot = (ax * bx + ay * by) / (la * lb);
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    /**
     * §2.1: отклонение от продолжения предыдущего участка.
     * 0° — движение по прямой, 180° — разворот. Допустимо до 90° включительно.
     */
    static double deviationDeg(Coordinate prev, Coordinate at, Coordinate next) {
        return 180.0 - turnAngleDeg(prev, at, next);
    }

    /** sin(1e-6°): порог «строго больше 90° + 1e-6°» в терминах скалярного произведения. */
    private static final double SHARP_EPS = Math.sin(Math.toRadians(1e-6));

    /**
     * AUDIT-12 (Claude, 24.09): то же, что {@code deviationDeg(prev, at, next) > 90° + 1e-6°}, без acos/hypot —
     * проверка выполняется на каждом шаге поиска пути (≈20 % времени Дейкстры уходило на acos).
     */
    static boolean turnSharperThan90(Coordinate prev, Coordinate at, Coordinate next) {
        double ax = prev.x - at.x;
        double ay = prev.y - at.y;
        double bx = next.x - at.x;
        double by = next.y - at.y;
        double la2 = ax * ax + ay * ay;
        double lb2 = bx * bx + by * by;
        if (la2 < 1e-18 || lb2 < 1e-18) {
            return false;
        }
        double dot = ax * bx + ay * by;
        return dot > SHARP_EPS * Math.sqrt(la2 * lb2);
    }

    static boolean isStandardTurn(double turnDeg, RulesConfig rules) {
        for (Double standard : rules.getStandardAnglesDeg()) {
            if (Math.abs(turnDeg - standard) <= rules.getAngleToleranceDeg()) {
                return true;
            }
            if (Math.abs(180.0 - turnDeg - standard) <= rules.getAngleToleranceDeg()) {
                return true;
            }
        }
        return false;
    }

    static List<Coordinate> deduplicate(List<Coordinate> raw, double toleranceM) {
        Map<String, Coordinate> unique = new LinkedHashMap<>();
        for (Coordinate c : raw) {
            String key = quantizeKey(c, toleranceM);
            unique.putIfAbsent(key, copy(c));
        }
        return new ArrayList<>(unique.values());
    }

    static String quantizeKey(Coordinate c, double toleranceM) {
        long q = Math.round(1.0 / Math.max(toleranceM, 1e-6));
        long x = Math.round(c.x * q);
        long y = Math.round(c.y * q);
        return x + ":" + y;
    }

    /**
     * AUDIT-13 (Claude, 25.09). Вершина кольца {@code vertex} (соседи {@code prev}, {@code next}), сдвинутая на
     * {@code outwardM} НАРУЖУ полигона — по биссектрисе угла, а не «от центроида». Раньше сдвиг шёл от центроида
     * полигона: у Г-, П-образных и других невыпуклых зон (здание-«подкова» ОКС 2 конкурсного набора) центроид лежит
     * во дворе, и у всех углов, обращённых во двор, «от центроида» — это ВНУТРЬ зоны. Сдвинутая вершина оказывалась
     * внутри, и граф дворов её отбрасывал: трасса не могла обогнуть угол корпуса со стороны двора и уходила в обход
     * всего здания (вариант B, ОКС 2: 155 м вместо 109 м). Из двух направлений биссектрисы берётся то, что выводит
     * из полигона; если ни одно не выводит (вырожденный угол), — прежний сдвиг от центроида.
     */
    static Coordinate outwardVertex(Coordinate prev, Coordinate vertex, Coordinate next, Polygon polygon,
                                    double outwardM) {
        double ax = vertex.x - prev.x;
        double ay = vertex.y - prev.y;
        double bx = next.x - vertex.x;
        double by = next.y - vertex.y;
        double la = Math.hypot(ax, ay);
        double lb = Math.hypot(bx, by);
        if (la > 1e-9 && lb > 1e-9) {
            double dx = ax / la - bx / lb;
            double dy = ay / la - by / lb;
            double ld = Math.hypot(dx, dy);
            if (ld < 1e-9) {
                // прямой угол 180° (вершина на прямой): нормаль к звену
                dx = -ay / la;
                dy = ax / la;
                ld = 1.0;
            }
            dx /= ld;
            dy /= ld;
            for (int sign = 1; sign >= -1; sign -= 2) {
                Coordinate c = new Coordinate(vertex.x + sign * dx * outwardM, vertex.y + sign * dy * outwardM);
                try {
                    if (!polygon.contains(point(c))) {
                        return c;
                    }
                } catch (RuntimeException ex) {
                    break;
                }
            }
        }
        return outwardVertex(vertex, polygon, outwardM);
    }

    /**
     * AUDIT-13 (Claude, 25.09). Точка на границе полигона, сдвинутая наружу по нормали к ближайшему звену границы (а не
     * «от центроида», см. {@link #outwardVertex(Coordinate, Coordinate, Coordinate, Polygon, double)}).
     */
    static Coordinate outwardFromBoundary(Coordinate onBoundary, Polygon polygon, double outwardM) {
        Coordinate[] best = null;
        Coordinate[] bestRing = null;
        int bestIndex = -1;
        double bestD = Double.POSITIVE_INFINITY;
        for (int r = 0; r <= polygon.getNumInteriorRing(); r++) {
            Coordinate[] cs = (r == 0 ? polygon.getExteriorRing() : polygon.getInteriorRingN(r - 1)).getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                double d = new org.locationtech.jts.geom.LineSegment(cs[i], cs[i + 1]).distance(onBoundary);
                if (d < bestD) {
                    bestD = d;
                    best = new Coordinate[] {cs[i], cs[i + 1]};
                    bestRing = cs;
                    bestIndex = i;
                }
            }
        }
        if (best != null) {
            // Точка — вершина кольца (например, угол здания): сдвиг по биссектрисе угла, как у вершин графа. Нормаль
            // к одному из примыкающих звеньев у угла уводила бы точку ВДОЛЬ соседнего звена — на границу.
            int n = bestRing.length - 1; // кольцо замкнуто: последняя точка = первой
            Coordinate vertex = null;
            int vi = -1;
            if (best[0].distance(onBoundary) < 1e-9) {
                vertex = best[0];
                vi = bestIndex;
            } else if (best[1].distance(onBoundary) < 1e-9) {
                vertex = best[1];
                vi = (bestIndex + 1) % n;
            }
            if (vertex != null && n >= 3) {
                Coordinate prev = bestRing[(vi - 1 + n) % n];
                Coordinate next = bestRing[(vi + 1) % n];
                return outwardVertex(prev, vertex, next, polygon, outwardM);
            }
            double ex = best[1].x - best[0].x;
            double ey = best[1].y - best[0].y;
            double le = Math.hypot(ex, ey);
            if (le > 1e-9) {
                for (int sign = 1; sign >= -1; sign -= 2) {
                    Coordinate c = new Coordinate(onBoundary.x - sign * ey / le * outwardM,
                            onBoundary.y + sign * ex / le * outwardM);
                    try {
                        // covers, а не contains: точка на границе (у соседнего звена) — не «наружу»
                        if (!polygon.covers(point(c))) {
                            return c;
                        }
                    } catch (RuntimeException ex2) {
                        break;
                    }
                }
            }
        }
        return outwardVertex(onBoundary, polygon, outwardM);
    }

    static Coordinate outwardVertex(Coordinate vertex, Polygon polygon, double outwardM) {
        Point centroid = polygon.getCentroid();
        double dx = vertex.x - centroid.getX();
        double dy = vertex.y - centroid.getY();
        double len = Math.hypot(dx, dy);
        if (len < 1e-9) {
            return copy(vertex);
        }
        return new Coordinate(vertex.x + dx / len * outwardM, vertex.y + dy / len * outwardM);
    }
}
