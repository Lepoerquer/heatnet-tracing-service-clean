package ru.heatnet.routing;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;

import ru.heatnet.calc.reference.RulesConfig;
import ru.heatnet.rules.SpatialConstraintEngine;
import ru.heatnet.rules.model.SpecialSection;

/**
 * Основной M3: portaled visibility graph + string-pulling + grid fallback.
 * §2.2: финальный прямой участок к точке ОКС внутри своего полигона.
 */
public final class PortaledVisibilityRouteFinder implements RouteFinder {

    private final SpatialConstraintEngine engine;
    private final List<org.locationtech.jts.geom.Geometry> blockedOutlines;
    private final RulesConfig rules;
    private final int dn;
    private Geometry ownApproachPolygon;

    public PortaledVisibilityRouteFinder(SpatialConstraintEngine engine,
                                         List<org.locationtech.jts.geom.Geometry> blockedOutlines,
                                         RulesConfig rules,
                                         int dn) {
        this.engine = engine;
        this.blockedOutlines = blockedOutlines;
        this.rules = rules;
        this.dn = dn;
    }

    void setOwnApproach(Geometry ownPolygon, Point toUtm) {
        this.ownApproachPolygon = ownPolygon;
    }

    @Override
    public RouteResult findRoute(Point fromUtm, Point toUtm, int dnHint) {
        if (fromUtm == null || toUtm == null) {
            return RouteResult.notFound();
        }
        int effectiveDn = dnHint > 0 ? dnHint : dn;
        Coordinate start = fromUtm.getCoordinate();
        Coordinate end = toUtm.getCoordinate();

        Coordinate searchStart = start;
        Coordinate searchEnd = end;
        LineString prefix = null;

        Coordinate startExit = exitFromBlocked(start, end);
        if (startExit != null && start.distance(startExit) > rules.getGeometryToleranceM()) {
            prefix = RoutingGeometry.line(start, startExit);
            searchStart = startExit;
            if (prefix.getLength() > rules.getRoutingStartExitMaxM()) {
                return RouteResult.notFound();
            }
        }

        // §2.2: единственный участок, освобождённый от отступа к своему полигону, — прямой заход
        // от ворот (ближайшей внешней точки границы) до самой точки ОКС. Все точки-кандидаты
        // на этот заход (полный обход периметра `approachGates()`, а при его неудаче — ровно
        // ближайшая точка `entryOnOwnApproach()`) собираются в ОДИН список `gates` и передаются
        // в построитель графа С keepOut: так граф всегда знает про своё же здание как препятствие
        // для ЛЮБОГО ребра, кроме специально добавленных рёбер «ворота -> цель» (см.
        // VisibilityGraphBuilder.addWeightedEdge). Раньше при пустом approachGates() ворота
        // считались отдельно (`entry`) и граф строился С keepOut=null — то есть вообще без защиты
        // от собственного здания, и «двор» мог насквозь прорезать корпус на пути к этой точке
        // (найдено независимым синтетическим тестом, см. 10-audit-and-fixes-2026-09-24.md §8.1).
        List<Coordinate> gates = approachGates(end);
        if (gates.isEmpty()) {
            Coordinate entry = entryOnOwnApproach(end);
            if (entry != null && end.distance(entry) > rules.getGeometryToleranceM()) {
                gates = java.util.Collections.singletonList(entry);
            }
        }

        RoutingWorkspace workspace = new RoutingWorkspace(
                RoutingGeometry.point(searchStart), RoutingGeometry.point(searchEnd), rules);

        LineString direct = RoutingGeometry.line(searchStart, searchEnd);
        List<Coordinate> path;
        boolean directOk = !engine.isSegmentBlocked(direct, effectiveDn)
                && !crossesOwnInterior(direct);
        if (directOk) {
            path = new ArrayList<>();
            path.add(searchStart);
            path.add(searchEnd);
        } else {
            VisibilityGraph graph = new VisibilityGraphBuilder(engine, blockedOutlines, rules, effectiveDn)
                    .build(searchStart, searchEnd, workspace, ownApproachPolygon, gates);

            path = null;
            if (graph.size() > 0 && !graph.isPartial()) {
                long deadline = System.currentTimeMillis() + Math.max(1L, rules.getRoutingRouteBudgetMs());
                path = new GraphPathSearcher(graph, engine, rules, effectiveDn, deadline).findPath(0, 1);
            }
            if (path == null || path.size() < 2) {
                path = gridFallback(searchStart, searchEnd, workspace, effectiveDn, ownApproachPolygon);
            }
            if (path == null || path.size() < 2) {
                return RouteResult.notFound();
            }
        }

        LineString smoothed = new RouteSmoother(engine, effectiveDn, ownApproachPolygon).smooth(path);
        LineString fixed = new CrossingFixer(engine, effectiveDn).fix(smoothed);
        if (engine.isSegmentBlocked(fixed, effectiveDn)) {
            return RouteResult.notFound();
        }
        // §2.2: единственный законно освобождённый от отступа участок — последний отрезок (ворота
        // -> точка ОКС). Если весь путь ДО этого отрезка тоже задевает свой полигон — что теперь
        // возможно только при сбое построителя графа/грида, а не штатно — отклоняем маршрут, а не
        // тянем трубу сквозь здание молча.
        if (ownApproachPolygon != null && !ownApproachPolygon.isEmpty() && fixed.getNumPoints() > 2) {
            LineString withoutLastLeg = RoutingGeometry.polyline(
                    java.util.Arrays.asList(fixed.getCoordinates()).subList(0, fixed.getNumPoints() - 1));
            try {
                Geometry intrusion = withoutLastLeg.intersection(ownApproachPolygon);
                if (intrusion != null && !intrusion.isEmpty() && intrusion.getLength() > 0.5) {
                    return RouteResult.notFound();
                }
            } catch (RuntimeException ex) {
                return RouteResult.notFound();
            }
        }

        LineString assembled = concat(prefix, fixed, null);
        if (assembled == null) {
            return RouteResult.notFound();
        }
        // §2.1: поворот допускается до 90° включительно. Стыки prefix/middle/suffix (выход из
        // накрывающей зоны и заход к точке ОКС внутри своего полигона) дают разворот до 180° —
        // убираем такие вершины спрямлением, если движок признаёт спрямление свободным.
        assembled = TurnRepair.repair(assembled, engine, effectiveDn, ownApproachPolygon);
        return finalizePath(assembled, effectiveDn);
    }

    private Coordinate exitFromBlocked(Coordinate start, Coordinate goal) {
        Geometry covering = coveringBlocked(start);
        if (covering == null || covering.isEmpty()) {
            return null;
        }
        Coordinate aimed = rayExit(start, goal, covering);
        if (aimed != null && start.distance(aimed) <= rules.getRoutingStartExitMaxM() + 1e-6
                && start.distance(aimed) > rules.getGeometryToleranceM()) {
            return aimed;
        }
        try {
            Coordinate[] pts = DistanceOp.nearestPoints(covering.getBoundary(), RoutingGeometry.point(start));
            if (pts != null && pts.length > 0) {
                return pts[0];
            }
        } catch (RuntimeException ex) {
            return aimed;
        }
        return aimed;
    }

    /** Выход из накрывающей зоны в сторону цели, а не к ближайшей границе с разворотом. */
    private Coordinate rayExit(Coordinate start, Coordinate goal, Geometry covering) {
        if (goal == null) {
            return null;
        }
        double dx = goal.x - start.x;
        double dy = goal.y - start.y;
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) {
            return null;
        }
        double reach = rules.getRoutingStartExitMaxM() + 8.0;
        Coordinate far = new Coordinate(start.x + dx / len * reach, start.y + dy / len * reach);
        try {
            Geometry hit = RoutingGeometry.line(start, far).intersection(covering.getBoundary());
            if (hit == null || hit.isEmpty()) {
                return null;
            }
            Coordinate best = null;
            double bestDist = Double.POSITIVE_INFINITY;
            for (Coordinate c : hit.getCoordinates()) {
                double d = start.distance(c);
                if (d > rules.getGeometryToleranceM() && d < bestDist) {
                    bestDist = d;
                    best = c;
                }
            }
            return best;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * §2.2: точки-кандидаты на «ворота» (внешняя точка границы своего полигона, откуда допускается
     * прямой заход к точке ОКС). Раньше выборка шла по 16 РАВНОМЕРНО расставленным по всему
     * периметру точкам — для большого/сложной формы здания настоящая ближайшая к цели точка
     * стены часто не попадала ни в одну из 16, а единственные проходимые кандидаты оказывались на
     * другом конце здания. Пока (до 24.09) движок не проверял `keepOut` на каждом ребре графа и
     * при спрямлении, эта слабость была не видна — прямой путь просто «срезал» через здание.
     * После исправления keepOut ей нужна точная выборка: плотное окно ±45 м вдоль границы вокруг
     * настоящей ближайшей точки (тот же приём, что уже есть в {@code SteinerPlanner.addNearWallGates}).
     */
    private List<Coordinate> approachGates(Coordinate target) {
        List<Coordinate> gates = new ArrayList<>();
        Polygon poly = coveringOwnPolygon(target);
        if (poly == null) {
            return gates;
        }
        LineString ring = poly.getExteriorRing();
        double ringLen = ring.getLength();
        if (ringLen < 1e-6) {
            return gates;
        }
        LengthIndexedLine indexed = new LengthIndexedLine(ring);
        Coordinate[] near = DistanceOp.nearestPoints(ring, RoutingGeometry.point(target));
        double baseIdx = indexed.indexOf(near[0]);
        double toWall = RoutingGeometry.point(target).distance(poly.getBoundary());
        double maxLegM = toWall + 15.0;
        Geometry padded = poly.buffer(0.35);
        double[] offsets = {0.0, 3.0, -3.0, 6.0, -6.0, 10.0, -10.0, 15.0, -15.0,
                22.0, -22.0, 30.0, -30.0, 45.0, -45.0};
        for (double off : offsets) {
            double idx = wrapIndex(baseIdx + off, ringLen);
            Coordinate boundaryPoint;
            try {
                boundaryPoint = indexed.extractPoint(idx);
            } catch (RuntimeException ex) {
                continue;
            }
            Coordinate outward = RoutingGeometry.outwardFromBoundary(boundaryPoint, poly, 0.45);
            LineString leg = RoutingGeometry.line(outward, target);
            if (leg.getLength() < rules.getGeometryToleranceM() || leg.getLength() > maxLegM) {
                continue;
            }
            try {
                if (!padded.covers(leg)) {
                    continue;
                }
            } catch (RuntimeException ex) {
                continue;
            }
            if (engine.isSegmentBlocked(leg, dn)) {
                continue;
            }
            gates.add(outward);
        }
        // Запасной вариант — старая равномерная выборка по всему периметру: полезна, если истинно
        // ближайшая стена (окно ±45 м) целиком заблокирована другим ограничением, а более дальняя,
        // но свободная сторона того же здания — ещё нет.
        if (gates.isEmpty()) {
            Coordinate[] corners = ring.getCoordinates();
            int usable = Math.max(1, corners.length - 1);
            int step = Math.max(1, usable / 16);
            for (int i = 0; i < usable; i += step) {
                Coordinate outward = RoutingGeometry.outwardFromBoundary(corners[i], poly, 0.45);
                LineString leg = RoutingGeometry.line(outward, target);
                if (leg.getLength() < rules.getGeometryToleranceM() || leg.getLength() > toWall + 30.0) {
                    continue;
                }
                try {
                    if (!padded.covers(leg) || engine.isSegmentBlocked(leg, dn)) {
                        continue;
                    }
                } catch (RuntimeException ex) {
                    continue;
                }
                gates.add(outward);
            }
        }
        return gates;
    }

    private static double wrapIndex(double idx, double len) {
        if (len <= 0) {
            return 0;
        }
        double v = idx % len;
        return v < 0 ? v + len : v;
    }

    private org.locationtech.jts.geom.Polygon coveringOwnPolygon(Coordinate target) {
        if (ownApproachPolygon == null || ownApproachPolygon.isEmpty() || target == null) {
            return null;
        }
        Point point = RoutingGeometry.point(target);
        if (ownApproachPolygon instanceof org.locationtech.jts.geom.Polygon) {
            org.locationtech.jts.geom.Polygon polygon = (org.locationtech.jts.geom.Polygon) ownApproachPolygon;
            return coversTarget(polygon, point) ? polygon : null;
        }
        for (int i = 0; i < ownApproachPolygon.getNumGeometries(); i++) {
            Geometry part = ownApproachPolygon.getGeometryN(i);
            if (part instanceof org.locationtech.jts.geom.Polygon
                    && coversTarget((org.locationtech.jts.geom.Polygon) part, point)) {
                return (org.locationtech.jts.geom.Polygon) part;
            }
        }
        return null;
    }

    private static boolean coversTarget(org.locationtech.jts.geom.Polygon polygon, Point point) {
        try {
            return polygon.covers(point) || polygon.contains(point);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean crossesOwnInterior(LineString segment) {
        if (ownApproachPolygon == null || ownApproachPolygon.isEmpty() || segment == null) {
            return false;
        }
        try {
            Geometry inter = segment.intersection(ownApproachPolygon);
            if (inter == null || inter.isEmpty() || inter.getLength() <= rules.getGeometryToleranceM()) {
                return false;
            }
            org.locationtech.jts.geom.Polygon poly = coveringOwnPolygon(
                    segment.getCoordinateN(segment.getNumPoints() - 1));
            return poly == null || segment.getNumPoints() != 2 || !poly.buffer(0.6).covers(segment);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private Geometry coveringBlocked(Coordinate start) {
        Point p = RoutingGeometry.point(start);
        Geometry union = null;
        for (Geometry outline : blockedOutlines) {
            if (outline == null || outline.isEmpty()) {
                continue;
            }
            try {
                if (outline.covers(p) || outline.contains(p)) {
                    union = union == null ? outline : union.union(outline);
                }
            } catch (RuntimeException ex) {
                // ignore invalid geometry
            }
        }
        return union;
    }

    private Coordinate entryOnOwnApproach(Coordinate target) {
        if (ownApproachPolygon == null || ownApproachPolygon.isEmpty()) {
            return null;
        }
        try {
            Point targetPoint = RoutingGeometry.point(target);
            if (!ownApproachPolygon.covers(targetPoint) && !ownApproachPolygon.contains(targetPoint)) {
                return null;
            }
            Coordinate[] pts = DistanceOp.nearestPoints(ownApproachPolygon.getBoundary(), targetPoint);
            if (pts != null && pts.length > 0) {
                return pts[0];
            }
        } catch (RuntimeException ex) {
            return null;
        }
        return null;
    }

    private LineString concat(LineString prefix, LineString middle, LineString suffix) {
        List<Coordinate> coords = new ArrayList<>();
        appendLine(coords, prefix);
        appendLine(coords, middle);
        appendLine(coords, suffix);
        if (coords.size() < 2) {
            return null;
        }
        return RoutingGeometry.polyline(dedupe(coords));
    }

    private void appendLine(List<Coordinate> coords, LineString line) {
        if (line == null || line.getNumPoints() < 2) {
            return;
        }
        for (int i = 0; i < line.getNumPoints(); i++) {
            coords.add(line.getCoordinateN(i));
        }
    }

    private List<Coordinate> dedupe(List<Coordinate> raw) {
        List<Coordinate> out = new ArrayList<>();
        for (Coordinate c : raw) {
            if (out.isEmpty() || out.get(out.size() - 1).distance(c) > rules.getGeometryToleranceM()) {
                out.add(c);
            }
        }
        return out;
    }

    private List<Coordinate> gridFallback(Coordinate start, Coordinate end, RoutingWorkspace workspace,
                                          int effectiveDn, Geometry keepOut) {
        return new GridRouteFinder(engine, blockedOutlines, rules, effectiveDn)
                .findPath(start, end, workspace, keepOut);
    }

    private RouteResult finalizePath(LineString path, int effectiveDn) {
        List<SpecialSection> sections = new ArrayList<>();
        Coordinate[] coords = path.getCoordinates();
        for (int i = 0; i < coords.length - 1; i++) {
            LineString seg = RoutingGeometry.line(coords[i], coords[i + 1]);
            sections.addAll(engine.extractSpecialSections(seg, effectiveDn));
        }
        return RouteResult.found(path, sections);
    }
}
