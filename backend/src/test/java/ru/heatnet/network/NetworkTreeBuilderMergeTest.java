package ru.heatnet.network;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.NodeKind;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.routing.RouteResult;

class NetworkTreeBuilderMergeTest {

    private final GeometryFactory gf = new GeometryFactory();
    private final NetworkTreeBuilder builder = new NetworkTreeBuilder(gf, 0.01, 4, 20.0);

    @Test
    @DisplayName("buildMerged: иерархия + каскад, степень камеры ≤ 4")
    void mergedTreeRespectsChamberDegree() {
        TieInPoint tie = TieInPoint.intoPipe("tie", "net_1", 0.0, "nch_new");
        List<NetworkTreeBuilder.OksRoute> routes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            LineString path = gf.createLineString(new Coordinate[] {
                    new Coordinate(0, 0),
                    new Coordinate(80, 0),
                    new Coordinate(120, i * 20.0)
            });
            RouteResult route = RouteResult.found(path, Collections.emptyList());
            routes.add(new NetworkTreeBuilder.OksRoute(new OksConnectionPoint("oks" + i, 10.0), route));
        }

        BuiltNetworkTree built = builder.buildMerged(tie, routes);
        NewNetworkTree tree = built.getTree();
        List<String> errors = TopologyValidator.validate(Collections.singletonList(tree), 4);
        assertTrue(errors.isEmpty(), String.valueOf(errors));

        Map<String, AtomicInteger> degree = new HashMap<>();
        for (NewSegment seg : tree.getSegments().values()) {
            degree.computeIfAbsent(seg.getFromNodeId(), k -> new AtomicInteger()).incrementAndGet();
            degree.computeIfAbsent(seg.getToNodeId(), k -> new AtomicInteger()).incrementAndGet();
        }
        int maxChamber = 0;
        for (NewNode node : tree.getNodes().values()) {
            if (node.getKind() != NodeKind.NEW_CHAMBER) {
                continue;
            }
            int d = degree.containsKey(node.getId()) ? degree.get(node.getId()).get() : 0;
            if (d > maxChamber) {
                maxChamber = d;
            }
        }
        assertTrue(maxChamber <= 4, "maxChamberDegree=" + maxChamber);
        assertTrue(tree.oksNodes().size() == 6);
    }
}
