package ru.heatnet.depth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Collections;

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
import ru.heatnet.export.ExportIds;
import ru.heatnet.export.ExportVariant;
import ru.heatnet.export.GeoJsonExporter;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.network.NetworkPlan;
import ru.heatnet.network.NetworkTreeLayout;

/** M11 поверх готового участка: глубина в атрибутах, Z в геометрию не пишется. */
class DepthTracerTest {

    @Test
    @DisplayName("газопровод режет участок, depth_start/depth_end есть, Z нет, врезка не дублируется")
    void depthAttributesWithoutZ() throws Exception {
        ProjectionService projection = new ProjectionService();
        Coordinate start = projection.toUtm(37.64, 55.70);
        Coordinate end = new Coordinate(start.x + 100, start.y);
        Coordinate gasA = new Coordinate(start.x + 50, start.y - 5);
        Coordinate gasB = new Coordinate(start.x + 50, start.y + 5);

        TieInPoint tie = TieInPoint.intoChamber("tie_c_2", "2");
        NewNetworkTree tree = new NewNetworkTree(tie, java.util.Arrays.asList(
                NewNode.tieIn("tie_c_2"),
                NewNode.oks("oks_4", "4", 10.5)),
                Collections.singletonList(NewSegment.base("seg_1", "tie_c_2", "oks_4", 100)));
        NetworkTreeLayout layout = new NetworkTreeLayout();
        layout.putNode("tie_c_2", start);
        layout.putNode("oks_4", end);
        LineString route = projection.utmFactory().createLineString(new Coordinate[] {start, end});
        layout.putSegment("seg_1", route);

        ExistingNetwork network = new ExistingNetwork(
                Collections.singletonList(new ExistingSegment("3", 400, null, "1", 20)),
                Collections.singletonList(new ExistingChamber("2", null, "1")),
                Collections.singletonList("1"));
        EngineeringResult engineering = new EngineeringCalculator(TestReference.get())
                .calculate(Collections.singletonList(tree), network);
        VariantCost cost = new VariantCostCalculator(TestReference.get())
                .calculate("1", engineering, Collections.emptyList());
        cost = cost.withSummary(cost.getSummary().withRank(1));
        NetworkPlan plan = new NetworkPlan(Collections.singletonList(tree), Collections.singletonList(layout),
                Collections.emptyList(), false);

        LineString gas = projection.utmFactory().createLineString(new Coordinate[] {gasA, gasB});
        DepthTracer tracer = new DepthTracer(TestReference.get(), projection);
        DepthOutcome depth = tracer.trace(plan, engineering, cost,
                Collections.singletonList(DepthObstacle.utility("gas1", "gas_pipeline", gas, 0)));

        assertTrue(depth.hasReplacements());
        assertTrue(depth.getSummary() != null);
        assertEquals(depth.getSummary().getConstructionCost(),
                depth.getSummary().getCalculatedCost() - depth.getSummary().getUnconnectedPenalty());
        boolean plateau = false;
        for (DepthPiece piece : depth.piecesOf("seg_1")) {
            if (Math.abs(piece.getDepthStartM() - piece.getDepthEndM()) < 1e-6
                    && piece.getDepthStartM() < 2.5
                    && Math.abs(piece.getLengthM() - 4.0) < 0.15) {
                plateau = true;
                assertEquals(1.0, piece.getKDepth(), 1e-9);
            }
        }
        assertTrue(plateau);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new GeoJsonExporter(projection).write(out, Collections.singletonList(
                new ExportVariant(plan, engineering, cost, depth)),
                ExportIds.of("2", 2L, "4", 4L), true, 3.0);
        JsonNode root = new ObjectMapper().readTree(out.toByteArray());
        boolean sawDepth = false;
        for (JsonNode feature : root.get("features")) {
            JsonNode props = feature.get("properties");
            String type = props.get("object_type").asText();
            assertFalse("tie_in".equals(type));
            if ("heat_network".equals(type)) {
                assertTrue(props.has("depth_start"));
                assertTrue(props.has("depth_end"));
                sawDepth = true;
                JsonNode coordinates = feature.get("geometry").get("coordinates");
                for (JsonNode point : coordinates) {
                    assertEquals(2, point.size());
                }
            }
            if ("variant_summary".equals(type)) {
                assertTrue(feature.get("geometry").isNull());
            }
        }
        assertTrue(sawDepth);
    }
}
