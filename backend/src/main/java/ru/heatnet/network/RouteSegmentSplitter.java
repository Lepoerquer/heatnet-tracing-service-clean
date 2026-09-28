package ru.heatnet.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.heatnet.calc.model.LayingMethod;
import ru.heatnet.geo.GeoUtils;
import ru.heatnet.rules.model.SpecialSection;
import ru.heatnet.routing.RouteResult;

/**
 * Разбивает маршрут M3 на участки base/special с границами technical_node (M4 NodePlacer).
 */
final class RouteSegmentSplitter {

    private final GeometryFactory gf;
    private final double toleranceM;

    RouteSegmentSplitter(GeometryFactory gf, double toleranceM) {
        this.gf = gf;
        this.toleranceM = toleranceM;
    }

    List<RoutePiece> split(RouteResult route) {
        if (!route.isFound() || route.getPathUtm() == null) {
            return Collections.emptyList();
        }
        LineString path = route.getPathUtm();
        if (path.getNumPoints() < 2) {
            return Collections.emptyList();
        }

        double totalLen = path.getLength();
        List<Interval> specials = buildSpecialIntervals(path, totalLen, route.getSpecialSections());
        List<Double> breaks = new ArrayList<>();
        breaks.add(0.0);
        breaks.add(totalLen);
        for (Interval iv : specials) {
            breaks.add(iv.startM);
            breaks.add(iv.endM);
        }
        Collections.sort(breaks);
        List<Double> unique = dedupe(breaks);

        List<RoutePiece> pieces = new ArrayList<>();
        for (int i = 0; i < unique.size() - 1; i++) {
            double a = unique.get(i);
            double b = unique.get(i + 1);
            if (b - a < toleranceM) {
                continue;
            }
            LineString sub = extractSubLine(path, a, b);
            if (sub == null || sub.getLength() < toleranceM) {
                continue;
            }
            double kSpec = 1.0;
            // AUDIT-12 (Claude, 24.09): принадлежность куска к спецучастку — по его середине. Раньше
            // проверялось пересечение интервалов с допуском 1e-6 м, а точки деления перед этим сливались с
            // допуском 1 см: концы двух почти совпадающих интервалов (дорога и чужая теплосеть кончаются в
            // одной точке, но проекции отличаются на 0,1 мм) оставляли «щепку», и весь следующий кусок до ОКС
            // (56 м с поворотами на синтетике s5) становился special с K=1,05 — ломаный спецпроход и завышенная
            // стоимость. Куски между соседними точками деления целиком лежат в интервале или вне его.
            double mid = (a + b) / 2.0;
            for (Interval iv : specials) {
                if (iv.startM < mid && mid < iv.endM) {
                    kSpec = Math.max(kSpec, iv.kSpec);
                }
            }
            LayingMethod method = kSpec > 1.0 + 1e-9 ? LayingMethod.SPECIAL : LayingMethod.BASE;
            if (method == LayingMethod.SPECIAL && sub.getNumPoints() > 2) {
                // §4: «Специальный проход выполняется одним прямым участком».
                LineString chord = straightenIfFlat(sub);
                if (chord != null) {
                    pieces.add(new RoutePiece(chord, chord.getLength(), LayingMethod.SPECIAL, kSpec));
                } else {
                    // Трасса ломается внутри спецучастка — это дефект маршрутизации, а не смена
                    // параметра; геометрию сохраняем целиком, нарушение ловит валидатор.
                    pieces.add(new RoutePiece(sub, sub.getLength(), LayingMethod.SPECIAL, kSpec));
                }
            } else {
                pieces.add(new RoutePiece(sub, sub.getLength(), method, kSpec));
            }
        }
        return mergeAdjacentEqual(pieces);
    }

    /**
     * Если подучасток фактически прямой (все внутренние вершины в пределах допуска от хорды),
     * возвращает хорду из двух точек; иначе null.
     */
    private LineString straightenIfFlat(LineString sub) {
        Coordinate a = sub.getCoordinateN(0);
        Coordinate b = sub.getCoordinateN(sub.getNumPoints() - 1);
        double chordLen = GeoUtils.distanceMeters(a, b);
        if (chordLen < 1e-9) {
            return null;
        }
        for (int i = 1; i < sub.getNumPoints() - 1; i++) {
            Coordinate p = sub.getCoordinateN(i);
            if (projectOnSegment(a, b, p).distanceM > toleranceM) {
                return null;
            }
        }
        return gf.createLineString(new Coordinate[] {a, b});
    }

    /**
     * §2.1: «technical_node используется только там, где … меняется … параметр». Соседние куски
     * с одинаковыми способом прокладки и K склеиваются, чтобы между ними не появлялся узел без смены
     * параметров. Спецучастки не склеиваются: каждый должен оставаться одним прямым участком.
     */
    private List<RoutePiece> mergeAdjacentEqual(List<RoutePiece> pieces) {
        if (pieces.size() < 2) {
            return pieces;
        }
        List<RoutePiece> out = new ArrayList<>();
        RoutePiece cur = pieces.get(0);
        for (int i = 1; i < pieces.size(); i++) {
            RoutePiece next = pieces.get(i);
            boolean sameParams = cur.getLayingMethod() == next.getLayingMethod()
                    && Math.abs(cur.getKSpec() - next.getKSpec()) < 1e-9;
            if (sameParams && cur.getLayingMethod() == LayingMethod.BASE) {
                cur = new RoutePiece(concat(cur.getGeometry(), next.getGeometry()),
                        cur.getLengthM() + next.getLengthM(), LayingMethod.BASE, 1.0);
            } else {
                out.add(cur);
                cur = next;
            }
        }
        out.add(cur);
        return out;
    }

    private LineString concat(LineString a, LineString b) {
        List<Coordinate> out = new ArrayList<>();
        for (Coordinate c : a.getCoordinates()) {
            out.add(c);
        }
        for (Coordinate c : b.getCoordinates()) {
            if (out.get(out.size() - 1).distance(c) > 1e-9) {
                out.add(c);
            }
        }
        return gf.createLineString(out.toArray(new Coordinate[0]));
    }

    private List<Interval> buildSpecialIntervals(LineString path, double totalLen,
                                                 List<SpecialSection> sections) {
        if (sections == null || sections.isEmpty()) {
            return Collections.emptyList();
        }
        List<Interval> intervals = new ArrayList<>();
        for (SpecialSection section : sections) {
            LineString spec = section.getGeometry();
            if (spec == null || spec.isEmpty()) {
                continue;
            }
            double start = positionAlong(path, spec.getCoordinateN(0), totalLen);
            double end = positionAlong(path, spec.getCoordinateN(spec.getNumPoints() - 1), totalLen);
            if (end < start) {
                double tmp = start;
                start = end;
                end = tmp;
            }
            if (end - start >= toleranceM) {
                intervals.add(new Interval(start, end, section.getKSpec()));
            }
        }
        intervals.sort(Comparator.comparingDouble(i -> i.startM));
        // QA-FIX P7 (H-8), §4: спецпроходы НЕ объединяются. Границы каждого остаются точками
        // деления («На границах общего фрагмента начинается новый участок»), а K = максимум
        // перекрывающих интервалов считается покусково в split(). Иначе дорога [10;50] K=1,60
        // и газопровод [40;80] K=1,25 давали [10;80] с K=1,60 — переплата на 30 м.
        return intervals;
    }

    private double positionAlong(LineString path, Coordinate point, double totalLen) {
        double best = 0;
        double bestDist = Double.MAX_VALUE;
        double acc = 0;
        for (int i = 0; i < path.getNumPoints() - 1; i++) {
            Coordinate a = path.getCoordinateN(i);
            Coordinate b = path.getCoordinateN(i + 1);
            double segLen = GeoUtils.distanceMeters(a, b);
            ProjectionHit hit = projectOnSegment(a, b, point);
            if (hit.distanceM < bestDist) {
                bestDist = hit.distanceM;
                double t = segLen < 1e-9 ? 0 : GeoUtils.distanceMeters(a, hit.projection) / segLen;
                best = acc + t * segLen;
            }
            acc += segLen;
        }
        return Math.max(0, Math.min(totalLen, best));
    }

    private static ProjectionHit projectOnSegment(Coordinate a, Coordinate b, Coordinate p) {
        double abx = b.x - a.x;
        double aby = b.y - a.y;
        double len2 = abx * abx + aby * aby;
        if (len2 < 1e-12) {
            return new ProjectionHit(a, GeoUtils.distanceMeters(a, p));
        }
        double t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        Coordinate proj = new Coordinate(a.x + t * abx, a.y + t * aby);
        return new ProjectionHit(proj, GeoUtils.distanceMeters(p, proj));
    }

    private LineString extractSubLine(LineString path, double fromM, double toM) {
        LineString sub = RouteGeometryUtils.extractSubLine(gf, path, fromM, toM, toleranceM);
        if (sub == null || sub.getNumPoints() < 2) {
            return null;
        }
        return sub;
    }

    private List<Double> dedupe(List<Double> breaks) {
        List<Double> unique = new ArrayList<>();
        Double prev = null;
        for (Double b : breaks) {
            if (prev == null || Math.abs(b - prev) > toleranceM) {
                unique.add(b);
                prev = b;
            }
        }
        return unique;
    }

    static final class RoutePiece {
        private final LineString geometry;
        private final double lengthM;
        private final LayingMethod layingMethod;
        private final double kSpec;

        RoutePiece(LineString geometry, double lengthM, LayingMethod layingMethod, double kSpec) {
            this.geometry = geometry;
            this.lengthM = lengthM;
            this.layingMethod = layingMethod;
            this.kSpec = kSpec;
        }

        LineString getGeometry() {
            return geometry;
        }

        double getLengthM() {
            return lengthM;
        }

        LayingMethod getLayingMethod() {
            return layingMethod;
        }

        double getKSpec() {
            return kSpec;
        }
    }

    private static final class Interval {
        private final double startM;
        private final double endM;
        private final double kSpec;

        Interval(double startM, double endM, double kSpec) {
            this.startM = startM;
            this.endM = endM;
            this.kSpec = kSpec;
        }
    }

    private static final class ProjectionHit {
        private final Coordinate projection;
        private final double distanceM;

        ProjectionHit(Coordinate projection, double distanceM) {
            this.projection = projection;
            this.distanceM = distanceM;
        }
    }
}
