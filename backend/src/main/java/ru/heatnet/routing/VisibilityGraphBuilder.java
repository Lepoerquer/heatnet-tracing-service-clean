package ru.heatnet.routing;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;

import ru.heatnet.calc.reference.RulesConfig;
import ru.heatnet.rules.SpatialConstraintEngine;
import ru.heatnet.rules.model.PortalGate;

/**
 * Portaled Visibility Graph: углы буферов, концы порталов, start/end.
 */
final class VisibilityGraphBuilder {

    private final SpatialConstraintEngine engine;
    private final List<Geometry> blockedOutlines;
    private final RulesConfig rules;
    private final int dn;
    private Geometry keepOut;

    VisibilityGraphBuilder(SpatialConstraintEngine engine, List<Geometry> blockedOutlines,
                           RulesConfig rules, int dn) {
        this.engine = engine;
        this.blockedOutlines = blockedOutlines;
        this.rules = rules;
        this.dn = dn;
    }

    VisibilityGraph build(Coordinate start, Coordinate end, RoutingWorkspace workspace) {
        return build(start, end, workspace, null, java.util.Collections.<Coordinate>emptyList());
    }

    /**
     * @param keepOut полигон своего ОКС: сквозь него нельзя, кроме финального прямого захода §2.2
     * @param gates   точки на внешней стороне границы, из которых прямой заход к {@code end} законен
     */
    VisibilityGraph build(Coordinate start, Coordinate end, RoutingWorkspace workspace,
                          Geometry keepOut, List<Coordinate> gates) {
        this.keepOut = keepOut;
        try {
            return buildInternal(start, end, workspace, gates);
        } finally {
            this.keepOut = null;
        }
    }

    private VisibilityGraph buildInternal(Coordinate start, Coordinate end, RoutingWorkspace workspace,
                                         List<Coordinate> gates) {
        List<Coordinate> extra = new ArrayList<>();
        collectObstacleVertices(extra, workspace);
        collectPortalVertices(extra, workspace);
        extra = RoutingGeometry.deduplicate(extra, rules.getGeometryToleranceM());

        List<Coordinate> coords = new ArrayList<>();
        coords.add(RoutingGeometry.copy(start));
        coords.add(RoutingGeometry.copy(end));
        List<Integer> gateIds = new ArrayList<>();
        if (gates != null) {
            for (Coordinate gate : gates) {
                if (gate == null || samePoint(gate, start) || samePoint(gate, end)) {
                    continue;
                }
                gateIds.add(Integer.valueOf(coords.size()));
                coords.add(RoutingGeometry.copy(gate));
            }
        }
        for (Coordinate c : extra) {
            if (!samePoint(c, start) && !samePoint(c, end)) {
                coords.add(c);
            }
        }

        if (coords.size() > rules.getRoutingGridFallbackMinVertices()) {
            return VisibilityGraph.empty();
        }

        VisibilityGraph.Builder builder = VisibilityGraph.builder();
        final int startId = 0;
        final int endId = 1;
        for (int i = 0; i < coords.size(); i++) {
            Coordinate c = coords.get(i);
            VisibilityVertex.Kind kind = VisibilityVertex.Kind.OBSTACLE;
            if (i == startId) {
                kind = VisibilityVertex.Kind.START;
            } else if (i == endId) {
                kind = VisibilityVertex.Kind.END;
            }
            builder.addVertex(new VisibilityVertex(i, c, kind));
        }

        STRtree tree = new STRtree();
        for (int i = 0; i < coords.size(); i++) {
            Coordinate c = coords.get(i);
            tree.insert(new Envelope(c), Integer.valueOf(i));
        }
        tree.build();

        double direct = start.distance(end);
        double maxEdge = Math.min(workspace.envelope().getDiameter() * 1.5,
                Math.max(250.0, direct * 2.5 + rules.getRoutingWorkspaceMarginM()));
        long deadline = System.currentTimeMillis() + Math.max(1L, rules.getRoutingVgDeadlineMs());

        for (int i = 0; i < coords.size(); i++) {
            if (System.currentTimeMillis() > deadline) {
                builder.markPartial();
                break;
            }
            Coordinate from = coords.get(i);
            Envelope search = new Envelope(from);
            search.expandBy(maxEdge);
            @SuppressWarnings("unchecked")
            List<Integer> neighbours = tree.query(search);
            for (Integer jObj : neighbours) {
                int j = jObj.intValue();
                if (j <= i) {
                    continue;
                }
                Coordinate to = coords.get(j);
                if (from.distance(to) > maxEdge) {
                    continue;
                }
                if (!isVisible(from, to)) {
                    continue;
                }
                LineString segment = RoutingGeometry.line(from, to);
                // AUDIT-13 (Claude, 25.09): Kспец — только на спецучастке звена (как в смете M6), см. SpecialCost.
                double base = SpecialCost.weightedLength(segment, engine.extractSpecialSections(segment, dn));
                builder.addEdge(i, j, base);
                builder.addEdge(j, i, base);
            }
        }
        // §2.2: прямой заход внутрь своего полигона не проходит обычную проверку keep-out.
        for (Integer gateId : gateIds) {
            addWeightedEdge(builder, coords, gateId.intValue(), endId);
        }

        return builder.build();
    }

    private void collectObstacleVertices(List<Coordinate> target, RoutingWorkspace workspace) {
        double outward = rules.getRoutingVertexOutwardM();
        for (Geometry geometry : blockedOutlines) {
            if (!workspace.intersects(geometry)) {
                continue;
            }
            if (geometry instanceof Polygon) {
                appendRing(target, (Polygon) geometry, outward);
            } else {
                for (int i = 0; i < geometry.getNumGeometries(); i++) {
                    if (geometry.getGeometryN(i) instanceof Polygon) {
                        appendRing(target, (Polygon) geometry.getGeometryN(i), outward);
                    }
                }
            }
        }
    }

    private void appendRing(List<Coordinate> target, Polygon polygon, double outwardM) {
        double simplifyM = rules.getRoutingVertexSimplifyM();
        Polygon working = polygon;
        if (simplifyM > 0 && simplifyM < outwardM) {
            Geometry simplified = DouglasPeuckerSimplifier.simplify(polygon, simplifyM);
            if (simplified instanceof Polygon && !simplified.isEmpty()) {
                working = (Polygon) simplified;
            }
        }
        Coordinate[] ring = working.getExteriorRing().getCoordinates();
        int n = ring.length - 1;
        for (int i = 0; i < n; i++) {
            // AUDIT-13 (Claude, 25.09): сдвиг по биссектрисе угла наружу, а не «от центроида» (см. RoutingGeometry).
            Coordinate pushed = n >= 3
                    ? RoutingGeometry.outwardVertex(ring[(i - 1 + n) % n], ring[i], ring[(i + 1) % n], working, outwardM)
                    : RoutingGeometry.outwardVertex(ring[i], working, outwardM);
            target.add(pushed);
        }
    }

    private void collectPortalVertices(List<Coordinate> target, RoutingWorkspace workspace) {
        for (PortalGate gate : engine.portalGates()) {
            LineString line = gate.getGateLine();
            if (!workspace.intersects(line)) {
                continue;
            }
            Coordinate[] coords = line.getCoordinates();
            if (coords.length >= 2) {
                target.add(coords[0]);
                target.add(coords[coords.length - 1]);
            }
        }
    }

    private void addWeightedEdge(VisibilityGraph.Builder builder, List<Coordinate> coords, int from, int to) {
        Coordinate a = coords.get(from);
        Coordinate b = coords.get(to);
        LineString segment = RoutingGeometry.line(a, b);
        if (segment.getLength() < rules.getGeometryToleranceM()) {
            return;
        }
        if (engine.isSegmentBlocked(segment, dn)) {
            return;
        }
        // AUDIT-13 (Claude, 25.09): Kспец — только на спецучастке звена (как в смете M6), см. SpecialCost.
        double base = SpecialCost.weightedLength(segment, engine.extractSpecialSections(segment, dn));
        builder.addEdge(from, to, base);
        builder.addEdge(to, from, base);
    }

    private boolean isVisible(Coordinate from, Coordinate to) {
        if (from.distance(to) < rules.getGeometryToleranceM()) {
            return false;
        }
        LineString segment = RoutingGeometry.line(from, to);
        if (crossesKeepOut(segment)) {
            return false;
        }
        return !engine.isSegmentBlocked(segment, dn);
    }

    /** AUDIT-13: подготовленный свой полигон — быстрый отказ для рёбер, вообще не задевающих здание. */
    private org.locationtech.jts.geom.prep.PreparedGeometry keepOutPrepared;

    private boolean crossesKeepOut(LineString segment) {
        if (keepOut == null || keepOut.isEmpty()) {
            return false;
        }
        try {
            // AUDIT-13 (Claude, 25.09): оверлей intersection — только для ребра, задевающего полигон (пустое пересечение
            // ⇔ нет intersects); раньше он считался для каждой пары вершин графа (~50 % времени прежнего маршрутизатора).
            if (!segment.getEnvelopeInternal().intersects(keepOut.getEnvelopeInternal())) {
                return false;
            }
            if (keepOutPrepared == null) {
                keepOutPrepared = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(keepOut);
            }
            if (!keepOutPrepared.intersects(segment)) {
                return false;
            }
            Geometry inter = segment.intersection(keepOut);
            return inter != null && !inter.isEmpty() && inter.getLength() > rules.getGeometryToleranceM();
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private boolean samePoint(Coordinate a, Coordinate b) {
        return a.distance(b) <= rules.getGeometryToleranceM();
    }
}
