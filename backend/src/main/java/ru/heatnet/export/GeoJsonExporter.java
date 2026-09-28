package ru.heatnet.export;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;

import ru.heatnet.calc.ChamberSizing;
import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.TreeCalcResult;
import ru.heatnet.calc.model.ExistingObjectType;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.NodeKind;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.cost.VariantSummary;
import ru.heatnet.depth.DepthExtraNode;
import ru.heatnet.depth.DepthOutcome;
import ru.heatnet.depth.DepthPiece;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.network.NetworkPlan;
import ru.heatnet.network.NetworkTreeLayout;

/**
 * M8. Потоковая запись result.geojson по §7.1 приложения 19.09.
 * Типы: новые heat_network, новые heat_chamber, technical_node, variant_summary.
 * Координаты [lon, lat]. Z не пишется: вертикаль задаётся depth_start/depth_end только в режиме M11.
 */
@Component
public class GeoJsonExporter {

    private final ProjectionService projection;
    private final JsonFactory jsonFactory = new JsonFactory();

    public GeoJsonExporter(ProjectionService projection) {
        this.projection = projection;
    }

    public Map<String, Map<String, Object>> write(OutputStream out, List<ExportVariant> variants, ExportIds ids,
                                                  boolean includeDepth, double normalDepthM) throws IOException {
        Map<String, Map<String, Object>> explanations = new LinkedHashMap<>();
        try (JsonGenerator g = jsonFactory.createGenerator(out, JsonEncoding.UTF8)) {
            g.setPrettyPrinter(new DefaultPrettyPrinter());
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");
            g.writeArrayFieldStart("features");
            if (variants != null) {
                for (ExportVariant variant : variants) {
                    writeVariant(g, variant, ids, includeDepth, normalDepthM, explanations);
                }
            }
            g.writeEndArray();
            g.writeEndObject();
        }
        return explanations;
    }

    private void writeVariant(JsonGenerator g, ExportVariant variant, ExportIds ids, boolean includeDepth,
                              double normalDepthM, Map<String, Map<String, Object>> explanations) throws IOException {
        NetworkPlan plan = variant.getPlan();
        EngineeringResult engineering = variant.getEngineering();
        VariantSummary summary = summaryOf(variant);
        String variantId = variant.getCost().getVariantId();
        DepthOutcome depth = variant.getDepth();
        Map<String, Coordinate> extraUtm = extraCoordinates(depth);

        List<NewNetworkTree> trees = plan.getTrees();
        List<NetworkTreeLayout> layouts = plan.getLayouts();
        for (int i = 0; i < trees.size(); i++) {
            NewNetworkTree tree = trees.get(i);
            NetworkTreeLayout layout = i < layouts.size() ? layouts.get(i) : new NetworkTreeLayout();
            TreeCalcResult calc = calcOf(engineering, tree);
            for (NewSegment segment : tree.segmentsTopDown()) {
                List<DepthPiece> pieces = depth == null ? null : depth.piecesOf(segment.getId());
                if (pieces != null && !pieces.isEmpty()) {
                    for (DepthPiece piece : pieces) {
                        writeNetwork(g, piece.getId(), variantId, piece.getFromNodeId(), piece.getToNodeId(),
                                tree, layout, extraUtm, ids, piece.getLineUtm(), piece.getLengthM(),
                                piece.getDiameterMm(), piece.getFlowTph(), piece.getLayingMethod().getCode(),
                                piece.getKSpec(), piece.getKDepth(), piece.getCostRub(),
                                includeDepth, piece.getDepthStartM(), piece.getDepthEndM(), piece.getNote(),
                                explanations);
                    }
                } else if (calc != null) {
                    Integer dn = calc.getDiameters().get(segment.getId());
                    Double flow = calc.getFlows().get(segment.getId());
                    Long rub = variant.getCost().getSegmentCosts().get(segment.getId());
                    LineString line = layout.segmentLine(segment.getId());
                    writeNetwork(g, segment.getId(), variantId, segment.getFromNodeId(), segment.getToNodeId(),
                            tree, layout, extraUtm, ids, line, segment.getLengthM(),
                            dn == null ? 0 : dn, flow == null ? 0 : flow, segment.getLayingMethod().getCode(),
                            segment.getKSpec(), segment.getKDepth(), rub == null ? 0L : rub,
                            includeDepth, normalDepthM, normalDepthM, null, explanations);
                }
            }
            writeNodes(g, tree, layout, extraUtm, ids, variantId, variant, explanations);
        }
        if (depth != null) {
            for (DepthExtraNode node : depth.getExtraNodes()) {
                writePointFeature(g, node.getId(), "technical_node", variantId, node.getUtm(), explanations,
                        node.getNote() == null ? "Технический узел профиля глубины" : node.getNote(), null);
            }
        }
        writeSummary(g, summary, ids, explanations);
    }

    private void writeNetwork(JsonGenerator g, String id, String variantId, String fromId, String toId,
                              NewNetworkTree tree, NetworkTreeLayout layout, Map<String, Coordinate> extraUtm,
                              ExportIds ids, LineString line, double lengthM, int diameter, double flow,
                              String laying, double kSpec, double kDepth, long costRub, boolean includeDepth,
                              double depthStart, double depthEnd, String note,
                              Map<String, Map<String, Object>> explanations) throws IOException {
        Object startId = resolveNode(fromId, tree, ids, extraUtm, variantId);
        Object endId = resolveNode(toId, tree, ids, extraUtm, variantId);
        Coordinate startUtm = utmOf(fromId, tree, layout, extraUtm);
        Coordinate endUtm = utmOf(toId, tree, layout, extraUtm);
        id = outId(variantId, id);
        g.writeStartObject();
        g.writeStringField("type", "Feature");
        writeTypedId(g, "id", id);
        g.writeFieldName("geometry");
        writeLine(g, line, startUtm, endUtm);
        g.writeObjectFieldStart("properties");
        writeTypedId(g, "id", id);
        g.writeStringField("object_type", "heat_network");
        g.writeStringField("variant_id", variantId);
        writeTypedId(g, "start_node_id", startId);
        writeTypedId(g, "end_node_id", endId);
        g.writeNumberField("flow_tph", flow);
        g.writeNumberField("diameter", diameter);
        g.writeNumberField("length", lengthM);
        g.writeStringField("laying_method", laying);
        if (includeDepth) {
            g.writeNumberField("depth_start", depthStart);
            g.writeNumberField("depth_end", depthEnd);
        } else {
            g.writeNullField("depth_start");
            g.writeNullField("depth_end");
        }
        g.writeNumberField("cost", costRub);
        g.writeNumberField("construction_cost", costRub);
        g.writeNumberField("k_spec", kSpec);
        g.writeEndObject();
        g.writeEndObject();

        Map<String, Object> explain = new LinkedHashMap<>();
        explain.put("objectId", id);
        explain.put("objectType", "heat_network");
        explain.put("variantId", variantId);
        explain.put("lengthM", lengthM);
        explain.put("diameter", diameter);
        explain.put("flowTph", flow);
        explain.put("layingMethod", laying);
        explain.put("kSpec", kSpec);
        explain.put("kDepth", kDepth);
        explain.put("costRub", costRub);
        if (includeDepth) {
            explain.put("depthStartM", depthStart);
            explain.put("depthEndM", depthEnd);
        }
        explain.put("rule", networkRule(laying, kSpec, kDepth, includeDepth, note));
        explanations.put(id, explain);
    }

    private void writeNodes(JsonGenerator g, NewNetworkTree tree, NetworkTreeLayout layout,
                            Map<String, Coordinate> extraUtm, ExportIds ids, String variantId,
                            ExportVariant variant, Map<String, Map<String, Object>> explanations) throws IOException {
        for (NewNode node : tree.getNodes().values()) {
            if (node.getKind() == NodeKind.TECHNICAL_NODE) {
                Coordinate utm = layout.nodeCoordinate(node.getId());
                writePointFeature(g, node.getId(), "technical_node", variantId, utm, explanations,
                        "Технический узел: граница спецпрохода или смена параметра участка", null);
            }
        }
        if (variant.getEngineering() == null) {
            return;
        }
        for (ChamberSizing chamber : variant.getEngineering().getNewChambers()) {
            if (!belongs(tree, chamber.getChamberId())) {
                continue;
            }
            Coordinate utm = chamberCoordinate(tree, layout, chamber.getChamberId());
            Long rub = variant.getCost().getNewChamberCosts().get(chamber.getChamberId());
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("diameter", chamber.getRequiredDiameter());
            if (rub != null) {
                extra.put("cost", rub);
            }
            writePointFeature(g, chamber.getChamberId(), "heat_chamber", variantId, utm, explanations,
                    "Новая камера, DN" + chamber.getRequiredDiameter()
                            + ". Стоимость камеры включает присоединение к сети.", extra);
        }
    }

    private void writePointFeature(JsonGenerator g, String id, String objectType, String variantId, Coordinate utm,
                                   Map<String, Map<String, Object>> explanations, String rule,
                                   Map<String, Object> extraProps) throws IOException {
        id = outId(variantId, id);
        g.writeStartObject();
        g.writeStringField("type", "Feature");
        writeTypedId(g, "id", id);
        g.writeFieldName("geometry");
        g.writeStartObject();
        g.writeStringField("type", "Point");
        g.writeFieldName("coordinates");
        writeLonLat(g, utm);
        g.writeEndObject();
        g.writeObjectFieldStart("properties");
        writeTypedId(g, "id", id);
        g.writeStringField("object_type", objectType);
        g.writeStringField("variant_id", variantId);
        if (extraProps != null) {
            for (Map.Entry<String, Object> entry : extraProps.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Integer) {
                    g.writeNumberField(entry.getKey(), (Integer) value);
                } else if (value instanceof Long) {
                    g.writeNumberField(entry.getKey(), (Long) value);
                } else if (value instanceof Double) {
                    g.writeNumberField(entry.getKey(), (Double) value);
                } else if (value != null) {
                    g.writeStringField(entry.getKey(), String.valueOf(value));
                }
            }
        }
        g.writeEndObject();
        g.writeEndObject();

        Map<String, Object> explain = new LinkedHashMap<>();
        explain.put("objectId", id);
        explain.put("objectType", objectType);
        explain.put("variantId", variantId);
        explain.put("rule", rule);
        if (extraProps != null) {
            explain.putAll(extraProps);
        }
        explanations.put(id, explain);
    }

    private void writeSummary(JsonGenerator g, VariantSummary summary, ExportIds ids,
                              Map<String, Map<String, Object>> explanations) throws IOException {
        int rank = summary.getRank() < 1 ? 1 : summary.getRank();
        String id = summary.getId();
        g.writeStartObject();
        g.writeStringField("type", "Feature");
        writeTypedId(g, "id", id);
        g.writeNullField("geometry");
        g.writeObjectFieldStart("properties");
        g.writeStringField("id", id);
        g.writeStringField("object_type", "variant_summary");
        g.writeStringField("variant_id", summary.getVariantId());
        g.writeNumberField("rank", rank);
        g.writeNumberField("construction_cost", summary.getConstructionCost());
        g.writeNumberField("chamber_construction_cost", summary.getChamberConstructionCost());
        g.writeNumberField("existing_chamber_tie_in_count", summary.getExistingChamberTieInCount());
        g.writeNumberField("existing_chamber_tie_in_cost", summary.getExistingChamberTieInCost());
        g.writeNumberField("unconnected_penalty", summary.getUnconnectedPenalty());
        g.writeNumberField("calculated_cost", summary.getCalculatedCost());
        g.writeNumberField("new_network_length", summary.getNewNetworkLength());
        g.writeNumberField("score", roundedScore(summary.getScore()));
        g.writeArrayFieldStart("unconnected_oks_ids");
        for (String oksId : summary.getUnconnectedOksIds()) {
            writeTypedValue(g, ids.of(oksId));
        }
        g.writeEndArray();
        g.writeEndObject();
        g.writeEndObject();

        Map<String, Object> explain = new LinkedHashMap<>();
        explain.put("objectId", id);
        explain.put("objectType", "variant_summary");
        explain.put("variantId", summary.getVariantId());
        explain.put("rank", rank);
        explain.put("constructionCost", summary.getConstructionCost());
        explain.put("calculatedCost", summary.getCalculatedCost());
        explain.put("newNetworkLength", summary.getNewNetworkLength());
        explain.put("score", summary.getScore());
        explain.put("rule", "S = 0,7·(C/25 000 000) + 0,3·(L/100), округление до 4 знаков HALF_UP. "
                + "Реконструкция в C и L не входит.");
        explanations.put(id, explain);
    }

    private void writeLine(JsonGenerator g, LineString line, Coordinate startUtm, Coordinate endUtm) throws IOException {
        g.writeStartObject();
        g.writeStringField("type", "LineString");
        g.writeArrayFieldStart("coordinates");
        List<Coordinate> coords = new ArrayList<>();
        if (line != null) {
            for (Coordinate coordinate : line.getCoordinates()) {
                coords.add(coordinate);
            }
        }
        if (coords.isEmpty()) {
            if (startUtm != null) {
                coords.add(startUtm);
            }
            if (endUtm != null) {
                coords.add(endUtm);
            }
        }
        if (!coords.isEmpty() && startUtm != null) {
            coords.set(0, startUtm);
        }
        if (coords.size() >= 1 && endUtm != null) {
            coords.set(coords.size() - 1, endUtm);
        }
        if (coords.size() < 2 && startUtm != null && endUtm != null) {
            coords.clear();
            coords.add(startUtm);
            coords.add(endUtm);
        }
        for (Coordinate coordinate : coords) {
            writeLonLat(g, coordinate);
        }
        g.writeEndArray();
        g.writeEndObject();
    }

    private void writeLonLat(JsonGenerator g, Coordinate utm) throws IOException {
        Coordinate wgs = utm == null ? new Coordinate(0, 0) : projection.toWgs(utm.x, utm.y);
        g.writeStartArray();
        g.writeNumber(wgs.x);
        g.writeNumber(wgs.y);
        g.writeEndArray();
    }

    private Object resolveNode(String nodeId, NewNetworkTree tree, ExportIds ids, Map<String, Coordinate> extraUtm,
                               String variantId) {
        if (extraUtm.containsKey(nodeId)) {
            return outId(variantId, nodeId);
        }
        NewNode node = tree.getNodes().get(nodeId);
        if (node == null) {
            return outId(variantId, nodeId);
        }
        if (node.getKind() == NodeKind.OKS_CONNECTION) {
            return ids.of(node.getOksId());
        }
        TieInPoint tie = tree.getTieIn();
        if (node.getKind() == NodeKind.TIE_IN && tie.getExistingObjectType() == ExistingObjectType.HEAT_CHAMBER) {
            return ids.of(tie.getExistingObjectId());
        }
        return outId(variantId, externalId(node, tie));
    }

    /**
     * AUDIT-24.09 (Claude). §7.2: id — «уникальный идентификатор выходного объекта» в пределах файла.
     * Внутренние id новых участков/камер/узлов генерируются планировщиком и повторялись в разных
     * вариантах (в одном файле встречались одинаковые nch_tie_p_* у vA и vB). Новые объекты получают
     * префикс варианта (как в примере §7.3: «v1_net_1»); ссылки на входные объекты (точки ОКС,
     * существующие камеры) остаются исходными id с исходным JSON-типом.
     */
    static String outId(String variantId, String internalId) {
        if (internalId == null) {
            return null;
        }
        String prefix = variantId + "_";
        return internalId.startsWith(prefix) ? internalId : prefix + internalId;
    }

    private Coordinate utmOf(String nodeId, NewNetworkTree tree, NetworkTreeLayout layout,
                             Map<String, Coordinate> extraUtm) {
        Coordinate extra = extraUtm.get(nodeId);
        if (extra != null) {
            return extra;
        }
        return layout.nodeCoordinate(nodeId);
    }

    private static String externalId(NewNode node, TieInPoint tie) {
        if (node.getKind() == NodeKind.OKS_CONNECTION) {
            return node.getOksId();
        }
        if (node.getKind() == NodeKind.TIE_IN) {
            if (tie.getExistingObjectType() == ExistingObjectType.HEAT_CHAMBER) {
                return tie.getExistingObjectId();
            }
            if (tie.getNewChamberId() != null) {
                return tie.getNewChamberId();
            }
        }
        return node.getId();
    }

    private static Coordinate chamberCoordinate(NewNetworkTree tree, NetworkTreeLayout layout, String chamberId) {
        Coordinate direct = layout.nodeCoordinate(chamberId);
        if (direct != null) {
            return direct;
        }
        TieInPoint tie = tree.getTieIn();
        if (chamberId.equals(tie.getNewChamberId())) {
            return layout.nodeCoordinate(tie.getId());
        }
        return layout.nodeCoordinate(tie.getId());
    }

    private static boolean belongs(NewNetworkTree tree, String chamberId) {
        if (tree.getNodes().containsKey(chamberId)) {
            return tree.node(chamberId).getKind() == NodeKind.NEW_CHAMBER;
        }
        return chamberId.equals(tree.getTieIn().getNewChamberId());
    }

    private static Map<String, Coordinate> extraCoordinates(DepthOutcome depth) {
        Map<String, Coordinate> map = new LinkedHashMap<>();
        if (depth == null) {
            return map;
        }
        for (DepthExtraNode node : depth.getExtraNodes()) {
            map.put(node.getId(), node.getUtm());
        }
        return map;
    }

    private static VariantSummary summaryOf(ExportVariant variant) {
        if (variant.getDepth() != null && variant.getDepth().getSummary() != null) {
            return variant.getDepth().getSummary();
        }
        return variant.getCost().getSummary();
    }

    private static TreeCalcResult calcOf(EngineeringResult engineering, NewNetworkTree tree) {
        if (engineering == null) {
            return null;
        }
        for (TreeCalcResult result : engineering.getTrees()) {
            if (result.getTree().getTieIn().getId().equals(tree.getTieIn().getId())) {
                return result;
            }
        }
        return null;
    }

    private static String networkRule(String laying, double kSpec, double kDepth, boolean depth, String note) {
        StringBuilder text = new StringBuilder();
        if ("special".equals(laying)) {
            text.append("Спецпроход, Kспец=").append(kSpec).append(". ");
        } else {
            text.append("Обычная прокладка, Kспец=1. ");
        }
        if (depth) {
            text.append("Kгл=").append(kDepth).append(". ");
            if (note != null && !note.isEmpty()) {
                text.append(note);
            } else {
                text.append("Глубина верха габарита 3,0 м.");
            }
        } else {
            text.append("Режим 2D, Kгл=1.");
        }
        return text.toString();
    }

    private static BigDecimal roundedScore(double score) {
        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
    }

    private static void writeTypedId(JsonGenerator g, String field, Object id) throws IOException {
        g.writeFieldName(field);
        writeTypedValue(g, id);
    }

    private static void writeTypedValue(JsonGenerator g, Object id) throws IOException {
        if (id instanceof Integer || id instanceof Long || id instanceof Short) {
            g.writeNumber(((Number) id).longValue());
        } else if (id instanceof java.math.BigInteger) {
            g.writeNumber((java.math.BigInteger) id);
        } else if (id instanceof java.math.BigDecimal) {
            g.writeNumber((java.math.BigDecimal) id);
        } else if (id instanceof Number) {
            g.writeNumber(((Number) id).doubleValue());
        } else if (id == null) {
            g.writeString("");
        } else {
            g.writeString(String.valueOf(id));
        }
    }
}
