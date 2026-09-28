package ru.heatnet.diag;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.heatnet.ContestDatasetPaths;
import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.TestReference;
import ru.heatnet.cost.VariantCost;
import ru.heatnet.cost.VariantCostCalculator;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.ingest.RawFeature;
import ru.heatnet.network.NetworkPlan;
import ru.heatnet.network.NetworkPlanner;
import ru.heatnet.routing.RouteFinder;
import ru.heatnet.routing.RouteFinderFactory;
import ru.heatnet.routing.RouteResult;

@SpringBootTest
@Tag("slow")
class ContestPipelineDiagTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private NetworkPlanner networkPlanner;

    @Autowired
    private RouteFinderFactory routeFinderFactory;

    @Autowired
    private ProjectionService projectionService;

    @Autowired
    private ru.heatnet.jobs.CalculationPipeline calculationPipeline;

    @Test
    @EnabledIf("ru.heatnet.ContestDatasetPaths#isPresent")
    void planOnly() throws Exception {
        // М7 (до 3 стратегий на прогон) утраивает стоимость каждого writeJob — запас увеличен.
        assertTimeoutPreemptively(Duration.ofMinutes(12), () -> {
            Path dataset = ContestDatasetPaths.require();
            try (InputStream in = Files.newInputStream(dataset)) {
                IngestResult ingest = ingestService.ingest(in);
                long t0 = System.currentTimeMillis();
                NetworkPlan plan = networkPlanner.plan(ingest);
                long t1 = System.currentTimeMillis();
                EngineeringResult er = new EngineeringCalculator(TestReference.get())
                        .calculate(plan.getTrees(), ingest.getExistingNetwork());
                VariantCost cost = new VariantCostCalculator(TestReference.get())
                        .calculate("diag", er, plan.getUnconnectedOks());
                int connected = 0;
                for (ru.heatnet.calc.model.NewNetworkTree tree : plan.getTrees()) {
                    connected += tree.oksNodes().size();
                }
                System.out.println("PLAN trees=" + plan.getTrees().size()
                        + " connected=" + connected
                        + " unconnected=" + plan.getUnconnectedOks().size()
                        + " ms=" + (t1 - t0)
                        + " S=" + cost.getSummary().getScore()
                        + " penalty=" + cost.getSummary().getUnconnectedPenalty()
                        + " diagnostics=" + plan.getDiagnostics().size());
                for (String d : plan.getDiagnostics()) {
                    System.out.println("DIAG " + d);
                }
                if (connected == ingest.getOksConnectionPoints().size() && plan.getDiagnostics().isEmpty()) {
                    Path dataDir = Path.of("..", "data").toAbsolutePath().normalize();
                    Path targetDir = Path.of("target").toAbsolutePath().normalize();
                    Files.createDirectories(dataDir);
                    Files.createDirectories(targetDir);
                    // М7 считает до трёх вариантов на каждый прогон — пересчитывать один и тот же
                    // результат 5 раз подряд (было решение до multi-variant) больше не бюджетно.
                    // Считаем один раз на enableDepth и копируем файл на остальные пути.
                    writeJob(dataset, dataDir.resolve("result.geojson"), false);
                    Files.copy(dataDir.resolve("result.geojson"), dataDir.resolve("m8-sample.geojson"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Files.copy(dataDir.resolve("result.geojson"), targetDir.resolve("m8-sample.geojson"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    writeJob(dataset, dataDir.resolve("m11-sample.geojson"), true);
                    Files.copy(dataDir.resolve("m11-sample.geojson"), targetDir.resolve("m11-sample.geojson"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        });
    }

    private void writeJob(Path dataset, Path out, boolean enableDepth) throws Exception {
        try (InputStream again = Files.newInputStream(dataset);
             java.io.OutputStream output = Files.newOutputStream(out)) {
            calculationPipeline.run(again, output, enableDepth, (stage, progress) -> { });
        }
        System.out.println("WROTE " + out + " bytes=" + Files.size(out) + " depth=" + enableDepth);
    }

    @Test
    @EnabledIf("ru.heatnet.ContestDatasetPaths#isPresent")
    void diagContestPipeline() {
        assertTimeoutPreemptively(Duration.ofMinutes(20), this::runContestPipeline);
    }

    private void runContestPipeline() throws Exception {
        Path dataset = ContestDatasetPaths.require();

        long t0 = System.currentTimeMillis();
        try (InputStream in = Files.newInputStream(dataset)) {
            IngestResult ingest = ingestService.ingest(in);

            int found = 0;
            int notFound = 0;

            for (OksConnectionPoint oks : ingest.getOksConnectionPoints()) {
            RawFeature pt = ingest.oksFeature(oks.getId());
                org.locationtech.jts.geom.Point oksUtm = projectionService.pointToUtm(
                        pt.getGeometryWgs84().getCoordinate().x,
                        pt.getGeometryWgs84().getCoordinate().y);
                org.locationtech.jts.geom.Point from = projectionService.utmFactory().createPoint(
                        new org.locationtech.jts.geom.Coordinate(oksUtm.getX() - 80.0, oksUtm.getY()));
                RouteFinder finder = routeFinderFactory.createForLeaf(ingest, oks.getFlowTph(), from, oksUtm);
                RouteResult r = finder.findRoute(from, oksUtm, 0);
                if (r.isFound()) {
                    found++;
                } else {
                    notFound++;
                }
            }
            long t1 = System.currentTimeMillis();
            System.out.println("ROUTES found=" + found + " notFound=" + notFound + " routeMs=" + (t1 - t0));
            assertTrue(found > 0, "ни один маршрут не найден");
            assertTrue(notFound < ingest.getOksConnectionPoints().size(), "все ОКС без маршрута");

            NetworkPlan plan = networkPlanner.plan(ingest);
            long t2 = System.currentTimeMillis();

            EngineeringCalculator eng = new EngineeringCalculator(TestReference.get());
            VariantCostCalculator costCalc = new VariantCostCalculator(TestReference.get());
            EngineeringResult er = eng.calculate(plan.getTrees(), ingest.getExistingNetwork());
            VariantCost cost = costCalc.calculate("diag", er, plan.getUnconnectedOks());

            System.out.println("PLAN trees=" + plan.getTrees().size()
                    + " unconnected=" + plan.getUnconnectedOks().size()
                    + " planMs=" + (t2 - t1));
            assertFalse(plan.getTrees().isEmpty(), "план не содержит деревьев");
            int connected = 0;
            for (ru.heatnet.calc.model.NewNetworkTree tree : plan.getTrees()) {
                connected += tree.oksNodes().size();
            }
            System.out.println("connectedOks=" + connected);
            org.junit.jupiter.api.Assertions.assertTrue(connected >= 15,
                    "подключено " + connected + " из " + ingest.getOksConnectionPoints().size()
                            + " — регрессия JointPlanner");
            assertNotNull(cost.getSummary(), "нет variant_summary-данных в VariantCost");
            List<String> topology = ru.heatnet.network.TopologyValidator.validate(
                    plan.getTrees(), TestReference.get().getRules().getMaxSegmentsPerChamber());
            org.junit.jupiter.api.Assertions.assertTrue(topology.isEmpty(), topology.toString());
            int maxDegree = 0;
            for (ru.heatnet.calc.model.NewNetworkTree tree : plan.getTrees()) {
                java.util.Map<String, Integer> degree = new java.util.HashMap<>();
                for (ru.heatnet.calc.model.NewSegment seg : tree.getSegments().values()) {
                    degree.merge(seg.getFromNodeId(), 1, Integer::sum);
                    degree.merge(seg.getToNodeId(), 1, Integer::sum);
                }
                for (ru.heatnet.calc.model.NewNode node : tree.getNodes().values()) {
                    if (node.getKind() != ru.heatnet.calc.model.NodeKind.NEW_CHAMBER) {
                        continue;
                    }
                    maxDegree = Math.max(maxDegree, degree.getOrDefault(node.getId(), 0));
                }
            }
            System.out.println("maxChamberDegree=" + maxDegree);
            org.junit.jupiter.api.Assertions.assertTrue(maxDegree <= 4, "maxChamberDegree=" + maxDegree);
            if (cost.getSummary() != null) {
                System.out.println("SCORE S=" + cost.getSummary().getScore()
                        + " penalty=" + cost.getSummary().getUnconnectedPenalty()
                        + " C=" + cost.getSummary().getCalculatedCost());
            }
        }
    }
}
