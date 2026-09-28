package ru.heatnet.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import ru.heatnet.ingest.OksConnectionPoint;

class JointPlannerClusterTest {

    private final GeometryFactory gf = new GeometryFactory();

    @Test
    @DisplayName("два близких ОКС в одном кластере, далёкий — отдельно")
    void nearbyOksShareCluster() {
        OksConnectionPoint a = new OksConnectionPoint("a", 10);
        OksConnectionPoint b = new OksConnectionPoint("b", 10);
        OksConnectionPoint c = new OksConnectionPoint("c", 10);
        Map<String, Point> utm = new HashMap<>();
        utm.put("a", gf.createPoint(new Coordinate(0, 0)));
        utm.put("b", gf.createPoint(new Coordinate(50, 0)));
        utm.put("c", gf.createPoint(new Coordinate(1000, 0)));

        List<List<OksConnectionPoint>> clusters = JointPlanner.clusterByDistance(
                Arrays.asList(a, b, c), utm, 250.0);
        assertEquals(2, clusters.size());
        int sizesMin = Math.min(clusters.get(0).size(), clusters.get(1).size());
        int sizesMax = Math.max(clusters.get(0).size(), clusters.get(1).size());
        assertEquals(1, sizesMin);
        assertEquals(2, sizesMax);
    }

    @Test
    @DisplayName("группа > 6 режется на куски без потери хвоста")
    void splitGroupKeepsRemainder() {
        List<OksConnectionPoint> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seven.add(new OksConnectionPoint("o" + i, 10));
        }
        List<List<OksConnectionPoint>> chunks = JointPlanner.splitGroup(seven, 6);
        assertEquals(2, chunks.size());
        assertEquals(6, chunks.get(0).size());
        assertEquals(1, chunks.get(1).size());
        assertEquals("o6", chunks.get(1).get(0).getId());
    }
}
