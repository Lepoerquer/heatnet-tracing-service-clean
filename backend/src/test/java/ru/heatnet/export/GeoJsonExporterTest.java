package ru.heatnet.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.model.ExistingChamber;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingSegment;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.cost.VariantCost;
import ru.heatnet.cost.VariantCostCalculator;
import ru.heatnet.depth.DepthOutcome;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.network.NetworkPlan;
import ru.heatnet.network.NetworkTreeLayout;

/**
 * Экспорт §7.1. Ручной контроль: расход 10,5 т/ч → DN80 (83 530 ₽/м),
 * два участка по 50 м = 8 353 000 ₽, врезка в существующую камеру 5 000 000 ₽,
 * C = 13 353 000, L = 100,
 * S = 0,7·(13 353 000/25 000 000) + 0,3·(100/100) = 0,673884 → 0,6739.
 */
class GeoJsonExporterTest {

    private final ProjectionService projection = new ProjectionService();
    private final GeoJsonExporter exporter = new GeoJsonExporter(projection);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("врезка в существующую камеру: сводка, узлы, без tie_in и без Z")
    void exportsChamberTieIn() throws Exception {
        ExistingNetwork network = network();
        Coordinate start = projection.toUtm(37.64, 55.70);
        Coordinate mid = new Coordinate(start.x + 50, start.y);
        Coordinate end = new Coordinate(start.x + 100, start.y);

        TieInPoint tie = TieInPoint.intoChamber("tie_c_2", "2");
        NewNetworkTree tree = new NewNetworkTree(tie, Arrays.asList(
                NewNode.tieIn("tie_c_2"),
                NewNode.technical("tn_1"),
                NewNode.oks("oks_4", "4", 10.5)),
                Arrays.asList(
                        NewSegment.base("seg_a", "tie_c_2", "tn_1", 50),
                        NewSegment.base("seg_b", "tn_1", "oks_4", 50)));
        NetworkTreeLayout layout = new NetworkTreeLayout();
        layout.putNode("tie_c_2", start);
        layout.putNode("tn_1", mid);
        layout.putNode("oks_4", end);
        layout.putSegment("seg_a", line(start, mid));
        layout.putSegment("seg_b", line(mid, end));

        EngineeringResult engineering = new EngineeringCalculator(TestReference.get())
                .calculate(Collections.singletonList(tree), network);
        VariantCost cost = new VariantCostCalculator(TestReference.get())
                .calculate("1", engineering, Collections.emptyList());
        cost = cost.withSummary(cost.getSummary().withRank(1));

        assertEquals(13_353_000L, cost.getSummary().getConstructionCost());
        assertEquals(0.6739, cost.getSummary().getScore(), 1e-9);

        NetworkPlan plan = new NetworkPlan(Collections.singletonList(tree), Collections.singletonList(layout),
                Collections.emptyList(), false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exporter.write(out, Collections.singletonList(
                new ExportVariant(plan, engineering, cost, DepthOutcome.unchanged())),
                ExportIds.of("2", 2L, "4", 4L), false, 3.0);

        JsonNode root = json.readTree(out.toByteArray());
        assertEquals("FeatureCollection", root.get("type").asText());
        int networks = 0;
        int technical = 0;
        int summaries = 0;
        int chambers = 0;
        JsonNode summary = null;
        for (JsonNode feature : root.get("features")) {
            JsonNode props = feature.get("properties");
            assertNoNullProps(props);
            String type = props.get("object_type").asText();
            assertFalse("tie_in".equals(type));
            assertFalse(type.contains("reconstruction"));
            if ("variant_summary".equals(type)) {
                summaries++;
                summary = feature;
                assertTrue(feature.get("geometry").isNull());
            } else {
                assertFalse(feature.get("geometry").isNull());
                assertNoZ(feature.get("geometry").get("coordinates"));
            }
            if ("heat_network".equals(type)) {
                // §5 / §7.2: в базовом 2D-режиме глубины передаются null, атрибуты обязательны
                assertTrue(props.has("depth_start"));
                assertTrue(props.has("depth_end"));
                assertTrue(props.get("depth_start").isNull());
                assertTrue(props.get("depth_end").isNull());
            } else {
                assertFalse(props.has("depth_start"));
            }
            if ("heat_network".equals(type)) {
                networks++;
                assertEquals("base", props.get("laying_method").asText());
                assertEquals(80, props.get("diameter").asInt());
            } else if ("technical_node".equals(type)) {
                technical++;
                // AUDIT-24.09: id новых объектов уникальны в пределах FeatureCollection — префикс варианта
                assertEquals("1_tn_1", props.get("id").asText());
            } else if ("heat_chamber".equals(type)) {
                chambers++;
            }
        }
        assertEquals(2, networks);
        assertEquals(1, technical);
        assertEquals(1, summaries);
        assertEquals(0, chambers);
        assertEquals(1, summary.get("properties").get("rank").asInt());
        assertEquals(13_353_000L, summary.get("properties").get("calculated_cost").asLong());
        assertEquals(5_000_000L, summary.get("properties").get("existing_chamber_tie_in_cost").asLong());
        assertEquals(1, summary.get("properties").get("existing_chamber_tie_in_count").asInt());
        assertEquals(0.6739, summary.get("properties").get("score").asDouble(), 1e-9);
        assertTrue(summary.get("properties").get("unconnected_oks_ids").isArray());
        assertEquals(0, summary.get("properties").get("unconnected_oks_ids").size());

        JsonNode fromChamber = networkWithStart(root, 2);
        assertTrue(fromChamber.get("properties").get("start_node_id").isNumber());
        JsonNode toOks = networkWithEnd(root, 4);
        assertTrue(toOks.get("properties").get("end_node_id").isNumber());
        assertSharedNode(root, "1_tn_1");
    }

    @Test
    @DisplayName("врезка в трубу: новая камера без доплаты 5 млн и без объекта tie_in")
    void exportsNewChamberWithoutTieInFeature() throws Exception {
        ExistingNetwork network = network();
        Coordinate start = projection.toUtm(37.64, 55.70);
        Coordinate end = new Coordinate(start.x + 40, start.y);
        TieInPoint tie = TieInPoint.intoPipe("tie_p_3", "3", 5.0, "nch_tie_p_3");
        NewNetworkTree tree = new NewNetworkTree(tie, Arrays.asList(
                NewNode.tieIn("tie_p_3"),
                NewNode.oks("oks_4", "4", 10.5)),
                Collections.singletonList(NewSegment.base("seg_1", "tie_p_3", "oks_4", 40)));
        NetworkTreeLayout layout = new NetworkTreeLayout();
        layout.putNode("tie_p_3", start);
        layout.putNode("oks_4", end);
        layout.putSegment("seg_1", line(start, end));

        EngineeringResult engineering = new EngineeringCalculator(TestReference.get())
                .calculate(Collections.singletonList(tree), network);
        VariantCost cost = new VariantCostCalculator(TestReference.get())
                .calculate("1", engineering, Collections.emptyList());
        cost = cost.withSummary(cost.getSummary().withRank(1));

        assertEquals(0, cost.getSummary().getExistingChamberTieInCount());
        // Новая камера: max(DN нового участка 80, DN существующей трубы 400) = 400 → 5 млн.
        // Отдельные 5 млн за врезку не добавляются.
        assertEquals(5_000_000L, cost.getSummary().getChamberConstructionCost());
        assertEquals(5_000_000L, cost.getSummary().getConstructionCost() - cost.getSummary().getSegmentCost());

        NetworkPlan plan = new NetworkPlan(Collections.singletonList(tree), Collections.singletonList(layout),
                Collections.emptyList(), false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exporter.write(out, Collections.singletonList(
                new ExportVariant(plan, engineering, cost, DepthOutcome.unchanged())),
                ExportIds.of("4", 4L, "3", 3L), false, 3.0);
        JsonNode root = json.readTree(out.toByteArray());

        JsonNode chamber = null;
        JsonNode segment = null;
        for (JsonNode feature : root.get("features")) {
            String type = feature.get("properties").get("object_type").asText();
            assertFalse("tie_in".equals(type));
            if ("heat_chamber".equals(type)) {
                chamber = feature;
            } else if ("heat_network".equals(type)) {
                segment = feature;
            }
        }
        assertTrue(chamber != null);
        // AUDIT-24.09: id новой камеры с префиксом варианта (уникальность между вариантами)
        assertEquals("1_nch_tie_p_3", chamber.get("properties").get("id").asText());
        assertEquals(400, chamber.get("properties").get("diameter").asInt());
        assertEquals(5_000_000L, chamber.get("properties").get("cost").asLong());
        assertEquals("1_nch_tie_p_3", segment.get("properties").get("start_node_id").asText());
        assertEquals(chamber.get("geometry").get("coordinates").get(0).asDouble(),
                segment.get("geometry").get("coordinates").get(0).get(0).asDouble(), 1e-9);
        assertEquals(chamber.get("geometry").get("coordinates").get(1).asDouble(),
                segment.get("geometry").get("coordinates").get(0).get(1).asDouble(), 1e-9);
    }

    private static ExistingNetwork network() {
        return new ExistingNetwork(
                Collections.singletonList(new ExistingSegment("3", 400, null, "1", 20)),
                Collections.singletonList(new ExistingChamber("2", null, "1")),
                Collections.singletonList("1"));
    }

    private LineString line(Coordinate a, Coordinate b) {
        return projection.utmFactory().createLineString(new Coordinate[] {a, b});
    }

    private static void assertNoNullProps(JsonNode props) {
        Iterator<String> names = props.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if ("depth_start".equals(name) || "depth_end".equals(name)) {
                continue;
            }
            assertFalse(props.get(name).isNull(), name);
        }
    }

    private static void assertNoZ(JsonNode coordinates) {
        if (coordinates.isArray() && coordinates.size() > 0 && coordinates.get(0).isNumber()) {
            assertEquals(2, coordinates.size());
            return;
        }
        for (JsonNode child : coordinates) {
            assertNoZ(child);
        }
    }

    private static JsonNode networkWithStart(JsonNode root, int start) {
        for (JsonNode feature : root.get("features")) {
            JsonNode props = feature.get("properties");
            if ("heat_network".equals(props.get("object_type").asText())
                    && props.get("start_node_id").asInt() == start) {
                return feature;
            }
        }
        throw new AssertionError("нет участка от " + start);
    }

    private static JsonNode networkWithEnd(JsonNode root, int end) {
        for (JsonNode feature : root.get("features")) {
            JsonNode props = feature.get("properties");
            if ("heat_network".equals(props.get("object_type").asText())
                    && props.get("end_node_id").asInt() == end) {
                return feature;
            }
        }
        throw new AssertionError("нет участка к " + end);
    }

    private static void assertSharedNode(JsonNode root, String nodeId) {
        JsonNode point = null;
        for (JsonNode feature : root.get("features")) {
            if (nodeId.equals(feature.get("properties").get("id").asText())
                    && "technical_node".equals(feature.get("properties").get("object_type").asText())) {
                point = feature.get("geometry").get("coordinates");
            }
        }
        assertTrue(point != null);
        int matches = 0;
        for (JsonNode feature : root.get("features")) {
            if (!"heat_network".equals(feature.get("properties").get("object_type").asText())) {
                continue;
            }
            JsonNode props = feature.get("properties");
            JsonNode coords = feature.get("geometry").get("coordinates");
            if (nodeId.equals(props.get("end_node_id").asText())) {
                assertClose(point, coords.get(coords.size() - 1));
                matches++;
            }
            if (nodeId.equals(props.get("start_node_id").asText())) {
                assertClose(point, coords.get(0));
                matches++;
            }
        }
        assertEquals(2, matches);
    }

    private static void assertClose(JsonNode a, JsonNode b) {
        assertEquals(a.get(0).asDouble(), b.get(0).asDouble(), 1e-9);
        assertEquals(a.get(1).asDouble(), b.get(1).asDouble(), 1e-9);
    }
}
