package ru.heatnet.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.NodeKind;
import ru.heatnet.geo.GeoUtils;

/**
 * Страховка п. 2.3 ТЗ: пересечение ветвей вне камер → камера на магистрали и врезка ветки.
 * Работает по UTM; сохраняет дерево (у камеры один входящий участок).
 * Коллинеарное перекрытие — общий коридор, не X-пересечение: не режется.
 */
public final class CrossingResolver {

    private static final Logger log = LoggerFactory.getLogger(CrossingResolver.class);
    private static final long DEFAULT_BUDGET_MS = 15_000L;
    private static final String CROSS_CHAMBER_PREFIX = "nch_x_";

    private final AtomicInteger chSeq = new AtomicInteger();
    private final AtomicInteger segSeq = new AtomicInteger();
    /** Сколько деревьев пришлось вернуть без разрешения пересечений (страховка сработала). */
    private final AtomicInteger fallbackCount = new AtomicInteger();

    private final GeometryFactory gf;
    private final double toleranceM;
    private final long crossingBudgetMs;

    public CrossingResolver(GeometryFactory gf, double toleranceM) {
        this(gf, toleranceM, DEFAULT_BUDGET_MS);
    }

    public CrossingResolver(GeometryFactory gf, double toleranceM, long crossingBudgetMs) {
        this.gf = gf;
        this.toleranceM = toleranceM;
        this.crossingBudgetMs = crossingBudgetMs > 0 ? crossingBudgetMs : DEFAULT_BUDGET_MS;
    }

    public void resetCounters() {
        chSeq.set(0);
        segSeq.set(0);
        fallbackCount.set(0);
    }

    /** Число деревьев, для которых resolve() откатился к исходному дереву. Норма — 0. */
    public int getFallbackCount() {
        return fallbackCount.get();
    }

    public BuiltNetworkTree resolve(NewNetworkTree tree, NetworkTreeLayout layout) {
        Map<String, NewNode> nodes = new HashMap<>();
        for (NewNode n : tree.getNodes().values()) {
            nodes.put(n.getId(), n);
        }
        List<NewSegment> segments = new ArrayList<>(tree.segmentsTopDown());
        NetworkTreeLayout lay = layout.copy();

        String tieInId = tieInId(tree);
        Set<String> skipped = new HashSet<>();
        int guard = 0;
        long deadline = System.currentTimeMillis() + crossingBudgetMs;
        while (true) {
            CrossingHit hit = findFirstCrossing(segments, lay, skipped);
            if (hit == null) {
                break;
            }
            int guardLimit = Math.max(64, segments.size() * segments.size());
            if (++guard > guardLimit || System.currentTimeMillis() > deadline) {
                throw new NetworkException("CrossingResolver не сошёлся: " + guard
                        + " итераций, участков " + segments.size()
                        + ", врезка " + tieInId);
            }
            if (!resolveOneCrossing(hit, nodes, segments, lay, tieInId)) {
                // Вырожденное X (у вершины одной из линий): резать нечего, камеру не создаём.
                skipped.add(pairKey(hit.s1, hit.s2));
            }
        }
        pruneDeadEnds(nodes, segments, lay, tieInId);
        mergePassThroughCrossChambers(nodes, segments, lay);
        pruneUnusedNodes(nodes, segments, lay, tieInId);

        try {
            return new BuiltNetworkTree(
                    new NewNetworkTree(tree.getTieIn(), new ArrayList<>(nodes.values()), segments),
                    lay);
        } catch (RuntimeException ex) {
            fallbackCount.incrementAndGet();
            log.warn("CrossingResolver: дерево врезки {} возвращено без разрешения пересечений: {}",
                    tieInId, ex.getMessage());
            return new BuiltNetworkTree(tree, layout);
        }
    }

    private static String tieInId(NewNetworkTree tree) {
        return tree.getTieIn().getId();
    }

    /**
     * Удаляет тупики: любой узел, кроме врезки и ОКС, без исходящих участков, вместе с входящим участком.
     * Тупик появляется, когда голову ветки (from → X) отрезали при переподключении к камере X:
     * узел from (техузел DN, камера сборки или более ранняя nch_x_*) теряет последний выход.
     * Каскадно — вверх до узла, у которого остались другие выходы.
     */
    private void pruneDeadEnds(Map<String, NewNode> nodes, List<NewSegment> segments,
                               NetworkTreeLayout layout, String tieInId) {
        boolean changed;
        do {
            changed = false;
            Set<String> hasOut = new HashSet<>();
            for (NewSegment seg : segments) {
                hasOut.add(seg.getFromNodeId());
            }
            for (NewNode node : new ArrayList<>(nodes.values())) {
                if (node.getId().equals(tieInId)
                        || node.getKind() == NodeKind.TIE_IN
                        || node.getKind() == NodeKind.OKS_CONNECTION) {
                    continue;
                }
                if (hasOut.contains(node.getId())) {
                    continue;
                }
                String deadId = node.getId();
                nodes.remove(deadId);
                layout.removeNode(deadId);
                List<NewSegment> kept = new ArrayList<>(segments.size());
                for (NewSegment seg : segments) {
                    if (deadId.equals(seg.getFromNodeId()) || deadId.equals(seg.getToNodeId())) {
                        layout.removeSegment(seg.getId());
                    } else {
                        kept.add(seg);
                    }
                }
                segments.clear();
                segments.addAll(kept);
                changed = true;
            }
        } while (changed);
    }

    /**
     * Камера nch_x_*, у которой после чистки тупиков остался один вход и один выход, — не разветвление.
     * Склеиваем участки (одинаковый способ прокладки) или превращаем в технический узел.
     */
    private void mergePassThroughCrossChambers(Map<String, NewNode> nodes, List<NewSegment> segments,
                                               NetworkTreeLayout layout) {
        boolean changed;
        do {
            changed = false;
            for (NewNode node : new ArrayList<>(nodes.values())) {
                if (node.getKind() != NodeKind.NEW_CHAMBER || !node.getId().startsWith(CROSS_CHAMBER_PREFIX)) {
                    continue;
                }
                NewSegment in = null;
                NewSegment out = null;
                int inCount = 0;
                int outCount = 0;
                for (NewSegment seg : segments) {
                    if (seg.getToNodeId().equals(node.getId())) {
                        in = seg;
                        inCount++;
                    }
                    if (seg.getFromNodeId().equals(node.getId())) {
                        out = seg;
                        outCount++;
                    }
                }
                if (inCount != 1 || outCount != 1) {
                    continue;
                }
                LineString l1 = layout.segmentLine(in.getId());
                LineString l2 = layout.segmentLine(out.getId());
                boolean sameLaying = in.getLayingMethod() == out.getLayingMethod()
                        && in.getKSpec() == out.getKSpec() && in.getKDepth() == out.getKDepth();
                if (!sameLaying || l1 == null || l2 == null) {
                    nodes.put(node.getId(), NewNode.technical(node.getId()));
                    changed = true;
                    continue;
                }
                LineString merged = concat(l1, l2);
                double len = RouteGeometryUtils.accumulatedLength(merged);
                String id = in.getId() + "_m_" + segSeq.incrementAndGet();
                NewSegment joined = new NewSegment(id, in.getFromNodeId(), out.getToNodeId(), len,
                        in.getLayingMethod(), in.getKSpec(), in.getKDepth());
                List<NewSegment> next = new ArrayList<>(segments.size());
                for (NewSegment seg : segments) {
                    if (seg.getId().equals(in.getId())) {
                        next.add(joined);
                    } else if (!seg.getId().equals(out.getId())) {
                        next.add(seg);
                    }
                }
                segments.clear();
                segments.addAll(next);
                layout.removeSegment(in.getId());
                layout.removeSegment(out.getId());
                layout.putSegment(id, merged);
                nodes.remove(node.getId());
                layout.removeNode(node.getId());
                changed = true;
            }
        } while (changed);
    }

    private LineString concat(LineString a, LineString b) {
        List<Coordinate> coords = new ArrayList<>();
        for (Coordinate c : a.getCoordinates()) {
            coords.add(new Coordinate(c));
        }
        Coordinate[] bc = b.getCoordinates();
        for (int i = 0; i < bc.length; i++) {
            if (i == 0 && GeoUtils.distanceMeters(coords.get(coords.size() - 1), bc[0]) < toleranceM) {
                continue;
            }
            coords.add(new Coordinate(bc[i]));
        }
        if (coords.size() < 2) {
            coords.add(new Coordinate(coords.get(0)));
        }
        return gf.createLineString(coords.toArray(new Coordinate[0]));
    }

    /** Камера, в которую ничего не врезали (вырожденное X у вершины). */
    private void pruneUnusedNodes(Map<String, NewNode> nodes, List<NewSegment> segments,
                                  NetworkTreeLayout layout, String tieInId) {
        Set<String> used = new HashSet<>();
        used.add(tieInId);
        for (NewSegment seg : segments) {
            used.add(seg.getFromNodeId());
            used.add(seg.getToNodeId());
        }
        for (String id : new ArrayList<>(nodes.keySet())) {
            if (!used.contains(id)) {
                nodes.remove(id);
                layout.removeNode(id);
            }
        }
    }

    public List<BuiltNetworkTree> resolveAll(List<BuiltNetworkTree> trees) {
        List<BuiltNetworkTree> result = new ArrayList<>();
        for (BuiltNetworkTree tree : trees) {
            result.add(resolve(tree.getTree(), tree.getLayout()));
        }
        return result;
    }

    /**
     * Камера X на магистрали + ветка от X. Атомарно: если магистраль или ветку нельзя разрезать в X
     * (точка у их концов), ничего не меняем и камеру не создаём — иначе nch_x_* остаётся без входа/выхода.
     *
     * @return true, если пересечение разрешено
     */
    private boolean resolveOneCrossing(CrossingHit hit, Map<String, NewNode> nodes,
                                       List<NewSegment> segments, NetworkTreeLayout layout,
                                       String tieInId) {
        Map<String, Integer> depth = nodeDepth(segments, tieInId);
        int depthA = segmentDepth(hit.s1, depth);
        int depthB = segmentDepth(hit.s2, depth);
        NewSegment trunk = depthA <= depthB ? hit.s1 : hit.s2;
        NewSegment branch = depthA <= depthB ? hit.s2 : hit.s1;

        if (!canSplitAt(layout.segmentLine(trunk.getId()), hit.cross)
                || !canRerouteFrom(layout.segmentLine(branch.getId()), hit.cross)) {
            return false;
        }

        String chamberId = CROSS_CHAMBER_PREFIX + chSeq.incrementAndGet();
        nodes.put(chamberId, NewNode.chamber(chamberId));
        layout.putNode(chamberId, hit.cross);

        List<NewSegment> next = new ArrayList<>();
        for (NewSegment seg : segments) {
            if (seg.getId().equals(trunk.getId())) {
                splitSegmentAt(seg, hit.cross, chamberId, layout, next);
            } else if (seg.getId().equals(branch.getId())) {
                rerouteBranchFromChamber(seg, hit.cross, chamberId, layout, next);
            } else {
                next.add(seg);
            }
        }
        segments.clear();
        segments.addAll(next);
        return true;
    }

    private boolean canSplitAt(LineString line, Coordinate cross) {
        if (line == null || line.getNumPoints() < 2) {
            return false;
        }
        double pos = RouteGeometryUtils.positionAlong(line, cross);
        double total = RouteGeometryUtils.accumulatedLength(line);
        return pos >= toleranceM && total - pos >= toleranceM;
    }

    private boolean canRerouteFrom(LineString line, Coordinate cross) {
        if (line == null || line.getNumPoints() < 2) {
            return false;
        }
        double pos = RouteGeometryUtils.positionAlong(line, cross);
        double total = RouteGeometryUtils.accumulatedLength(line);
        return total - pos >= toleranceM;
    }

    private static String pairKey(NewSegment a, NewSegment b) {
        return a.getId().compareTo(b.getId()) < 0
                ? a.getId() + "|" + b.getId()
                : b.getId() + "|" + a.getId();
    }

    /** Ветка переподключается от камеры на магистрали (T-образное присоединение, один вход в камеру). */
    private void rerouteBranchFromChamber(NewSegment branch, Coordinate cross, String chamberId,
                                          NetworkTreeLayout layout, List<NewSegment> out) {
        LineString line = layout.segmentLine(branch.getId());
        if (line == null) {
            out.add(branch);
            return;
        }
        double splitPos = RouteGeometryUtils.positionAlong(line, cross);
        double totalLen = RouteGeometryUtils.accumulatedLength(line);
        if (totalLen - splitPos < toleranceM) {
            out.add(branch);
            return;
        }
        layout.removeSegment(branch.getId());
        String id = branch.getId() + "_r_" + segSeq.incrementAndGet();
        LineString rerouted = RouteGeometryUtils.extractSubLine(gf, line, splitPos, totalLen, toleranceM);
        double len = RouteGeometryUtils.accumulatedLength(rerouted);
        if (branch.getLayingMethod() == ru.heatnet.calc.model.LayingMethod.SPECIAL) {
            out.add(NewSegment.special(id, chamberId, branch.getToNodeId(), len, branch.getKSpec()));
        } else {
            out.add(NewSegment.base(id, chamberId, branch.getToNodeId(), len));
        }
        layout.putSegment(id, rerouted);
    }

    private CrossingHit findFirstCrossing(List<NewSegment> segments, NetworkTreeLayout layout,
                                          Set<String> skipped) {
        STRtree tree = new STRtree();
        LineString[] lines = new LineString[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            LineString line = layout.segmentLine(segments.get(i).getId());
            lines[i] = line;
            if (line == null || line.isEmpty()) {
                continue;
            }
            tree.insert(line.getEnvelopeInternal(), Integer.valueOf(i));
        }
        tree.build();

        for (int i = 0; i < segments.size(); i++) {
            LineString l1 = lines[i];
            if (l1 == null || l1.isEmpty()) {
                continue;
            }
            Envelope search = l1.getEnvelopeInternal();
            @SuppressWarnings("unchecked")
            List<Integer> neighbours = tree.query(search);
            for (Integer jObj : neighbours) {
                int j = jObj.intValue();
                if (j <= i) {
                    continue;
                }
                NewSegment s1 = segments.get(i);
                NewSegment s2 = segments.get(j);
                if (skipped.contains(pairKey(s1, s2))) {
                    continue;
                }
                LineString l2 = lines[j];
                if (l2 == null || l2.isEmpty()) {
                    continue;
                }
                // QA-FIX P8 (H-5): участки с общим узлом тоже проверяются. Общий узел разрешает
                // пересечение только В САМОМ УЗЛЕ (§2.1), но не дальше по трассе.
                CrossingHit hit = sharesEndpoint(s1, s2)
                        ? toHitShared(s1, s2, l1, l2, layout)
                        : toHit(s1, s2, l1, l2);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /**
     * X-пересечение в точке — да; коллинеарное перекрытие (dim ≥ 1) — общий коридор M4, не режем.
     */
    private CrossingHit toHit(NewSegment s1, NewSegment s2, LineString l1, LineString l2) {
        Geometry inter;
        try {
            if (!l1.getEnvelopeInternal().intersects(l2.getEnvelopeInternal())) {
                return null;
            }
            inter = l1.intersection(l2);
        } catch (RuntimeException ex) {
            return null;
        }
        if (inter == null || inter.isEmpty()) {
            return null;
        }
        if (inter.getDimension() >= 1) {
            return null;
        }
        Coordinate cross = inter.getCoordinate();
        if (cross == null) {
            return null;
        }
        if (nearEndpoint(l1, cross) || nearEndpoint(l2, cross)) {
            return null;
        }
        return new CrossingHit(s1, s2, cross);
    }

    /**
     * QA-FIX P8 (H-5): пара с общим узлом. Пересечение берётся за вычетом окрестности общего узла;
     * для наложения (dim ≥ 1) точка разрешения — дальний от узла конец общего коридора,
     * то есть «общий ствол + камера в точке расхождения».
     */
    private CrossingHit toHitShared(NewSegment s1, NewSegment s2, LineString l1, LineString l2,
                                    NetworkTreeLayout layout) {
        String common = s1.getFromNodeId().equals(s2.getFromNodeId())
                || s1.getFromNodeId().equals(s2.getToNodeId())
                ? s1.getFromNodeId() : s1.getToNodeId();
        Coordinate c0 = layout.nodeCoordinate(common);
        if (c0 == null) {
            return null;
        }
        Geometry inter;
        try {
            inter = l1.intersection(l2).difference(gf.createPoint(c0).buffer(Math.max(toleranceM * 2, 0.02)));
        } catch (RuntimeException ex) {
            return null;
        }
        if (inter == null || inter.isEmpty()) {
            return null;
        }
        Coordinate best = null;
        double bestD = inter.getDimension() >= 1 ? -1 : Double.MAX_VALUE;
        for (Coordinate c : inter.getCoordinates()) {
            double d = c.distance(c0);
            if (inter.getDimension() >= 1 ? d > bestD : d < bestD) {
                bestD = d;
                best = c;
            }
        }
        if (best == null || farEndpoint(l1, c0, best) || farEndpoint(l2, c0, best)) {
            return null;
        }
        return new CrossingHit(s1, s2, best);
    }

    /** Точка у НЕобщего конца линии — там уже есть узел, резать нечего. */
    private boolean farEndpoint(LineString line, Coordinate common, Coordinate p) {
        Coordinate a = line.getCoordinateN(0);
        Coordinate b = line.getCoordinateN(line.getNumPoints() - 1);
        Coordinate far = a.distance(common) <= b.distance(common) ? b : a;
        return GeoUtils.distanceMeters(p, far) < toleranceM;
    }

    private boolean nearEndpoint(LineString line, Coordinate p) {
        Coordinate a = line.getCoordinateN(0);
        Coordinate b = line.getCoordinateN(line.getNumPoints() - 1);
        return GeoUtils.distanceMeters(p, a) < toleranceM
                || GeoUtils.distanceMeters(p, b) < toleranceM;
    }

    private void splitSegmentAt(NewSegment seg, Coordinate cross, String chamberId,
                                NetworkTreeLayout layout, List<NewSegment> out) {
        LineString line = layout.segmentLine(seg.getId());
        if (line == null || line.getNumPoints() < 2) {
            out.add(seg);
            return;
        }
        double splitPos = RouteGeometryUtils.positionAlong(line, cross);
        double totalLen = RouteGeometryUtils.accumulatedLength(line);
        if (splitPos < toleranceM || totalLen - splitPos < toleranceM) {
            out.add(seg);
            return;
        }
        layout.removeSegment(seg.getId());
        String id1 = seg.getId() + "_a_" + segSeq.incrementAndGet();
        String id2 = seg.getId() + "_b_" + segSeq.incrementAndGet();
        LineString line1 = RouteGeometryUtils.extractSubLine(gf, line, 0, splitPos, toleranceM);
        LineString line2 = RouteGeometryUtils.extractSubLine(gf, line, splitPos, totalLen, toleranceM);
        double len1 = RouteGeometryUtils.accumulatedLength(line1);
        double len2 = RouteGeometryUtils.accumulatedLength(line2);
        if (seg.getLayingMethod() == ru.heatnet.calc.model.LayingMethod.SPECIAL) {
            out.add(NewSegment.special(id1, seg.getFromNodeId(), chamberId, len1, seg.getKSpec()));
            out.add(NewSegment.special(id2, chamberId, seg.getToNodeId(), len2, seg.getKSpec()));
        } else {
            out.add(NewSegment.base(id1, seg.getFromNodeId(), chamberId, len1));
            out.add(NewSegment.base(id2, chamberId, seg.getToNodeId(), len2));
        }
        layout.putSegment(id1, line1);
        layout.putSegment(id2, line2);
    }

    private static Map<String, Integer> nodeDepth(List<NewSegment> segments, String tieInId) {
        Map<String, Integer> depth = new HashMap<>();
        depth.put(tieInId, 0);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (NewSegment seg : segments) {
                Integer fromD = depth.get(seg.getFromNodeId());
                if (fromD == null) {
                    continue;
                }
                int next = fromD + 1;
                Integer toD = depth.get(seg.getToNodeId());
                if (toD == null || next < toD) {
                    depth.put(seg.getToNodeId(), next);
                    changed = true;
                }
            }
        }
        return depth;
    }

    private static int segmentDepth(NewSegment seg, Map<String, Integer> depth) {
        Integer d = depth.get(seg.getFromNodeId());
        return d == null ? Integer.MAX_VALUE : d;
    }

    private static boolean sharesEndpoint(NewSegment a, NewSegment b) {
        return a.getFromNodeId().equals(b.getFromNodeId())
                || a.getFromNodeId().equals(b.getToNodeId())
                || a.getToNodeId().equals(b.getFromNodeId())
                || a.getToNodeId().equals(b.getToNodeId());
    }

    private static final class CrossingHit {
        private final NewSegment s1;
        private final NewSegment s2;
        private final Coordinate cross;

        CrossingHit(NewSegment s1, NewSegment s2, Coordinate cross) {
            this.s1 = s1;
            this.s2 = s2;
            this.cross = cross;
        }
    }
}
