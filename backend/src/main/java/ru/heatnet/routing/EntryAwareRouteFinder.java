package ru.heatnet.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.heatnet.calc.reference.RulesConfig;
import ru.heatnet.rules.SpatialConstraintBundle;
import ru.heatnet.rules.SpatialConstraintEngine;
import ru.heatnet.rules.model.SpecialSection;

/**
 * AUDIT-24.09 (Claude). Маршрут «точка → ОКС» с финальным прямым заходом в свой полигон (§2.2
 * приложения, Разъяснение №3) — тем же способом, что и основной планировщик (дерево Штейнера):
 *
 * <ol>
 *   <li>кандидаты захода {@link OwnEntryCandidates}: точки Q на луче «P → ближайшая граница → наружу»
 *       за пределами зоны отступа своего полигона, по уровням (0 — ближайшая граница);</li>
 *   <li>путь от начала до Q ищется по графу видимости {@link SharedVisibilityRouter} с ПОЛНЫМ набором
 *       ограничений (свой полигон и отступ от него действуют до точки Q), поворот в Q ≤ 90°;</li>
 *   <li>из Q — один прямой отрезок до P, проверенный набором ограничений без своего полигона.</li>
 * </ol>
 *
 * <p>Зачем: прежний маршрутизатор ввода ({@link PortaledVisibilityRouteFinder}) строит граф
 * видимости «всех со всеми» с отсечкой 12 с и сеточный запасной вариант, который не умеет входить
 * в полигон глубже одной ячейки. На конкурсном наборе он не находил маршрут ни к одному ОКС
 * (тест {@code RoutingContestDatasetTest} падал и до аудита), а он используется как запасной путь
 * планировщика (перетрассировка после M5, отдельный ввод, совместный планировщик). Кроме того,
 * трасса до «ворот» шла вплотную к своей стене — без отступа 5–9 м, который по §2.2 снимается
 * только с финального прямого участка.</p>
 *
 * <p>Если кандидатов захода нет или граф не собрался, используется прежний маршрутизатор.</p>
 */
final class EntryAwareRouteFinder implements RouteFinder {

    private static final Logger log = LoggerFactory.getLogger(EntryAwareRouteFinder.class);
    /** Запас области графа вокруг начала и цели (дополнительно к routing.workspace_margin_m). */
    private static final double AREA_MARGIN_M = 100.0;
    private static final double INSIDE_TOLERANCE_M = 0.3;

    private final SpatialConstraintBundle full;
    private final SpatialConstraintEngine approach;
    private final Geometry own;
    private final double clearanceM;
    private final RulesConfig rules;
    private final int dn;
    private final RouteFinder fallback;
    private final GeometryFactory gf = RoutingGeometry.factory();

    EntryAwareRouteFinder(SpatialConstraintBundle full, SpatialConstraintEngine approach, Geometry own,
                          double clearanceM, RulesConfig rules, int dn, RouteFinder fallback) {
        this.full = full;
        this.approach = approach;
        this.own = own;
        this.clearanceM = clearanceM;
        this.rules = rules;
        this.dn = dn;
        this.fallback = fallback;
    }

    @Override
    public RouteResult findRoute(Point fromUtm, Point toUtm, int dnHint) {
        if (fromUtm == null || toUtm == null) {
            return RouteResult.notFound();
        }
        if (own == null || own.isEmpty()) {
            return fallback == null ? RouteResult.notFound() : fallback.findRoute(fromUtm, toUtm, dnHint);
        }
        Coordinate p = toUtm.getCoordinate();
        List<OwnEntryCandidates.Entry> entries;
        RouteResult result = null;
        try {
            entries = OwnEntryCandidates.compute(p, own, clearanceM, dn, full.getEngine(), approach, gf);
            result = route(fromUtm.getCoordinate(), p, entries);
        } catch (RuntimeException ex) {
            log.warn("Маршрут с заходом §2.2 не построен: {}", ex.toString());
            entries = Collections.emptyList();
        }
        if (result != null && result.isFound()) {
            return result;
        }
        if (fallback == null) {
            return RouteResult.notFound();
        }
        // Прежний маршрутизатор — только как запасной, и его результат принимается лишь при соблюдении тех же
        // правил: повороты ≤ 90°, до финального участка — отступы полного набора (в т.ч. от своего здания),
        // финальный участок — прямой заход через одну из допустимых стен. Иначе «не найден»: по Разъяснению
        // №15 неподключение допустимо, а маршрут с нарушением правил приложения — нет.
        RouteResult legacy = fallback.findRoute(fromUtm, toUtm, dnHint);
        if (legacy == null || !legacy.isFound()) {
            return RouteResult.notFound();
        }
        String why;
        try {
            why = violation(legacy.getPathUtm(), p, entries);
        } catch (RuntimeException ex) {
            why = "ошибка проверки: " + ex;
        }
        if (why != null) {
            log.info("Запасной маршрут к ({}, {}) отклонён: {}", Math.round(p.x), Math.round(p.y), why);
            return RouteResult.notFound();
        }
        return legacy;
    }

    private RouteResult route(Coordinate start, Coordinate p, List<OwnEntryCandidates.Entry> entries) {
        if (entries.isEmpty()) {
            return null;
        }
        SpatialConstraintEngine engine = full.getEngine();
        Envelope area = new Envelope(start, p);
        area.expandBy(AREA_MARGIN_M);
        SharedVisibilityRouter router = new SharedVisibilityRouter(engine, full.getBlockedOutlines(), rules, dn);
        router.setFocus(Arrays.asList(start, p));
        if (!router.build(area)) {
            return null;
        }
        List<SharedVisibilityRouter.Target> specs = new ArrayList<>(entries.size());
        for (OwnEntryCandidates.Entry e : entries) {
            specs.add(new SharedVisibilityRouter.Target(e.q, p));
        }
        List<SharedVisibilityRouter.RouteHit> hits = router.cheapestTo(
                Collections.singletonList(new SharedVisibilityRouter.Seed(start, 0.0, 0)), specs);

        List<Coordinate> best = null;
        int bestLevel = Integer.MAX_VALUE;
        double bestCost = Double.POSITIVE_INFINITY;
        for (int i = 0; i < hits.size(); i++) {
            SharedVisibilityRouter.RouteHit hit = hits.get(i);
            if (hit == null || hit.getPath() == null || hit.getPath().size() < 2) {
                continue;
            }
            OwnEntryCandidates.Entry entry = entries.get(i);
            if (entry.level > bestLevel) {
                continue;
            }
            List<Coordinate> path = accept(hit.getPath(), entry, p);
            if (path == null) {
                continue;
            }
            double cost = hit.getCost() + entry.q.distance(p);
            if (entry.level < bestLevel || cost < bestCost - 1e-9) {
                best = path;
                bestLevel = entry.level;
                bestCost = cost;
            }
        }
        if (best == null) {
            return null;
        }
        return finalizePath(best);
    }

    /** Проверки те же, что у дерева Штейнера: поворот ≤ 90°, до Q — вне своего полигона, Q→P свободен. */
    private List<Coordinate> accept(List<Coordinate> exteriorPath, OwnEntryCandidates.Entry entry, Coordinate p) {
        LineString exterior = RoutingGeometry.polyline(exteriorPath);
        LineString repaired = TurnRepair.repair(exterior, full.getEngine(), dn, own);
        List<Coordinate> coords = new ArrayList<>(Arrays.asList(repaired.getCoordinates()));
        if (coords.size() < 2 || !turnsOk(coords)) {
            return null;
        }
        if (insideLength(repaired) > 0.05) {
            return null;
        }
        Coordinate q = coords.get(coords.size() - 1);
        Coordinate prev = coords.get(coords.size() - 2);
        if (q.distance(p) > 0.05) {
            if (TurnRepair.deviationDeg(prev, q, p) > TurnRepair.MAX_TURN_DEG + 1e-6) {
                return null;
            }
            LineString leg = RoutingGeometry.line(q, p);
            if (approach.isSegmentBlocked(leg, dn)) {
                return null;
            }
            if (insideLength(leg) > entry.insideM + INSIDE_TOLERANCE_M) {
                return null;
            }
            coords.add(new Coordinate(p));
        }
        // Трасса до Q не должна задевать ограничения полного набора; исключение — первое звено, если это
        // выход из зоны, в которой лежит сама точка начала (как у прежнего маршрутизатора).
        for (int i = 0; i < coords.size() - 2; i++) {
            if (full.getEngine().isSegmentBlocked(RoutingGeometry.line(coords.get(i), coords.get(i + 1)), dn)
                    && (i > 0 || !startEnclosed(coords.get(0), coords.get(1)))) {
                return null;
            }
        }
        return coords;
    }

    /** @return причина несоответствия правилам §2.1/§2.2 или null, если маршрут допустим */
    String violation(LineString path, Coordinate p, List<OwnEntryCandidates.Entry> entries) {
        if (path == null || path.getNumPoints() < 2) {
            return "пустой путь";
        }
        if (entries.isEmpty()) {
            return "нет допустимого прямого захода в свой полигон";
        }
        List<Coordinate> coords = Arrays.asList(path.getCoordinates());
        int n = coords.size();
        if (coords.get(n - 1).distance(p) > 0.05) {
            return "путь не заканчивается в точке ОКС";
        }
        if (!turnsOk(coords)) {
            return "поворот больше 90°";
        }
        SpatialConstraintEngine engine = full.getEngine();
        for (int i = 0; i < n - 2; i++) {
            LineString seg = RoutingGeometry.line(coords.get(i), coords.get(i + 1));
            if (!engine.isSegmentBlocked(seg, dn)) {
                continue;
            }
            // Первое звено может быть только выходом из зоны, в которой лежит сама точка начала.
            if (i > 0 || !startEnclosed(coords.get(0), coords.get(1))) {
                return "участок " + (i + 1) + " нарушает отступы до финального захода";
            }
        }
        if (n > 2 && insideLength(RoutingGeometry.polyline(coords.subList(0, n - 1))) > 0.05) {
            return "трасса заходит в свой полигон до финального участка";
        }
        Coordinate legStart = coords.get(n - 2);
        try {
            if (own.contains(RoutingGeometry.point(legStart))
                    && own.getBoundary().distance(RoutingGeometry.point(legStart)) > 0.05) {
                return "финальный участок начинается внутри своего полигона, а не от его границы";
            }
        } catch (RuntimeException ex) {
            return "ошибка геометрии своего полигона";
        }
        LineString leg = RoutingGeometry.line(legStart, p);
        if (approach.isSegmentBlocked(leg, dn)) {
            return "финальный участок нарушает ограничения";
        }
        double maxInside = 0.0;
        for (OwnEntryCandidates.Entry e : entries) {
            maxInside = Math.max(maxInside, e.insideM);
        }
        if (insideLength(leg) > maxInside + INSIDE_TOLERANCE_M) {
            return "финальный заход не через допустимую стену";
        }
        return null;
    }

    private boolean startEnclosed(Coordinate start, Coordinate next) {
        double len = start.distance(next);
        if (len < 1e-9) {
            return false;
        }
        double k = Math.min(0.05, len) / len;
        Coordinate probe = new Coordinate(start.x + (next.x - start.x) * k, start.y + (next.y - start.y) * k);
        return full.getEngine().isSegmentBlocked(RoutingGeometry.line(start, probe), dn);
    }

    private static boolean turnsOk(List<Coordinate> coords) {
        for (int i = 1; i + 1 < coords.size(); i++) {
            if (TurnRepair.deviationDeg(coords.get(i - 1), coords.get(i), coords.get(i + 1))
                    > TurnRepair.MAX_TURN_DEG + 1e-6) {
                return false;
            }
        }
        return true;
    }

    private double insideLength(LineString line) {
        try {
            Geometry inter = line.intersection(own);
            return inter == null || inter.isEmpty() ? 0.0 : inter.getLength();
        } catch (RuntimeException ex) {
            return Double.POSITIVE_INFINITY;
        }
    }

    private RouteResult finalizePath(List<Coordinate> coords) {
        List<SpecialSection> sections = new ArrayList<>();
        for (int i = 0; i < coords.size() - 1; i++) {
            LineString seg = RoutingGeometry.line(coords.get(i), coords.get(i + 1));
            boolean last = i == coords.size() - 2;
            sections.addAll((last ? approach : full.getEngine()).extractSpecialSections(seg, dn));
        }
        return RouteResult.found(RoutingGeometry.polyline(coords), sections);
    }
}
