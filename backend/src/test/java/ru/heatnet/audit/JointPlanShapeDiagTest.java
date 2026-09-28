package ru.heatnet.audit;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.heatnet.ContestDatasetPaths;
import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.NodeKind;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;
import ru.heatnet.network.BuiltNetworkTree;
import ru.heatnet.network.CrossingResolver;
import ru.heatnet.network.DnBoundarySplitter;
import ru.heatnet.network.ExistingNetworkGeometry;
import ru.heatnet.network.JointPlanner;
import ru.heatnet.network.NetworkPlan;
import ru.heatnet.network.NetworkTreeLayout;
import ru.heatnet.network.TopologyValidator;
import ru.heatnet.routing.RoutingPipeline;

/**
 * Форма совместного плана на конкурсном наборе: степень камеры ≤4, CrossingResolver сходится.
 */
@SpringBootTest
@Tag("slow")
class JointPlanShapeDiagTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private ReferenceData reference;

    @Autowired
    private ProjectionService projection;

    @Autowired
    private RoutingPipeline routingPipeline;

    @Test
    @EnabledIf("ru.heatnet.ContestDatasetPaths#isPresent")
    void diagJointPlanShapeAndCrossingResolver() {
        assertTimeoutPreemptively(Duration.ofMinutes(20), this::runDiag);
    }

    private void runDiag() throws Exception {
        Path dataset = ContestDatasetPaths.require();
        IngestResult ingest;
        try (InputStream in = Files.newInputStream(dataset)) {
            ingest = ingestService.ingest(in);
        }

        ExistingNetworkGeometry geometry = ExistingNetworkGeometry.fromIngest(
                ingest.getAcceptedFeatures(), ingest.getExistingNetwork(), projection);

        System.out.println("=== JOINT PLAN SHAPE DIAG ===");
        System.out.println("oks count: " + ingest.getOksConnectionPoints().size());

        JointPlanner planner = new JointPlanner(reference, projection, routingPipeline);
        long t0 = System.currentTimeMillis();
        NetworkPlan raw = planner.plan(ingest, ingest.getExistingNetwork(), geometry,
                ingest.getOksConnectionPoints());
        long t1 = System.currentTimeMillis();
        System.out.println("JointPlanner.plan: " + (t1 - t0) + " ms");
        System.out.println("  joint=" + raw.isJointConnection()
                + " trees=" + raw.getTrees().size()
                + " unconnected=" + raw.getUnconnectedOks().size());
        for (ru.heatnet.cost.UnconnectedOks lost : raw.getUnconnectedOks()) {
            System.out.println("  unconnected id=" + lost.getOksId() + " G=" + lost.getFlowTph());
        }

        for (NewNetworkTree tree : raw.getTrees()) {
            reportTree("raw", tree);
            assertTrue(maxChamberDegree(tree) <= 4,
                    "buildMerged: степень камеры > 4: " + maxChamberDegree(tree));
        }

        EngineeringCalculator engCalc = new EngineeringCalculator(reference);
        EngineeringResult eng = engCalc.calculate(raw.getTrees(), ingest.getExistingNetwork());
        double tol = reference.getRules().getGeometryToleranceM();
        DnBoundarySplitter splitter = new DnBoundarySplitter(projection.utmFactory(), tol);

        List<BuiltNetworkTree> built = new ArrayList<>();
        for (int i = 0; i < raw.getTrees().size(); i++) {
            NetworkTreeLayout layout = i < raw.getLayouts().size()
                    ? raw.getLayouts().get(i) : new NetworkTreeLayout();
            built.add(splitter.apply(raw.getTrees().get(i), layout,
                    eng.getTrees().get(i).getDiameters()));
        }
        int afterSplit = 0;
        for (BuiltNetworkTree b : built) {
            afterSplit += b.getTree().getSegments().size();
            reportTree("afterDnSplit", b.getTree());
        }
        System.out.println("segments after DnBoundarySplitter: " + afterSplit);

        CrossingResolver resolver = new CrossingResolver(projection.utmFactory(), tol,
                reference.getRules().getRoutingCrossingBudgetMs());
        List<BuiltNetworkTree> out = resolver.resolveAll(built);
        int n = 0;
        for (BuiltNetworkTree b : out) {
            n += b.getTree().getSegments().size();
            assertTrue(maxChamberDegree(b.getTree()) <= 4,
                    "после CrossingResolver степень камеры > 4");
        }
        System.out.println("CrossingResolver.resolveAll ok, segments " + afterSplit + " -> " + n
                + ", fallback=" + resolver.getFallbackCount());
        assertTrue(resolver.getFallbackCount() == 0,
                "CrossingResolver откатил " + resolver.getFallbackCount() + " дерев(а) — пересечения не разрешены");

        List<NewNetworkTree> trees = new ArrayList<>();
        for (BuiltNetworkTree b : out) {
            trees.add(b.getTree());
        }
        List<String> errors = TopologyValidator.validate(trees,
                reference.getRules().getMaxSegmentsPerChamber());
        assertTrue(errors.isEmpty(), errors.toString());
        System.out.println("TopologyValidator empty");
        assertFalse(raw.getTrees().isEmpty(), "нет деревьев");
        int connected = 0;
        for (NewNetworkTree tree : raw.getTrees()) {
            connected += tree.oksNodes().size();
        }
        int connectedResolved = 0;
        for (NewNetworkTree tree : trees) {
            connectedResolved += tree.oksNodes().size();
        }
        System.out.println("connectedOks=" + connected + " (после CrossingResolver: " + connectedResolved + ")");
        assertTrue(connectedResolved == connected, "CrossingResolver потерял ОКС: " + connected + " -> " + connectedResolved);
        assertTrue(connected >= 15, "подключено " + connected + " из 17 — регрессия JointPlanner");
    }

    private static int maxChamberDegree(NewNetworkTree tree) {
        Map<String, AtomicInteger> degree = new HashMap<>();
        for (NewSegment seg : tree.getSegments().values()) {
            degree.computeIfAbsent(seg.getFromNodeId(), k -> new AtomicInteger()).incrementAndGet();
            degree.computeIfAbsent(seg.getToNodeId(), k -> new AtomicInteger()).incrementAndGet();
        }
        int worst = 0;
        for (NewNode node : tree.getNodes().values()) {
            if (node.getKind() != NodeKind.NEW_CHAMBER) {
                continue;
            }
            int d = degree.containsKey(node.getId()) ? degree.get(node.getId()).get() : 0;
            if (d > worst) {
                worst = d;
            }
        }
        return worst;
    }

    private static void reportTree(String label, NewNetworkTree tree) {
        Map<String, AtomicInteger> degree = new HashMap<>();
        for (NewSegment seg : tree.getSegments().values()) {
            degree.computeIfAbsent(seg.getFromNodeId(), k -> new AtomicInteger()).incrementAndGet();
            degree.computeIfAbsent(seg.getToNodeId(), k -> new AtomicInteger()).incrementAndGet();
        }
        String worstChamber = null;
        int worstDegree = 0;
        for (NewNode node : tree.getNodes().values()) {
            if (node.getKind() != NodeKind.NEW_CHAMBER) {
                continue;
            }
            int d = degree.containsKey(node.getId()) ? degree.get(node.getId()).get() : 0;
            if (d > worstDegree) {
                worstDegree = d;
                worstChamber = node.getId();
            }
        }
        double trunkLen = -1;
        for (NewSegment seg : tree.getSegments().values()) {
            if (seg.getFromNodeId().equals(tree.getTieIn().getId())) {
                trunkLen = seg.getLengthM();
                break;
            }
        }
        System.out.printf("  [%s] tieIn=%s nodes=%d segments=%d oks=%d "
                        + "trunkFromTieIn=%.3f m maxChamberDegree=%d (%s)%n",
                label, tree.getTieIn().getId(), tree.getNodes().size(), tree.getSegments().size(),
                tree.oksNodes().size(), trunkLen, worstDegree, worstChamber);
    }
}
