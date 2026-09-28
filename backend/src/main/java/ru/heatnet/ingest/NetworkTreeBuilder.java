package ru.heatnet.ingest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Component;

import ru.heatnet.calc.model.ExistingChamber;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingSegment;
import ru.heatnet.geo.GeoUtils;
import ru.heatnet.geo.ProjectionService;

/**
 * Строит {@link ExistingNetwork} по upstream_object_id или геометрическому snap концов.
 */
@Component
public class NetworkTreeBuilder {

    private final ProjectionService projectionService;

    public NetworkTreeBuilder(ProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    public ExistingNetwork build(Collection<RawFeature> networkFeatures,
                                 double snapToleranceM,
                                 IngestReport report) {
        List<DraftSegment> segments = new ArrayList<>();
        Map<String, Coordinate> chambers = new LinkedHashMap<>();
        List<String> sourceIds = new ArrayList<>();
        Coordinate sourceUtm = null;

        for (RawFeature feature : networkFeatures) {
            switch (feature.getKind()) {
                case HEAT_NETWORK:
                    segments.add(toDraftSegment(feature, report));
                    break;
                case HEAT_CHAMBER:
                    chambers.put(feature.getId(), chamberUtm(feature));
                    break;
                case SOURCE:
                    sourceIds.add(feature.getId());
                    sourceUtm = pointUtm(feature);
                    break;
                default:
                    break;
            }
        }

        if (sourceIds.isEmpty()) {
            // AUDIT-24.09 (Claude): раньше без source вся существующая сеть отбрасывалась, и ни один
            // ОКС не мог быть подключён. Направление к источнику нужно было только для реконструкции,
            // которой в актуальной модели нет (§2.4 приложения) — сеть строится без upstream.
            report.warn("SOURCE_MISSING", null,
                    "В данных нет source — сеть используется без направления к источнику (врезки доступны)");
        }
        if (sourceIds.size() > 1) {
            report.warn("SOURCE_MULTIPLE", null,
                    "Найдено source: " + sourceIds.size() + ", используется первый: " + sourceIds.get(0));
            sourceIds = Collections.singletonList(sourceIds.get(0));
        }

        boolean explicitUpstream = hasExplicitUpstream(segments, chambers);
        Map<String, String> upstreamBySegment = new HashMap<>();
        Map<String, String> upstreamByChamber = new HashMap<>();

        if (explicitUpstream) {
            applyExplicitUpstream(segments, chambers, upstreamBySegment, upstreamByChamber, report);
            detectUpstreamCycles(networkFeatures, report);
        } else if (!sourceIds.isEmpty()) {
            inferUpstreamByGeometry(segments, chambers, sourceIds.get(0), sourceUtm, snapToleranceM,
                    upstreamBySegment, upstreamByChamber, report);
        }

        List<ExistingSegment> builtSegments = new ArrayList<>();
        for (DraftSegment draft : segments) {
            if (draft == null) {
                continue;
            }
            String upstream = draft.explicitUpstream != null && explicitUpstream
                    ? draft.explicitUpstream
                    : upstreamBySegment.get(draft.id);
            builtSegments.add(new ExistingSegment(
                    draft.id,
                    draft.diameter,
                    draft.flowTph,
                    upstream,
                    draft.lengthM));
        }

        Map<String, RawFeature> byId = new HashMap<>();
        for (RawFeature f : networkFeatures) {
            byId.putIfAbsent(f.getId(), f);
        }
        List<ExistingChamber> builtChambers = new ArrayList<>();
        for (Map.Entry<String, Coordinate> e : chambers.entrySet()) {
            String id = e.getKey();
            RawFeature raw = byId.get(id);
            Integer diameter = raw == null ? null : intProp(raw, "diameter");
            String explicit = raw == null ? null : stringProp(raw, "upstream_object_id");
            String upstream = explicit != null && explicitUpstream
                    ? explicit
                    : upstreamByChamber.get(id);
            builtChambers.add(new ExistingChamber(id, diameter, upstream));
        }

        try {
            return new ExistingNetwork(builtSegments, builtChambers, sourceIds);
        } catch (RuntimeException ex) {
            report.error("NETWORK_BUILD", null, ex.getMessage());
            return emptyNetwork();
        }
    }

    private void inferUpstreamByGeometry(List<DraftSegment> segments,
                                         Map<String, Coordinate> chambers,
                                         String sourceId,
                                         Coordinate sourceUtm,
                                         double snapToleranceM,
                                         Map<String, String> upstreamBySegment,
                                         Map<String, String> upstreamByChamber,
                                         IngestReport report) {
        Graph graph = new Graph();
        graph.addNode(sourceId, NodeKind.SOURCE);

        for (String chamberId : chambers.keySet()) {
            graph.addNode(chamberId, NodeKind.CHAMBER);
        }
        for (DraftSegment segment : segments) {
            if (segment != null) {
                graph.addNode(segment.id, NodeKind.SEGMENT);
            }
        }

        Map<String, EndpointSnap> startSnaps = new HashMap<>();
        Map<String, EndpointSnap> endSnaps = new HashMap<>();
        EndpointGrid pipeGrid = EndpointGrid.build(segments, snapToleranceM);

        for (DraftSegment segment : segments) {
            if (segment == null) {
                continue;
            }
            EndpointSnap start = snapEndpoint(segment.id, true, segment.startUtm,
                    sourceId, sourceUtm, chambers, pipeGrid, snapToleranceM);
            EndpointSnap end = snapEndpoint(segment.id, false, segment.endUtm,
                    sourceId, sourceUtm, chambers, pipeGrid, snapToleranceM);
            startSnaps.put(segment.id, start);
            endSnaps.put(segment.id, end);
            connectSnap(graph, segment.id, start);
            connectSnap(graph, segment.id, end);
        }

        Map<String, Integer> depth = bfsDepth(graph, sourceId);
        for (DraftSegment segment : segments) {
            if (segment == null) {
                continue;
            }
            EndpointSnap start = startSnaps.get(segment.id);
            EndpointSnap end = endSnaps.get(segment.id);
            String upstream = chooseUpstream(segment.id, start, end, depth, graph);
            if (upstream == null) {
                report.warn("NET_UNREACHABLE", segment.id,
                        "Участок не достижим из source по геометрическому snap");
            }
            upstreamBySegment.put(segment.id, upstream);
        }

        for (String chamberId : chambers.keySet()) {
            String upstream = chooseChamberUpstream(chamberId, graph, depth);
            if (upstream == null) {
                report.warn("CHAMBER_UNREACHABLE", chamberId,
                        "Камера не достижима из source по геометрическому snap");
            }
            upstreamByChamber.put(chamberId, upstream);
        }

        logIsolatedSegments(segments, depth, report);
    }

    private static void connectSnap(Graph graph, String segmentId, EndpointSnap snap) {
        if (snap == null || snap.targetId == null) {
            return;
        }
        graph.connect(segmentId, snap.targetId);
    }

    private EndpointSnap snapEndpoint(String segmentId,
                                      boolean start,
                                      Coordinate endpointUtm,
                                      String sourceId,
                                      Coordinate sourceUtm,
                                      Map<String, Coordinate> chambers,
                                      EndpointGrid pipeGrid,
                                      double toleranceM) {
        if (sourceUtm != null) {
            double dSource = GeoUtils.distanceMeters(endpointUtm, sourceUtm);
            if (dSource <= toleranceM) {
                return new EndpointSnap(sourceId, dSource);
            }
        }
        EndpointSnap chamberSnap = snapToChamber(endpointUtm, chambers, toleranceM);
        if (chamberSnap != null) {
            return chamberSnap;
        }
        return pipeGrid.snap(segmentId, endpointUtm, toleranceM);
    }

    private EndpointSnap snapToChamber(Coordinate endpointUtm,
                                       Map<String, Coordinate> chambers,
                                       double toleranceM) {
        String bestId = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<String, Coordinate> e : chambers.entrySet()) {
            double d = GeoUtils.distanceMeters(endpointUtm, e.getValue());
            if (d <= toleranceM && d < bestDist) {
                bestDist = d;
                bestId = e.getKey();
            }
        }
        if (bestId == null) {
            return null;
        }
        return new EndpointSnap(bestId, bestDist);
    }

    private static final class EndpointGrid {
        private final Map<Long, List<DraftSegment>> cells = new HashMap<>();
        private final double cellSize;

        private EndpointGrid(double cellSize) {
            this.cellSize = Math.max(cellSize, 0.5);
        }

        static EndpointGrid build(List<DraftSegment> segments, double toleranceM) {
            EndpointGrid grid = new EndpointGrid(Math.max(toleranceM * 2.0, 1.0));
            for (DraftSegment segment : segments) {
                if (segment == null) {
                    continue;
                }
                grid.put(segment.startUtm, segment);
                grid.put(segment.endUtm, segment);
            }
            return grid;
        }

        private void put(Coordinate c, DraftSegment segment) {
            long key = keyOf(c);
            List<DraftSegment> list = cells.get(key);
            if (list == null) {
                list = new ArrayList<>();
                cells.put(key, list);
            }
            list.add(segment);
        }

        private long keyOf(Coordinate c) {
            int x = (int) Math.floor(c.x / cellSize);
            int y = (int) Math.floor(c.y / cellSize);
            return ((long) x << 32) ^ (y & 0xffffffffL);
        }

        EndpointSnap snap(String selfSegmentId, Coordinate endpointUtm, double toleranceM) {
            String bestSegmentId = null;
            double bestDist = Double.MAX_VALUE;
            int cx = (int) Math.floor(endpointUtm.x / cellSize);
            int cy = (int) Math.floor(endpointUtm.y / cellSize);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    long key = ((long) (cx + dx) << 32) ^ ((cy + dy) & 0xffffffffL);
                    List<DraftSegment> list = cells.get(key);
                    if (list == null) {
                        continue;
                    }
                    for (DraftSegment other : list) {
                        if (other.id.equals(selfSegmentId)) {
                            continue;
                        }
                        double dStart = GeoUtils.distanceMeters(endpointUtm, other.startUtm);
                        if (dStart <= toleranceM && dStart < bestDist) {
                            bestDist = dStart;
                            bestSegmentId = other.id;
                        }
                        double dEnd = GeoUtils.distanceMeters(endpointUtm, other.endUtm);
                        if (dEnd <= toleranceM && dEnd < bestDist) {
                            bestDist = dEnd;
                            bestSegmentId = other.id;
                        }
                    }
                }
            }
            if (bestSegmentId == null) {
                return null;
            }
            return new EndpointSnap(bestSegmentId, bestDist);
        }
    }

    private String chooseUpstream(String segmentId,
                                  EndpointSnap start,
                                  EndpointSnap end,
                                  Map<String, Integer> depth,
                                  Graph graph) {
        Integer selfDepth = depth.get(segmentId);
        if (selfDepth == null) {
            return null;
        }
        String best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (String neighbor : graph.neighbors(segmentId)) {
            int d = depth.getOrDefault(neighbor, Integer.MAX_VALUE);
            if (d < selfDepth && d < bestDepth) {
                bestDepth = d;
                best = neighbor;
            }
        }
        if (best != null) {
            return best;
        }
        if (start != null && start.targetId != null) {
            int d = depth.getOrDefault(start.targetId, Integer.MAX_VALUE);
            if (d < selfDepth && d < bestDepth) {
                best = start.targetId;
            }
        }
        if (end != null && end.targetId != null) {
            int d = depth.getOrDefault(end.targetId, Integer.MAX_VALUE);
            if (d < selfDepth && (best == null || d < depth.getOrDefault(best, Integer.MAX_VALUE))) {
                best = end.targetId;
            }
        }
        return best;
    }

    private String chooseChamberUpstream(String chamberId, Graph graph, Map<String, Integer> depth) {
        Integer selfDepth = depth.get(chamberId);
        if (selfDepth == null) {
            return null;
        }
        String best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (String neighbor : graph.neighbors(chamberId)) {
            int d = depth.getOrDefault(neighbor, Integer.MAX_VALUE);
            if (d < selfDepth && d < bestDepth) {
                bestDepth = d;
                best = neighbor;
            }
        }
        return best;
    }

    private Map<String, Integer> bfsDepth(Graph graph, String sourceId) {
        Map<String, Integer> depth = new HashMap<>();
        Queue<String> queue = new ArrayDeque<>();
        depth.put(sourceId, 0);
        queue.add(sourceId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            int nextDepth = depth.get(current) + 1;
            for (String neighbor : graph.neighbors(current)) {
                if (!depth.containsKey(neighbor)) {
                    depth.put(neighbor, nextDepth);
                    queue.add(neighbor);
                }
            }
        }
        return depth;
    }

    private void logIsolatedSegments(List<DraftSegment> segments, Map<String, Integer> depth,
                                     IngestReport report) {
        for (DraftSegment segment : segments) {
            if (segment != null && !depth.containsKey(segment.id)) {
                report.warn("NET_ISOLATED", segment.id, "Участок вне графа достижимости source");
            }
        }
    }

    private void applyExplicitUpstream(List<DraftSegment> segments,
                                       Map<String, Coordinate> chambers,
                                       Map<String, String> upstreamBySegment,
                                       Map<String, String> upstreamByChamber,
                                       IngestReport report) {
        for (DraftSegment segment : segments) {
            if (segment != null && segment.explicitUpstream != null) {
                upstreamBySegment.put(segment.id, segment.explicitUpstream);
            } else if (segment != null) {
                report.warn("NET_UPSTREAM_MISSING", segment.id,
                        "upstream_object_id не задан при явном режиме");
            }
        }
        for (String chamberId : chambers.keySet()) {
            // explicit upstream applied when building chambers from raw properties
        }
    }

    private void detectUpstreamCycles(Collection<RawFeature> networkFeatures, IngestReport report) {
        Map<String, String> upstream = new HashMap<>();
        Set<String> sourceIds = new HashSet<>();

        for (RawFeature feature : networkFeatures) {
            if (feature.getKind() == RawFeature.Kind.SOURCE) {
                sourceIds.add(feature.getId());
            }
            if (feature.getKind() == RawFeature.Kind.HEAT_NETWORK
                    || feature.getKind() == RawFeature.Kind.HEAT_CHAMBER) {
                String up = stringProp(feature, "upstream_object_id");
                if (up != null) {
                    upstream.put(feature.getId(), up);
                }
            }
        }

        for (Map.Entry<String, String> entry : upstream.entrySet()) {
            Set<String> visited = new HashSet<>();
            String current = entry.getKey();
            while (current != null) {
                if (sourceIds.contains(current)) {
                    break;
                }
                if (!visited.add(current)) {
                    report.error("UPSTREAM_CYCLE", entry.getKey(),
                            "Цикл в цепочке upstream_object_id, зацикливание на " + current);
                    break;
                }
                current = upstream.get(current);
            }
        }
    }

    private boolean hasExplicitUpstream(List<DraftSegment> segments, Map<String, Coordinate> chambers) {
        for (DraftSegment segment : segments) {
            if (segment != null && segment.explicitUpstream != null) {
                return true;
            }
        }
        return false;
    }

    private DraftSegment toDraftSegment(RawFeature feature, IngestReport report) {
        Integer diameter = intProp(feature, "diameter");
        if (diameter == null || diameter <= 0) {
            return null;
        }
        Coordinate[] wgs = feature.getLineCoordinatesWgs84();
        LineString lineUtm = projectionService.lineToUtm(wgs);
        double lengthM = lineUtm.getLength();
        if (!(lengthM > 0)) {
            report.error("NET_ZERO_LENGTH", feature.getId(), "heat_network: нулевая длина после проекции");
            return null;
        }
        return new DraftSegment(
                feature.getId(),
                diameter,
                numericProp(feature, "flow_tph"),
                stringProp(feature, "upstream_object_id"),
                lineUtm,
                lengthM,
                lineUtm.getCoordinateN(0),
                lineUtm.getCoordinateN(lineUtm.getNumPoints() - 1));
    }

    private Coordinate chamberUtm(RawFeature feature) {
        return pointUtm(feature);
    }

    private Coordinate pointUtm(RawFeature feature) {
        org.locationtech.jts.geom.Point point = (org.locationtech.jts.geom.Point) feature.getGeometryWgs84();
        return projectionService.toUtm(point.getX(), point.getY());
    }

    private static RawFeature findFeature(Collection<RawFeature> features, String id) {
        for (RawFeature f : features) {
            if (f.getId().equals(id)) {
                return f;
            }
        }
        return null;
    }

    private static ExistingNetwork emptyNetwork() {
        return new ExistingNetwork(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    private static String stringProp(RawFeature feature, String key) {
        Object v = feature.getProperties().get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intProp(RawFeature feature, String key) {
        Object v = feature.getProperties().get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return ru.heatnet.ingest.RawFeature.parseIntLenient(v); // AUDIT-12 (Claude, 24.09): "500.0", " 500 "
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double numericProp(RawFeature feature, String key) {
        Object v = feature.getProperties().get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return ru.heatnet.ingest.RawFeature.parseDoubleLenient(v); // AUDIT-12 (Claude, 24.09): "12,5"
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static final class DraftSegment {
        final String id;
        final int diameter;
        final Double flowTph;
        final String explicitUpstream;
        final LineString lineUtm;
        final double lengthM;
        final Coordinate startUtm;
        final Coordinate endUtm;

        DraftSegment(String id, int diameter, Double flowTph, String explicitUpstream,
                     LineString lineUtm, double lengthM, Coordinate startUtm, Coordinate endUtm) {
            this.id = id;
            this.diameter = diameter;
            this.flowTph = flowTph;
            this.explicitUpstream = explicitUpstream;
            this.lineUtm = lineUtm;
            this.lengthM = lengthM;
            this.startUtm = startUtm;
            this.endUtm = endUtm;
        }
    }

    private static final class EndpointSnap {
        final String targetId;
        final double distanceM;

        EndpointSnap(String targetId, double distanceM) {
            this.targetId = targetId;
            this.distanceM = distanceM;
        }
    }

    private static final class Graph {
        private final Map<String, NodeKind> kinds = new HashMap<>();
        private final Map<String, Set<String>> adjacency = new HashMap<>();

        void addNode(String id, NodeKind kind) {
            kinds.put(id, kind);
            adjacency.computeIfAbsent(id, k -> new HashSet<>());
        }

        void connect(String a, String b) {
            if (a == null || b == null || a.equals(b)) {
                return;
            }
            adjacency.computeIfAbsent(a, k -> new HashSet<>()).add(b);
            adjacency.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        }

        Set<String> neighbors(String id) {
            return adjacency.getOrDefault(id, Collections.emptySet());
        }
    }

    private enum NodeKind {
        SOURCE, CHAMBER, SEGMENT
    }
}
