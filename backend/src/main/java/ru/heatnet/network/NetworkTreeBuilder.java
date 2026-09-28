package ru.heatnet.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.NodeKind;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.geo.GeoUtils;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.routing.RouteResult;

/**
 * Сборка {@link NewNetworkTree} из маршрута M3: узлы врезки, technical_node, ОКС, камера при врезке в трубу.
 * Совместное дерево — иерархическая кластеризация по общему префиксу и каскад камер ≤4 примыканий.
 */
public final class NetworkTreeBuilder {

    private final AtomicInteger segSeq = new AtomicInteger();
    private final AtomicInteger nodeSeq = new AtomicInteger();
    private final AtomicInteger cascadeSeq = new AtomicInteger();

    /** QA-FIX P6 (H-2): шаг каскада камер вдоль маршрута, м (было 1 см вбок). */
    private static final double CASCADE_STEP_M = 2.0;

    private final GeometryFactory gf;
    private final RouteSegmentSplitter splitter;
    private final double toleranceM;
    private final int maxSegmentsPerChamber;
    private final double clusterPrefixM;

    public NetworkTreeBuilder(GeometryFactory gf, double toleranceM) {
        this(gf, toleranceM, 4, 20.0);
    }

    public NetworkTreeBuilder(GeometryFactory gf, double toleranceM,
                              int maxSegmentsPerChamber, double clusterPrefixM) {
        this.gf = gf;
        this.splitter = new RouteSegmentSplitter(gf, toleranceM);
        this.toleranceM = toleranceM;
        this.maxSegmentsPerChamber = Math.max(2, maxSegmentsPerChamber);
        this.clusterPrefixM = clusterPrefixM > 0 ? clusterPrefixM : 20.0;
    }

    public void resetCounters() {
        segSeq.set(0);
        nodeSeq.set(0);
        cascadeSeq.set(0);
    }

    /**
     * Одно дерево: врезка → один ОКС по готовому маршруту.
     */
    public BuiltNetworkTree buildSingle(TieInPoint tieIn, RouteResult route, OksConnectionPoint oks) {
        if (!route.isFound()) {
            throw new NetworkException("Маршрут к ОКС " + oks.getId() + " не найден");
        }
        List<NewNode> nodes = new ArrayList<>();
        List<NewSegment> segments = new ArrayList<>();
        NetworkTreeLayout layout = new NetworkTreeLayout();

        String tieNodeId = tieIn.getId();
        nodes.add(NewNode.tieIn(tieNodeId));

        LineString path = route.getPathUtm();
        layout.putNode(tieNodeId, path.getCoordinateN(0));

        String prevNodeId = tieNodeId;
        List<RouteSegmentSplitter.RoutePiece> pieces = splitter.split(route);
        if (pieces.isEmpty()) {
            throw new NetworkException("Пустой маршрут к ОКС " + oks.getId());
        }

        for (int i = 0; i < pieces.size(); i++) {
            RouteSegmentSplitter.RoutePiece piece = pieces.get(i);
            boolean needTechnical = piece.getLayingMethod() == ru.heatnet.calc.model.LayingMethod.SPECIAL
                    || (i > 0 && pieces.get(i - 1).getLayingMethod() != piece.getLayingMethod());
            String nextNodeId;
            Coordinate endCoord = piece.getGeometry().getCoordinateN(piece.getGeometry().getNumPoints() - 1);
            if (i == pieces.size() - 1) {
                nextNodeId = oksNodeId(oks.getId());
                if (!containsNode(nodes, nextNodeId)) {
                    nodes.add(NewNode.oks(nextNodeId, oks.getId(), oks.getFlowTph()));
                    layout.putNode(nextNodeId, endCoord);
                }
            } else if (needTechnical) {
                nextNodeId = "tn_" + nodeSeq.incrementAndGet();
                nodes.add(NewNode.technical(nextNodeId));
                layout.putNode(nextNodeId, endCoord);
            } else {
                nextNodeId = "jn_" + nodeSeq.incrementAndGet();
                nodes.add(NewNode.technical(nextNodeId));
                layout.putNode(nextNodeId, endCoord);
            }
            NewSegment seg = toSegment(prevNodeId, nextNodeId, piece);
            segments.add(seg);
            layout.putSegment(seg.getId(), piece.getGeometry());
            prevNodeId = nextNodeId;
        }

        return new BuiltNetworkTree(new NewNetworkTree(tieIn, nodes, segments), layout);
    }

    /**
     * Объединяет несколько маршрутов от одной врезки в разветвлённое дерево.
     */
    public BuiltNetworkTree buildMerged(TieInPoint tieIn, List<OksRoute> oksRoutes) {
        if (oksRoutes == null || oksRoutes.isEmpty()) {
            throw new NetworkException("Нет маршрутов для объединения");
        }
        if (oksRoutes.size() == 1) {
            OksRoute single = oksRoutes.get(0);
            return buildSingle(tieIn, single.getRoute(), single.getOks());
        }

        List<NewNode> nodes = new ArrayList<>();
        List<NewSegment> segments = new ArrayList<>();
        NetworkTreeLayout layout = new NetworkTreeLayout();

        String tieNodeId = tieIn.getId();
        nodes.add(NewNode.tieIn(tieNodeId));

        LineString firstPath = oksRoutes.get(0).getRoute().getPathUtm();
        layout.putNode(tieNodeId, firstPath.getCoordinateN(0));

        double hubAt = Math.min(firstPath.getLength(), Math.max(0.05, toleranceM * 2));
        String hubId = placeHubChamber(tieNodeId, 0.0, oksRoutes.get(0), nodes, segments, layout);
        attachGroup(tieIn, hubId, hubAt, oksRoutes, nodes, segments, layout);

        return new BuiltNetworkTree(new NewNetworkTree(tieIn, nodes, segments), layout);
    }

    private void attachGroup(TieInPoint tieIn, String fromNodeId, double fromM, List<OksRoute> group,
                             List<NewNode> nodes, List<NewSegment> segments, NetworkTreeLayout layout) {
        if (group == null || group.isEmpty()) {
            return;
        }
        if (group.size() == 1) {
            appendBranch(fromNodeId, fromM, group.get(0), nodes, segments, layout);
            return;
        }

        LineString ref = group.get(0).getRoute().getPathUtm();
        double shared = sharedPrefixFrom(group, fromM);
        double minTrunk = Math.max(toleranceM * 2, 1.0);

        if (shared > fromM + minTrunk) {
            String chamberId = "nch_branch_" + nodeSeq.incrementAndGet();
            nodes.add(NewNode.chamber(chamberId));
            Coordinate branchCoord = RouteGeometryUtils.pointAtDistance(ref, shared);
            layout.putNode(chamberId, branchCoord);
            LineString trunkLine = snapStart(RouteGeometryUtils.extractSubLine(gf, ref, fromM, shared, toleranceM),
                    layout.nodeCoordinate(fromNodeId));
            NewSegment trunk = segmentFromLine(fromNodeId, chamberId, trunkLine);
            segments.add(trunk);
            layout.putSegment(trunk.getId(), trunkLine);
            List<List<OksRoute>> clusters = clusterByPrefix(group, shared);
            attachClusters(tieIn, chamberId, shared, clusters, nodes, segments, layout);
            return;
        }

        List<List<OksRoute>> clusters = clusterByPrefix(group, fromM);
        if (clusters.size() == 1 && clusters.get(0).size() == group.size()) {
            List<List<OksRoute>> singles = new ArrayList<>();
            for (OksRoute r : group) {
                singles.add(Collections.singletonList(r));
            }
            clusters = singles;
        }
        String hub = fromNodeId;
            if (!isChamber(hub, nodes) && needsChamberForBranch(fromNodeId, nodes)) {
            hub = placeHubChamber(fromNodeId, fromM, group.get(0), nodes, segments, layout);
        }
        attachClusters(tieIn, hub, fromM, clusters, nodes, segments, layout);
    }

    private boolean needsChamberForBranch(String fromNodeId, List<NewNode> nodes) {
        if (isChamber(fromNodeId, nodes)) {
            return false;
        }
        return true;
    }

    private String placeHubChamber(String fromNodeId, double fromM, OksRoute sample,
                                   List<NewNode> nodes, List<NewSegment> segments, NetworkTreeLayout layout) {
        LineString path = sample.getRoute().getPathUtm();
        double at = Math.min(path.getLength(), fromM + Math.max(0.05, toleranceM * 2));
        if (at <= fromM + 1e-9) {
            at = Math.min(path.getLength(), fromM + 0.05);
        }
        String chamberId = "nch_branch_" + nodeSeq.incrementAndGet();
        nodes.add(NewNode.chamber(chamberId));
        Coordinate coord = RouteGeometryUtils.pointAtDistance(path, at);
        layout.putNode(chamberId, coord);
        LineString trunk = snapStart(RouteGeometryUtils.extractSubLine(gf, path, fromM, at, toleranceM),
                layout.nodeCoordinate(fromNodeId));
        NewSegment seg = segmentFromLine(fromNodeId, chamberId, trunk);
        segments.add(seg);
        layout.putSegment(seg.getId(), trunk);
        return chamberId;
    }

    private void attachClusters(TieInPoint tieIn, String hubId, double atM, List<List<OksRoute>> clusters,
                                List<NewNode> nodes, List<NewSegment> segments, NetworkTreeLayout layout) {
        int idx = 0;
        String current = hubId;
        double at = atM;
        while (idx < clusters.size()) {
            int cap = remainingCapacity(current, nodes, segments);
            int leftover = clusters.size() - idx;
            if (leftover <= cap) {
                while (idx < clusters.size()) {
                    attachGroup(tieIn, current, at, clusters.get(idx++), nodes, segments, layout);
                }
                return;
            }
            int take = Math.max(0, cap - 1);
            for (int t = 0; t < take; t++) {
                attachGroup(tieIn, current, at, clusters.get(idx++), nodes, segments, layout);
            }
            if (idx >= clusters.size()) {
                return;
            }
            // QA-FIX P6 (H-2): следующая камера — на маршруте на CASCADE_STEP_M дальше, а не в 1 см вбок
            // (две камеры в 1 см — физически одна с > 4 примыканиями, то есть обход §2.1)
            LineString sample = clusters.get(idx).get(0).getRoute().getPathUtm();
            double next = Math.min(sample.getLength() - toleranceM, at + CASCADE_STEP_M);
            if (next <= at + toleranceM) {
                next = Math.min(sample.getLength(), at + Math.max(toleranceM, 0.05));
            }
            current = cascadeChamber(current, at, next, clusters.get(idx).get(0), nodes, segments, layout);
            at = next;
        }
    }

    private String cascadeChamber(String fromId, double atM, double toM, OksRoute sample,
                                  List<NewNode> nodes, List<NewSegment> segments, NetworkTreeLayout layout) {
        LineString path = sample.getRoute().getPathUtm();
        int n = cascadeSeq.incrementAndGet();
        String chamberId = "nch_cascade_" + n;
        nodes.add(NewNode.chamber(chamberId));
        LineString conn = snapStart(RouteGeometryUtils.extractSubLine(gf, path, atM, toM, toleranceM),
                layout.nodeCoordinate(fromId));
        layout.putNode(chamberId, conn.getCoordinateN(conn.getNumPoints() - 1));
        NewSegment seg = segmentFromLine(fromId, chamberId, conn);
        segments.add(seg);
        layout.putSegment(seg.getId(), conn);
        return chamberId;
    }

    /**
     * QA-FIX P4 (H-3): начало линии обязано совпадать с координатой узла {@code from} (§7.2:
     * start_node_id и end_node_id совпадают с геометрическими концами LineString).
     * Мелкий зазор — заменяем первую точку, крупный — добавляем связку.
     */
    private LineString snapStart(LineString line, Coordinate node) {
        if (node == null || line == null || line.getNumPoints() < 2) {
            return line;
        }
        Coordinate[] c = line.getCoordinates();
        double gap = c[0].distance(node);
        if (gap < 1e-9) {
            return line;
        }
        List<Coordinate> out = new ArrayList<>();
        out.add(new Coordinate(node.x, node.y));
        int from = gap < 0.5 ? 1 : 0;
        for (int i = from; i < c.length; i++) {
            if (out.get(out.size() - 1).distance(c[i]) > 1e-9) {
                out.add(c[i]);
            }
        }
        if (out.size() < 2) {
            out.add(c[c.length - 1]);
        }
        return gf.createLineString(out.toArray(new Coordinate[0]));
    }

    private int remainingCapacity(String nodeId, List<NewNode> nodes, List<NewSegment> segments) {
        int incident = 0;
        for (NewSegment seg : segments) {
            if (nodeId.equals(seg.getFromNodeId()) || nodeId.equals(seg.getToNodeId())) {
                incident++;
            }
        }
        return Math.max(0, maxSegmentsPerChamber - incident);
    }

    private static boolean isChamber(String id, List<NewNode> nodes) {
        for (NewNode n : nodes) {
            if (n.getId().equals(id) && n.getKind() == NodeKind.NEW_CHAMBER) {
                return true;
            }
        }
        return false;
    }

    private double sharedPrefixFrom(List<OksRoute> group, double fromM) {
        LineString first = group.get(0).getRoute().getPathUtm();
        double shared = RouteGeometryUtils.accumulatedLength(first);
        for (int i = 1; i < group.size(); i++) {
            LineString other = group.get(i).getRoute().getPathUtm();
            if (other == null) {
                continue;
            }
            shared = Math.min(shared, commonPrefixLength(first, other));
        }
        return Math.max(fromM, shared);
    }

    private List<List<OksRoute>> clusterByPrefix(List<OksRoute> group, double fromM) {
        int n = group.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            LineString a = group.get(i).getRoute().getPathUtm();
            for (int j = i + 1; j < n; j++) {
                LineString b = group.get(j).getRoute().getPathUtm();
                if (a == null || b == null) {
                    continue;
                }
                if (commonPrefixLength(a, b) >= fromM + clusterPrefixM) {
                    int pa = find(parent, i);
                    int pb = find(parent, j);
                    if (pa != pb) {
                        parent[pa] = pb;
                    }
                }
            }
        }
        List<List<OksRoute>> clusters = new ArrayList<>();
        boolean[] used = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (used[i]) {
                continue;
            }
            int root = find(parent, i);
            List<OksRoute> cluster = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                if (find(parent, j) == root) {
                    used[j] = true;
                    cluster.add(group.get(j));
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private void appendBranch(String fromChamberId, double branchAtM, OksRoute oksRoute,
                              List<NewNode> nodes, List<NewSegment> segments, NetworkTreeLayout layout) {
        RouteResult route = oksRoute.getRoute();
        OksConnectionPoint oks = oksRoute.getOks();
        LineString path = route.getPathUtm();
        LineString suffixPath = snapStart(
                RouteGeometryUtils.extractSubLine(gf, path, branchAtM, path.getLength(), toleranceM),
                layout.nodeCoordinate(fromChamberId));
        RouteResult suffixRoute = RouteResult.found(suffixPath, route.getSpecialSections());

        List<RouteSegmentSplitter.RoutePiece> pieces = splitter.split(suffixRoute);
        if (pieces.isEmpty()) {
            throw new NetworkException("Пустой маршрут к ОКС " + oks.getId());
        }

        String prevNodeId = fromChamberId;
        for (int i = 0; i < pieces.size(); i++) {
            RouteSegmentSplitter.RoutePiece piece = pieces.get(i);
            Coordinate endCoord = piece.getGeometry().getCoordinateN(piece.getGeometry().getNumPoints() - 1);
            String nextNodeId;
            if (i == pieces.size() - 1) {
                nextNodeId = oksNodeId(oks.getId());
                if (!containsNode(nodes, nextNodeId)) {
                    nodes.add(NewNode.oks(nextNodeId, oks.getId(), oks.getFlowTph()));
                    layout.putNode(nextNodeId, endCoord);
                }
            } else {
                nextNodeId = "tn_" + nodeSeq.incrementAndGet();
                nodes.add(NewNode.technical(nextNodeId));
                layout.putNode(nextNodeId, endCoord);
            }
            NewSegment seg = toSegment(prevNodeId, nextNodeId, piece);
            segments.add(seg);
            layout.putSegment(seg.getId(), piece.getGeometry());
            prevNodeId = nextNodeId;
        }
    }

    /** QA-FIX P4 (H-3): длина участка — длина его геометрии в EPSG:32637, а не хорда. */
    private NewSegment segmentFromLine(String fromId, String toId, LineString line) {
        double len = line.getLength() > 0 ? line.getLength() : GeoUtils.distanceMeters(
                line.getCoordinateN(0), line.getCoordinateN(line.getNumPoints() - 1));
        if (len < toleranceM) {
            len = Math.max(toleranceM, 0.01);
        }
        return NewSegment.base(segId(fromId, toId), fromId, toId, len);
    }

    private double commonPrefixLength(LineString a, LineString b) {
        double step = Math.max(toleranceM, 0.5);
        double max = Math.min(RouteGeometryUtils.accumulatedLength(a), RouteGeometryUtils.accumulatedLength(b));
        double acc = 0;
        while (acc + step <= max + 1e-9) {
            Coordinate pa = RouteGeometryUtils.pointAtDistance(a, acc + step);
            Coordinate pb = RouteGeometryUtils.pointAtDistance(b, acc + step);
            if (GeoUtils.distanceMeters(pa, pb) > toleranceM * 3) {
                break;
            }
            acc += step;
        }
        return acc;
    }

    private NewSegment toSegment(String fromId, String toId, RouteSegmentSplitter.RoutePiece piece) {
        String id = segId(fromId, toId);
        if (piece.getLayingMethod() == ru.heatnet.calc.model.LayingMethod.SPECIAL) {
            return NewSegment.special(id, fromId, toId, piece.getLengthM(), piece.getKSpec());
        }
        return NewSegment.base(id, fromId, toId, piece.getLengthM());
    }

    private String segId(String from, String to) {
        return "seg_" + from + "_" + to + "_" + segSeq.incrementAndGet();
    }

    private static String oksNodeId(String oksId) {
        return "oks_" + oksId;
    }

    private static boolean containsNode(List<NewNode> nodes, String id) {
        for (NewNode n : nodes) {
            if (n.getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Маршрут M3 к одному ОКС. */
    public static final class OksRoute {
        private final OksConnectionPoint oks;
        private final RouteResult route;

        public OksRoute(OksConnectionPoint oks, RouteResult route) {
            this.oks = oks;
            this.route = route;
        }

        public OksConnectionPoint getOks() {
            return oks;
        }

        public RouteResult getRoute() {
            return route;
        }
    }
}
