package ru.heatnet.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.distance.DistanceOp;

import ru.heatnet.rules.SpatialConstraintEngine;

/**
 * AUDIT-24.09 (Claude). Финальный прямой заход в собственный полигон ОКС (§2.2 приложения,
 * Разъяснение №3): «допускается один финальный прямой участок от ближайшей к точке границы
 * полигона до самой точки». Отступ к своему полигону на этот участок не распространяется,
 * «в том числе на его часть в зоне отступа перед границей».
 *
 * <p>Геометрически заход — луч из точки подключения P через точку границы B и дальше наружу:
 * трасса приходит в точку Q на этом луче за пределами зоны отступа своего полигона и оттуда
 * одним прямым отрезком Q→B→P идёт к точке. Длина участка внутри здания равна |PB|.</p>
 *
 * <p>Кандидаты упорядочены по |PB|. Уровень 0 — ближайшая граница (с допуском 0,5 м); если заход
 * с неё невозможен (граница смотрит во внутренний двор/нишу уже зоны отступа, заход упирается
 * в соседнее здание и т.п.), следующие уровни — ближайшие из ДОСТУПНЫХ стен. Планировщик
 * повышает уровень только тогда, когда на текущем уровне маршрут не найден, — так соблюдается
 * смысл правила «ближайшая граница», а ОКС с глухой ближайшей стеной не остаётся без подключения
 * из-за формы здания (организаторы гарантируют подключаемость всех ОКС).</p>
 *
 * <p>AUDIT-13 (Claude, 25.09): {@link #computeRelaxed} — кандидаты для доводки готовой ветки
 * ({@code SteinerPlanner.polishLeaves}): заход через ТУ ЖЕ ближайшую доступную стену (звено границы на том же
 * расстоянии от P, ±{@value #SAME_WALL_TOLERANCE_M} м) не глубже max(ближайшая доступная + 0,2 м; расстояние до
 * границы + {@value #RELAXED_EXCESS_M} м), с мелкой выборкой стены (0,25 м) и без склейки лучей через ~10°.
 * §2.2 требует захода «от ближайшей к точке границы полигона», а не строго перпендикулярного: жёсткое окно
 * +0,2 м и склейка соседних лучей оставляли у ОКС 2 конкурсного набора единственный луч, касающийся угла
 * ниши, — и петлю-«пятиугольник» 78 м вместо 62,5 м (вариант A), 159 м вместо 110 м (вариант B).</p>
 */
public final class OwnEntryCandidates {

    /** Окна уровней: превышение |PB| над минимально достижимым, м. */
    public static final double[] LEVEL_WINDOWS = {0.2, 1.0, 3.0, 6.0, 12.0, 25.0, Double.POSITIVE_INFINITY};
    private static final double SAMPLE_STEP_M = 1.0;
    private static final double RAY_STEP_M = 0.25;
    private static final double RAY_MAX_M = 60.0;
    private static final double ON_BOUNDARY_M = 0.05;
    private static final int MAX_EVALUATED = 4000;
    /** Доводка (computeRelaxed): превышение захода над расстоянием до границы, м. */
    public static final double RELAXED_EXCESS_M = 1.0;
    /** «Та же стена»: звено границы на расстоянии от P не больше, чем у лучшей доступной стены + допуск, м. */
    public static final double SAME_WALL_TOLERANCE_M = 0.2;
    private static final double NEAR_SAMPLE_STEP_M = 0.25;
    private static final double NEAR_WALL_M = 3.0;
    private static final double FINE_DEDUPE_BOUNDARY_M = 0.3;
    private static final double FINE_DEDUPE_COS = 0.9994;
    private static final int FINE_MAX_KEPT = 45;

    private OwnEntryCandidates() {
    }

    /** Одна возможная точка входа трассы на финальный прямой. */
    public static final class Entry {
        /** Точка на луче захода вне зоны отступа своего полигона: сюда приходит трасса. */
        public final Coordinate q;
        /** Точка границы полигона на финальном прямом. */
        public final Coordinate boundary;
        /** Длина финального участка внутри полигона, м. */
        public final double insideM;
        /** Уровень ({@link #LEVEL_WINDOWS}): 0 — ближайшая граница. */
        public final int level;

        public Entry(Coordinate q, Coordinate boundary, double insideM, int level) {
            this.q = q;
            this.boundary = boundary;
            this.insideM = insideM;
            this.level = level;
        }
    }

    /**
     * @param p            точка подключения (UTM)
     * @param own          полигон/мультиполигон ОКС, содержащий точку
     * @param clearanceM   отступ до своего полигона для трассы ДО финального участка
     *                     (отступ ОКС по ДУ + половина ширины пары)
     * @param fullEngine   все ограничения (включая свой полигон)
     * @param approach     ограничения без своего полигона (для самого финального отрезка)
     */
    public static List<Entry> compute(Coordinate p, Geometry own, double clearanceM, int dn,
                                      SpatialConstraintEngine fullEngine, SpatialConstraintEngine approach,
                                      GeometryFactory gf) {
        return compute(p, own, clearanceM, dn, fullEngine, approach, gf, false);
    }

    /**
     * AUDIT-13 (Claude, 25.09). Заходы через ту же ближайшую доступную стену для доводки ветки (см. описание класса):
     * все с уровнем 0, упорядочены по |PB|.
     */
    public static List<Entry> computeRelaxed(Coordinate p, Geometry own, double clearanceM, int dn,
                                             SpatialConstraintEngine fullEngine, SpatialConstraintEngine approach,
                                             GeometryFactory gf) {
        return compute(p, own, clearanceM, dn, fullEngine, approach, gf, true);
    }

    private static List<Entry> compute(Coordinate p, Geometry own, double clearanceM, int dn,
                                       SpatialConstraintEngine fullEngine, SpatialConstraintEngine approach,
                                       GeometryFactory gf, boolean relaxed) {
        List<Entry> result = new ArrayList<>();
        if (p == null || own == null || own.isEmpty()) {
            return result;
        }
        Point pp = gf.createPoint(p);
        Polygon part = containingPart(own, pp);
        if (part == null) {
            return result;
        }
        Geometry boundary = own.getBoundary();
        Shape shape = new Shape(own);
        org.locationtech.jts.geom.Envelope env = own.getEnvelopeInternal();

        // 1. Геометрические кандидаты: направления на точки границы содержащей части.
        List<Raw> raws = new ArrayList<>();
        double dmin = pp.distance(boundary);
        if (dmin <= ON_BOUNDARY_M) {
            // Точка на самой границе: внутри здания трасса не идёт (|PB| = 0), заход — по наружной
            // нормали к ближайшему звену и веером до ±80°.
            raws.addAll(boundaryFan(p, own, boundary, gf));
        } else {
            for (int r = 0; r <= part.getNumInteriorRing(); r++) {
                LineString ring = r == 0 ? part.getExteriorRing() : part.getInteriorRingN(r - 1);
                for (Coordinate s : sampleRing(ring, p, relaxed ? dmin : -1.0)) {
                    Raw raw = rayEntry(p, s, shape);
                    if (raw != null) {
                        raws.add(raw);
                    }
                }
            }
        }
        if (raws.isEmpty()) {
            return result;
        }
        raws.sort(Comparator.comparingDouble(r -> r.insideM));

        // 2. Проверки ограничений в порядке возрастания |PB|; дубли направлений отбрасываются.
        double best = Double.NaN;
        double bestWall = Double.NaN;
        int evaluated = 0;
        double dedupeM = relaxed ? FINE_DEDUPE_BOUNDARY_M : 1.0;
        double dedupeCos = relaxed ? FINE_DEDUPE_COS : 0.985;
        int maxKept = relaxed ? FINE_MAX_KEPT : 30;
        List<Raw> kept = new ArrayList<>();
        for (Raw raw : raws) {
            if (evaluated++ > MAX_EVALUATED) {
                break;
            }
            boolean duplicate = false;
            for (Raw k : kept) {
                if (k.boundary.distance(raw.boundary) < dedupeM
                        && Math.abs(k.ux * raw.ux + k.uy * raw.uy) > dedupeCos) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                continue;
            }
            List<Coordinate> qs = placeQ(p, raw, own, clearanceM, dn, fullEngine, approach, gf, shape);
            if (qs.isEmpty()) {
                continue;
            }
            if (Double.isNaN(best)) {
                best = raw.insideM;
                bestWall = raw.wallDist;
            }
            if (relaxed) {
                // та же стена и не глубже max(лучшая доступная + 0,2; расстояние до границы + 1,0)
                double limit = Math.max(best + LEVEL_WINDOWS[0], dmin + RELAXED_EXCESS_M);
                if (raw.insideM > limit + 1e-9) {
                    break; // лучи упорядочены по |PB| — дальше только глубже
                }
                if (raw.wallDist > bestWall + SAME_WALL_TOLERANCE_M) {
                    continue;
                }
            }
            int level = relaxed ? 0 : levelOf(raw.insideM - best);
            for (Coordinate q : qs) {
                result.add(new Entry(q, raw.boundary, raw.insideM, level));
            }
            kept.add(raw);
            if (kept.size() >= maxKept) {
                break;
            }
        }
        return result;
    }

    /**
     * Уровень захода по превышению его длины внутри полигона над наименьшей достижимой ({@link #LEVEL_WINDOWS}).
     * AUDIT-13: открыт для доводки при фактическом ДУ ветки (SteinerPlanner) — уровень прежней ветки пересчитывается
     * относительно заходов этого ДУ.
     */
    public static int levelOf(double excessM) {
        for (int i = 0; i < LEVEL_WINDOWS.length; i++) {
            if (excessM <= LEVEL_WINDOWS[i] + 1e-9) {
                return i;
            }
        }
        return LEVEL_WINDOWS.length - 1;
    }

    private static List<Coordinate> placeQ(Coordinate p, Raw raw, Geometry own, double clearanceM, int dn,
                                           SpatialConstraintEngine fullEngine, SpatialConstraintEngine approach,
                                           GeometryFactory gf, Shape shape) {
        List<Coordinate> qs = new ArrayList<>();
        Coordinate b = raw.boundary;
        double ux = raw.ux;
        double uy = raw.uy;
        Double first = null;
        for (double t = RAY_STEP_M; t <= RAY_MAX_M; t += RAY_STEP_M) {
            Coordinate x = new Coordinate(b.x + ux * t, b.y + uy * t);
            Point xp = gf.createPoint(x);
            if (shape.prepared.intersects(xp)) {
                return qs; // луч снова вошёл в своё здание — стена смотрит в нишу/двор
            }
            double dist = shape.distance.distance(xp);
            if (dist < clearanceM + 0.05) {
                // до выхода из зоны отступа шагаем крупнее, если до неё далеко
                t += Math.max(0.0, Math.floor((clearanceM + 0.05 - dist) / RAY_STEP_M) - 1) * RAY_STEP_M;
                continue;
            }
            if (isFreeQ(x, ux, uy, dn, fullEngine, gf)) {
                first = t;
                break;
            }
            t += 0.75; // проверки движка — не чаще раза в метр
        }
        if (first == null) {
            return qs;
        }
        // Замкнутый внутренний двор (дыра полигона): заход «с ближайшей стены» во двор невозможен
        // без пересечения самого здания — такие направления не рассматриваются.
        if (shape.shells.covers(gf.createPoint(new Coordinate(b.x + ux * first, b.y + uy * first)))) {
            return qs;
        }
        double[] extra = {0.0, 1.0, 2.5, 5.0, 8.0, 12.0, 18.0, 25.0};
        for (double e : extra) {
            double t = first + e;
            Coordinate q = new Coordinate(b.x + ux * t, b.y + uy * t);
            LineString leg = gf.createLineString(new Coordinate[] {q, new Coordinate(p)});
            try {
                // от границы до Q луч не должен повторно входить в своё здание
                if (shape.prepared.intersects(gf.createLineString(new Coordinate[] {
                        new Coordinate(b.x + ux * 0.02, b.y + uy * 0.02), q}))) {
                    break;
                }
                if (e > 0 && !isFreeQ(q, ux, uy, dn, fullEngine, gf)) {
                    continue;
                }
                if (approach.isSegmentBlocked(leg, dn)) {
                    continue;
                }
            } catch (RuntimeException ex) {
                continue;
            }
            qs.add(q);
        }
        return qs;
    }

    /** Q вне всех запретных зон (с учётом своего полигона) и не в зоне спецпрохода. */
    private static boolean isFreeQ(Coordinate q, double ux, double uy, int dn, SpatialConstraintEngine fullEngine,
                                   GeometryFactory gf) {
        LineString probe = gf.createLineString(new Coordinate[] {q, new Coordinate(q.x + ux * 0.05, q.y + uy * 0.05)});
        try {
            return !fullEngine.isSegmentBlocked(probe, dn) && !fullEngine.isInsideSpecialZone(q);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Подготовленная геометрия своего полигона: быстрые intersects и расстояние. */
    private static final class Shape {
        final org.locationtech.jts.geom.prep.PreparedGeometry prepared;
        final org.locationtech.jts.operation.distance.IndexedFacetDistance distance;
        /** Полигоны без дыр: точка внутри — во внутреннем замкнутом дворе здания, снаружи туда не попасть. */
        final org.locationtech.jts.geom.prep.PreparedGeometry shells;

        final GeometryFactory factory;

        Shape(Geometry own) {
            this.factory = own.getFactory();
            this.prepared = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(own);
            this.distance = new org.locationtech.jts.operation.distance.IndexedFacetDistance(own);
            List<Polygon> filled = new ArrayList<>();
            for (int i = 0; i < own.getNumGeometries(); i++) {
                Geometry g = own.getGeometryN(i);
                if (g instanceof Polygon) {
                    filled.add(own.getFactory().createPolygon(((Polygon) g).getExteriorRing().getCoordinates()));
                }
            }
            Geometry union = own.getFactory().createMultiPolygon(filled.toArray(new Polygon[0]));
            this.shells = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(union);
            List<double[]> es = new ArrayList<>();
            for (int i = 0; i < own.getNumGeometries(); i++) {
                Geometry g = own.getGeometryN(i);
                if (!(g instanceof Polygon)) {
                    continue;
                }
                Polygon poly = (Polygon) g;
                for (int r = 0; r <= poly.getNumInteriorRing(); r++) {
                    Coordinate[] cs = (r == 0 ? poly.getExteriorRing() : poly.getInteriorRingN(r - 1)).getCoordinates();
                    for (int k = 0; k + 1 < cs.length; k++) {
                        es.add(new double[] {cs[k].x, cs[k].y, cs[k + 1].x, cs[k + 1].y});
                    }
                }
            }
            this.edges = es.toArray(new double[0][]);
        }

        /** Звенья всех колец полигона: {x1, y1, x2, y2}. */
        final double[][] edges;
    }

    /**
     * Луч из P в направлении точки S: первая точка выхода на границу своего полигона.
     *
     * <p>AUDIT-12 (Claude, 24.09): пересечение луча с границей считается арифметикой по звеньям кольца, а не
     * {@code LineString.intersection} (оверлей JTS на каждую точку выборки через 1 м — ~22 % времени плана на
     * конкурсном наборе). Результат тот же: ближайшая к P точка границы на луче дальше 1e-6 м.</p>
     */
    private static Raw rayEntry(Coordinate p, Coordinate s, Shape shape) {
        double dx = s.x - p.x;
        double dy = s.y - p.y;
        double len = Math.hypot(dx, dy);
        if (len < ON_BOUNDARY_M) {
            // точка на самой границе: направление — наружная нормаль
            return null;
        }
        double ux = dx / len;
        double uy = dy / len;
        double best = Double.POSITIVE_INFINITY;
        double[] bestEdge = null;
        for (double[] e : shape.edges) {
            double ex = e[2] - e[0];
            double ey = e[3] - e[1];
            double denom = ux * ey - uy * ex;
            double wx = e[0] - p.x;
            double wy = e[1] - p.y;
            if (Math.abs(denom) < 1e-12) {
                // луч параллелен звену: касание только при коллинеарности — берём ближайший конец впереди
                if (Math.abs(wx * uy - wy * ux) > 1e-9) {
                    continue;
                }
                double t1 = wx * ux + wy * uy;
                double t2 = (e[2] - p.x) * ux + (e[3] - p.y) * uy;
                double lo = Math.min(t1, t2);
                double hi = Math.max(t1, t2);
                double t = lo > 1e-6 ? lo : (hi > 1e-6 ? Math.max(1e-6, lo) : Double.NaN);
                if (!Double.isNaN(t) && t < best) {
                    best = t;
                    bestEdge = e;
                }
                continue;
            }
            double t = (wx * ey - wy * ex) / denom;       // вдоль луча
            double v = (wx * uy - wy * ux) / denom;       // вдоль звена, [0, 1]
            if (v < -1e-12 || v > 1.0 + 1e-12 || t <= 1e-6) {
                continue;
            }
            if (t < best) {
                best = t;
                bestEdge = e;
            }
        }
        if (best == Double.POSITIVE_INFINITY || bestEdge == null) {
            return null;
        }
        Coordinate hit = new Coordinate(p.x + ux * best, p.y + uy * best);
        // после точки выхода луч должен идти наружу (не по границе/внутрь)
        Coordinate after = new Coordinate(hit.x + ux * 0.05, hit.y + uy * 0.05);
        if (shape.prepared.intersects(shape.factory.createPoint(after))) {
            return null;
        }
        return new Raw(hit, best, ux, uy, segmentDistance(p, bestEdge));
    }

    /** Расстояние от точки до звена {x1, y1, x2, y2}. */
    private static double segmentDistance(Coordinate p, double[] e) {
        double ex = e[2] - e[0];
        double ey = e[3] - e[1];
        double len2 = ex * ex + ey * ey;
        double t = len2 < 1e-18 ? 0.0 : ((p.x - e[0]) * ex + (p.y - e[1]) * ey) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(p.x - (e[0] + ex * t), p.y - (e[1] + ey * t));
    }

    private static List<Raw> boundaryFan(Coordinate p, Geometry own, Geometry boundary, GeometryFactory gf) {
        List<Raw> out = new ArrayList<>();
        Coordinate[] near = DistanceOp.nearestPoints(boundary, gf.createPoint(p));
        Coordinate n0 = near[0];
        // звено границы, на котором лежит ближайшая точка
        double bestD = Double.POSITIVE_INFINITY;
        Coordinate ea = null;
        Coordinate eb = null;
        for (int g = 0; g < own.getNumGeometries(); g++) {
            Geometry part = own.getGeometryN(g);
            if (!(part instanceof Polygon)) {
                continue;
            }
            Polygon poly = (Polygon) part;
            for (int r = 0; r <= poly.getNumInteriorRing(); r++) {
                Coordinate[] cs = (r == 0 ? poly.getExteriorRing() : poly.getInteriorRingN(r - 1)).getCoordinates();
                for (int i = 0; i + 1 < cs.length; i++) {
                    double d = new org.locationtech.jts.geom.LineSegment(cs[i], cs[i + 1]).distance(n0);
                    if (d < bestD) {
                        bestD = d;
                        ea = cs[i];
                        eb = cs[i + 1];
                    }
                }
            }
        }
        if (ea == null) {
            return out;
        }
        double ex = eb.x - ea.x;
        double ey = eb.y - ea.y;
        double el = Math.hypot(ex, ey);
        if (el < 1e-9) {
            return out;
        }
        double nx = -ey / el;
        double ny = ex / el;
        if (own.intersects(gf.createPoint(new Coordinate(p.x + nx * 0.2, p.y + ny * 0.2)))) {
            nx = -nx;
            ny = -ny;
        }
        double[] fan = {0, 20, -20, 40, -40, 60, -60, 80, -80};
        for (double deg : fan) {
            double a = Math.toRadians(deg);
            double ux = nx * Math.cos(a) - ny * Math.sin(a);
            double uy = nx * Math.sin(a) + ny * Math.cos(a);
            if (own.intersects(gf.createPoint(new Coordinate(p.x + ux * 0.2, p.y + uy * 0.2)))) {
                continue;
            }
            out.add(new Raw(new Coordinate(p), 0.0, ux, uy, 0.0));
        }
        return out;
    }

    /**
     * @param fineNearM если &gt; 0: у стен не дальше {@code fineNearM + 3 м} от P — выборка через 0,25 м (угловой шаг
     *                  лучей ~2–3° вместо ~10°; AUDIT-13, доводка); иначе — через 1 м, как раньше
     */
    private static List<Coordinate> sampleRing(LineString ring, Coordinate p, double fineNearM) {
        List<Coordinate> out = new ArrayList<>();
        Coordinate[] cs = ring.getCoordinates();
        for (int i = 0; i + 1 < cs.length; i++) {
            Coordinate a = cs[i];
            Coordinate b = cs[i + 1];
            double len = a.distance(b);
            double step = fineNearM > 0
                    && segmentDistance(p, new double[] {a.x, a.y, b.x, b.y}) <= fineNearM + NEAR_WALL_M
                    ? NEAR_SAMPLE_STEP_M : SAMPLE_STEP_M;
            out.add(new Coordinate(a));
            // ближайшая точка звена к P (перпендикуляр)
            if (len > 1e-9) {
                double t = ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / (len * len);
                if (t > 0 && t < 1) {
                    out.add(new Coordinate(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t));
                }
            }
            for (double d = step; d < len; d += step) {
                double t = d / len;
                out.add(new Coordinate(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t));
            }
        }
        return out;
    }

    private static Polygon containingPart(Geometry own, Point p) {
        Polygon best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < own.getNumGeometries(); i++) {
            Geometry g = own.getGeometryN(i);
            if (!(g instanceof Polygon)) {
                continue;
            }
            double d = g.distance(p);
            if (d < bestDist) {
                bestDist = d;
                best = (Polygon) g;
            }
        }
        return best;
    }

    /** Нужна ли вообще логика собственного полигона: точка в нём или на его границе. */
    static boolean onOrInside(Geometry own, Coordinate p, GeometryFactory gf) {
        if (own == null || own.isEmpty()) {
            return false;
        }
        Point pp = gf.createPoint(p);
        return own.covers(pp) || own.distance(pp) <= ON_BOUNDARY_M;
    }

    /** Ближайшая точка границы и расстояние до неё (для диагностики). */
    static double distanceToBoundary(Geometry own, Coordinate p, GeometryFactory gf) {
        return DistanceOp.distance(own.getBoundary(), gf.createPoint(p));
    }

    private static final class Raw {
        final Coordinate boundary;
        final double insideM;
        final double ux;
        final double uy;
        /** Расстояние от P до звена границы (стены), через которое проходит заход, м. */
        final double wallDist;

        Raw(Coordinate boundary, double insideM, double ux, double uy, double wallDist) {
            this.boundary = boundary;
            this.insideM = insideM;
            this.ux = ux;
            this.uy = uy;
            this.wallDist = wallDist;
        }
    }
}
