package ru.heatnet.depth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.TreeCalcResult;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.reference.DepthRules;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.cost.DepthCoefficient;
import ru.heatnet.cost.ScoreCalculator;
import ru.heatnet.cost.SegmentCostCalculator;
import ru.heatnet.cost.VariantCost;
import ru.heatnet.cost.VariantSummary;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.RawFeature;
import ru.heatnet.network.NetworkPlan;
import ru.heatnet.network.NetworkTreeLayout;

/**
 * M11. Накладывает профиль глубины на готовый 2D-план и пересчитывает стоимость участков с Kгл.
 * Горизонтальная трасса не ищется заново.
 */
@Component
public class DepthTracer {

    private static final Logger log = LoggerFactory.getLogger(DepthTracer.class);

    private final ReferenceData reference;
    private final ProjectionService projection;
    private final DepthProfileSolver solver;
    private final SegmentCostCalculator segmentCost;
    private final DepthCoefficient coefficient;
    private final ScoreCalculator scoreCalculator;

    public DepthTracer(ReferenceData reference, ProjectionService projection) {
        this.reference = reference;
        this.projection = projection;
        this.solver = new DepthProfileSolver(reference.getDepth(), reference.getGabarits());
        this.segmentCost = new SegmentCostCalculator(reference.getDiameters());
        this.coefficient = new DepthCoefficient(reference.getDepth());
        this.scoreCalculator = new ScoreCalculator(reference.getRules());
    }

    /** Препятствия из ingest, геометрия переведена в EPSG:32637. */
    public List<DepthObstacle> fromIngest(IngestResult ingest) {
        List<DepthObstacle> obstacles = new ArrayList<>();
        if (ingest == null) {
            return obstacles;
        }
        DepthRules depth = reference.getDepth();
        for (RawFeature feature : ingest.getAcceptedFeatures()) {
            if (feature.getGeometryWgs84() == null) {
                continue;
            }
            if (feature.getKind() == RawFeature.Kind.RESTRICTION) {
                String type = feature.restrictionType();
                if (type == null) {
                    continue;
                }
                Geometry utm = toUtm(feature.getGeometryWgs84());
                if (utm == null) {
                    continue;
                }
                if (depth.getSurfaceCrossings().containsKey(type)) {
                    DepthRules.SurfaceCrossing surface = depth.getSurfaceCrossings().get(type);
                    Geometry zone = utm.buffer(surface.getZoneMarginM());
                    obstacles.add(DepthObstacle.surface(feature.getId(), type, zone,
                            surface.getMinTopDepthBelowSurfaceM()));
                } else if (depth.getUtilities().containsKey(type)) {
                    obstacles.add(DepthObstacle.utility(feature.getId(), type, utm, 0));
                }
            } else if (feature.getKind() == RawFeature.Kind.HEAT_NETWORK) {
                Geometry utm = toUtm(feature.getGeometryWgs84());
                if (utm == null) {
                    continue;
                }
                obstacles.add(DepthObstacle.utility(feature.getId(), "heat_network", utm,
                        intProperty(feature.getProperties(), "diameter")));
            }
        }
        return obstacles;
    }

    /**
     * Профиль по всем участкам плана.
     * Препятствие с id точки врезки (труба, в которую врезаемся) пропускается.
     */
    public DepthOutcome trace(NetworkPlan plan, EngineeringResult engineering, VariantCost cost,
                              List<DepthObstacle> obstacles) {
        if (plan == null || engineering == null || cost == null) {
            return DepthOutcome.unchanged();
        }
        Map<String, List<DepthPiece>> replacements = new LinkedHashMap<>();
        List<DepthExtraNode> extraNodes = new ArrayList<>();
        List<NewNetworkTree> trees = plan.getTrees();
        List<NetworkTreeLayout> layouts = plan.getLayouts();
        for (int i = 0; i < trees.size(); i++) {
            NewNetworkTree tree = trees.get(i);
            NetworkTreeLayout layout = i < layouts.size() ? layouts.get(i) : null;
            TreeCalcResult calc = calcOf(engineering, tree);
            if (calc == null || layout == null) {
                continue;
            }
            String skipId = tree.getTieIn().getExistingObjectId();
            List<DepthObstacle> filtered = filter(obstacles, skipId);
            // AUDIT-24.09 (Claude): профиль строится по ЦЕПОЧКЕ участков между узлами разветвления (врезка,
            // камеры), а не по каждому участку отдельно. 2D-план делит трассу на границах спецпроходов, и
            // пересечение газа/кабеля/теплосети — это отдельный участок длиной 4 м (±2 м от точки, табл. 2).
            // Решатель, запущенный на таком участке, требует обычную глубину 3,0 м на обоих его концах —
            // спуск/подъём (≥ 10 м на 1 м глубины, §5) не помещается, и глубина оставалась 3,0 м прямо в
            // габарите пересекаемого объекта (газ 2,8–3,2 м, кабель 2,7–2,9 м, теплосеть 3,0–3,56 м).
            // Теперь площадка приходится на участок спецпрохода, а спуск и подъём — на соседние участки.
            for (List<NewSegment> chain : chains(tree)) {
                traceChain(chain, layout, calc, filtered, replacements, extraNodes);
            }
        }
        if (replacements.isEmpty()) {
            return DepthOutcome.unchanged();
        }
        VariantSummary summary = rebuild(plan, cost, replacements);
        return new DepthOutcome(replacements, extraNodes, summary);
    }

    /** Цепочки участков от врезки/узла разветвления до следующего узла разветвления или ОКС. */
    static List<List<NewSegment>> chains(NewNetworkTree tree) {
        List<List<NewSegment>> out = new ArrayList<>();
        NewSegment root = tree.rootSegment();
        if (root == null) {
            return out;
        }
        java.util.Deque<NewSegment> starts = new java.util.ArrayDeque<>();
        starts.add(root);
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (!starts.isEmpty()) {
            NewSegment cur = starts.poll();
            List<NewSegment> chain = new ArrayList<>();
            while (cur != null && seen.add(cur.getId())) {
                chain.add(cur);
                List<NewSegment> children = tree.childrenOf(cur.getToNodeId());
                if (children.size() == 1) {
                    cur = children.get(0);
                } else {
                    starts.addAll(children);
                    cur = null;
                }
            }
            if (!chain.isEmpty()) {
                out.add(chain);
            }
        }
        return out;
    }

    private void traceChain(List<NewSegment> chain, NetworkTreeLayout layout, TreeCalcResult calc,
                            List<DepthObstacle> obstacles, Map<String, List<DepthPiece>> replacements,
                            List<DepthExtraNode> extraNodes) {
        List<LineString> lines = new ArrayList<>();
        Integer dn = null;
        for (NewSegment segment : chain) {
            LineString line = oriented(layout, segment);
            Integer segDn = calc.getDiameters().get(segment.getId());
            if (line == null || segDn == null || (dn != null && !dn.equals(segDn))) {
                // нет геометрии или ДУ меняется внутри цепочки (не должно быть по §2.3) — прежний способ
                for (NewSegment s : chain) {
                    traceSingle(s, layout, calc, obstacles, replacements, extraNodes);
                }
                return;
            }
            dn = segDn;
            lines.add(line);
        }
        List<Coordinate> coords = new ArrayList<>();
        double[] offsets = new double[chain.size() + 1];
        for (int i = 0; i < lines.size(); i++) {
            LineString line = lines.get(i);
            offsets[i + 1] = offsets[i] + line.getLength();
            for (int k = 0; k < line.getNumPoints(); k++) {
                Coordinate c = line.getCoordinateN(k);
                if (coords.isEmpty() || coords.get(coords.size() - 1).distance(c) > 1e-6) {
                    coords.add(new Coordinate(c));
                }
            }
        }
        if (coords.size() < 2) {
            return;
        }
        LineString chainLine = lines.get(0).getFactory().createLineString(coords.toArray(new Coordinate[0]));
        List<DepthSpan> spans;
        try {
            spans = solver.solve(chainLine, dn, obstacles);
        } catch (RuntimeException ex) {
            log.warn("Профиль глубины цепочки от {}: {}", chain.get(0).getId(), ex.getMessage());
            return;
        }
        if (spans == null || spans.isEmpty()) {
            return;
        }
        double scale = chainLine.getLength() > 1e-9 ? offsets[chain.size()] / chainLine.getLength() : 1.0;
        for (int i = 0; i < chain.size(); i++) {
            NewSegment segment = chain.get(i);
            List<DepthSpan> local = clip(spans, offsets[i] / scale, offsets[i + 1] / scale, lines.get(i));
            if (local.isEmpty()) {
                continue;
            }
            Double flow = calc.getFlows().get(segment.getId());
            List<DepthPiece> pieces = cut(segment, dn, flow == null ? 0 : flow, local, extraNodes);
            if (pieces.size() > 1 || depthChanged(pieces)) {
                replacements.put(segment.getId(), pieces);
            }
        }
    }

    private void traceSingle(NewSegment segment, NetworkTreeLayout layout, TreeCalcResult calc,
                             List<DepthObstacle> obstacles, Map<String, List<DepthPiece>> replacements,
                             List<DepthExtraNode> extraNodes) {
        LineString line = layout.segmentLine(segment.getId());
        Integer dn = calc.getDiameters().get(segment.getId());
        Double flow = calc.getFlows().get(segment.getId());
        if (line == null || dn == null) {
            return;
        }
        List<DepthSpan> spans;
        try {
            spans = solver.solve(line, dn, obstacles);
        } catch (RuntimeException ex) {
            log.warn("Профиль глубины участка {}: {}", segment.getId(), ex.getMessage());
            return;
        }
        if (spans == null || spans.isEmpty()) {
            return;
        }
        List<DepthPiece> pieces = cut(segment, dn, flow == null ? 0 : flow, spans, extraNodes);
        if (pieces.size() > 1 || depthChanged(pieces)) {
            replacements.put(segment.getId(), pieces);
        }
    }

    /** Линия участка в направлении «от узла from к узлу to». */
    private static LineString oriented(NetworkTreeLayout layout, NewSegment segment) {
        LineString line = layout.segmentLine(segment.getId());
        if (line == null || line.getNumPoints() < 2) {
            return null;
        }
        Coordinate from = layout.nodeCoordinate(segment.getFromNodeId());
        if (from != null && line.getCoordinateN(0).distance(from)
                > line.getCoordinateN(line.getNumPoints() - 1).distance(from) + 1e-6) {
            return (LineString) line.reverse();
        }
        return line;
    }

    /** Части профиля цепочки в пределах [from, to] (по длине цепочки) — в координатах участка. */
    private static List<DepthSpan> clip(List<DepthSpan> spans, double from, double to, LineString segmentLine) {
        List<DepthSpan> out = new ArrayList<>();
        org.locationtech.jts.linearref.LengthIndexedLine indexed =
                new org.locationtech.jts.linearref.LengthIndexedLine(segmentLine);
        double segLen = segmentLine.getLength();
        double scale = to - from > 1e-9 ? segLen / (to - from) : 1.0;
        for (DepthSpan span : spans) {
            double a = Math.max(from, span.getChainageStartM());
            double b = Math.min(to, span.getChainageEndM());
            if (b - a <= 1e-6) {
                continue;
            }
            double da = interpolate(span, a);
            double db = interpolate(span, b);
            double la = (a - from) * scale;
            double lb = (b - from) * scale;
            LineString piece = subLine(indexed, la, lb, segmentLine);
            String note = Math.abs(da - db) < 1e-6 ? span.getNote() : null;
            out.add(new DepthSpan(la, lb, da, db, piece, note));
        }
        return out;
    }

    private static double interpolate(DepthSpan span, double chainage) {
        double len = span.lengthM();
        if (len <= 1e-9) {
            return span.getDepthStartM();
        }
        double t = (chainage - span.getChainageStartM()) / len;
        return span.getDepthStartM() + (span.getDepthEndM() - span.getDepthStartM()) * t;
    }

    private static LineString subLine(org.locationtech.jts.linearref.LengthIndexedLine indexed, double from,
                                      double to, LineString base) {
        Geometry extracted = indexed.extractLine(from, to);
        if (extracted instanceof LineString && extracted.getNumPoints() >= 2) {
            return (LineString) extracted;
        }
        Coordinate a = indexed.extractPoint(from);
        Coordinate b = indexed.extractPoint(to);
        return base.getFactory().createLineString(new Coordinate[] {new Coordinate(a), new Coordinate(b)});
    }

    private boolean depthChanged(List<DepthPiece> pieces) {
        double normal = reference.getDepth().getNormalDepthM();
        for (DepthPiece piece : pieces) {
            // AUDIT-24.09 (Claude): участок целиком на площадке (например, спецпроход 4 м над газом на 2,375 м)
            // тоже изменён — раньше такой участок не заменялся и оставался на 3,0 м.
            if (Math.abs(piece.getDepthStartM() - normal) > 1e-6 || Math.abs(piece.getDepthEndM() - normal) > 1e-6) {
                return true;
            }
            if (Math.abs(piece.getKDepth() - 1.0) > 1e-9) {
                return true;
            }
            if (Math.abs(piece.getDepthStartM() - piece.getDepthEndM()) > 1e-6) {
                return true;
            }
        }
        return pieces.size() > 1;
    }

    private List<DepthPiece> cut(NewSegment segment, int dn, double flow, List<DepthSpan> spans,
                                 List<DepthExtraNode> extraNodes) {
        double geom = 0;
        for (DepthSpan span : spans) {
            geom += span.lengthM();
        }
        double scale = geom > 1e-9 ? segment.getLengthM() / geom : 1.0;
        List<DepthPiece> pieces = new ArrayList<>();
        double consumed = 0;
        String prev = segment.getFromNodeId();
        for (int i = 0; i < spans.size(); i++) {
            DepthSpan span = spans.get(i);
            boolean last = i == spans.size() - 1;
            String next = last ? segment.getToNodeId() : "tn_d_" + segment.getId() + "_" + (i + 1);
            double length = last ? segment.getLengthM() - consumed : span.lengthM() * scale;
            if (!(length > 0)) {
                continue;
            }
            consumed += length;
            double k = coefficient.average(span.getDepthStartM(), span.getDepthEndM());
            long rub = segmentCost.cost(length, dn, k, segment.getKSpec());
            String id = spans.size() == 1 ? segment.getId() : segment.getId() + "_d" + (i + 1);
            pieces.add(new DepthPiece(id, prev, next, span.getLineUtm(), length,
                    span.getDepthStartM(), span.getDepthEndM(), k, segment.getKSpec(),
                    segment.getLayingMethod(), dn, flow, rub, span.getNote()));
            if (!last && span.getLineUtm() != null && span.getLineUtm().getNumPoints() > 0) {
                Coordinate end = span.getLineUtm().getCoordinateN(span.getLineUtm().getNumPoints() - 1);
                extraNodes.add(new DepthExtraNode(next, new Coordinate(end.x, end.y),
                        "Граница участка глубины"));
            }
            prev = next;
        }
        return pieces;
    }

    private VariantSummary rebuild(NetworkPlan plan, VariantCost cost, Map<String, List<DepthPiece>> replacements) {
        VariantSummary.Builder builder = VariantSummary.builder(cost.getVariantId());
        for (NewNetworkTree tree : plan.getTrees()) {
            TreeCalcResult calc = calcOf(cost.getEngineering(), tree);
            if (calc == null) {
                continue;
            }
            for (NewSegment segment : tree.segmentsTopDown()) {
                List<DepthPiece> pieces = replacements.get(segment.getId());
                if (pieces != null) {
                    for (DepthPiece piece : pieces) {
                        builder.addNewSegment(piece.getLengthM(), piece.getCostRub());
                    }
                } else {
                    Long rub = cost.getSegmentCosts().get(segment.getId());
                    builder.addNewSegment(segment.getLengthM(), rub == null ? 0L : rub);
                }
            }
        }
        for (Long rub : cost.getNewChamberCosts().values()) {
            builder.addNewChamber(rub);
        }
        for (Long rub : cost.getTieInCosts().values()) {
            builder.addTieIn(rub);
        }
        for (Map.Entry<String, Long> penalty : cost.getPenalties().entrySet()) {
            builder.addUnconnected(penalty.getKey(), penalty.getValue());
        }
        int rank = cost.getSummary().getRank();
        VariantSummary summary = builder.build(scoreCalculator);
        return rank > 0 ? summary.withRank(rank) : summary;
    }

    private static TreeCalcResult calcOf(EngineeringResult engineering, NewNetworkTree tree) {
        for (TreeCalcResult result : engineering.getTrees()) {
            if (result.getTree().getTieIn().getId().equals(tree.getTieIn().getId())) {
                return result;
            }
        }
        return null;
    }

    private static List<DepthObstacle> filter(List<DepthObstacle> obstacles, String skipId) {
        if (obstacles == null || obstacles.isEmpty()) {
            return Collections.emptyList();
        }
        if (skipId == null) {
            return obstacles;
        }
        List<DepthObstacle> filtered = new ArrayList<>();
        for (DepthObstacle obstacle : obstacles) {
            if (!skipId.equals(obstacle.getId())) {
                filtered.add(obstacle);
            }
        }
        return filtered;
    }

    private Geometry toUtm(Geometry wgs) {
        try {
            Geometry copy = wgs.copy();
            copy.apply(new CoordinateFilter() {
                @Override
                public void filter(Coordinate coord) {
                    Coordinate utm = projection.toUtm(coord.x, coord.y);
                    coord.x = utm.x;
                    coord.y = utm.y;
                }
            });
            return copy;
        } catch (RuntimeException ex) {
            log.warn("Не удалось перепроецировать препятствие: {}", ex.getMessage());
            return null;
        }
    }

    private static int intProperty(Map<String, Object> properties, String key) {
        if (properties == null) {
            return 0;
        }
        Object value = properties.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
