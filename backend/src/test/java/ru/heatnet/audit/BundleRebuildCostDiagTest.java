package ru.heatnet.audit;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.heatnet.ContestDatasetPaths;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.ingest.RawFeature;
import ru.heatnet.rules.IngestRestrictionsLoader;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.model.RestrictionFeature;

/**
 * Диагностика: сколько стоит ОДНА пересборка пространственного бандла M2 и сколько раз
 * она происходит за один прогон. Не ассертит поведение — печатает измерения для отчёта.
 */
@SpringBootTest
@Tag("slow")
class BundleRebuildCostDiagTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private RestrictionEngineFactory engineFactory;

    @Autowired
    private IngestRestrictionsLoader restrictionsLoader;

    @Autowired
    private ReferenceData referenceData;

    @Autowired
    private ProjectionService projectionService;

    @Test
    @Timeout(600)
    @EnabledIf("ru.heatnet.ContestDatasetPaths#isPresent")
    void measureBundleRebuildCost() throws Exception {
        Path dataset = ContestDatasetPaths.require();
        IngestResult ingest;
        try (InputStream in = Files.newInputStream(dataset)) {
            ingest = ingestService.ingest(in);
        }

        List<RestrictionFeature> features = restrictionsLoader.load(ingest, referenceData);
        System.out.println("=== BUNDLE REBUILD DIAG ===");
        System.out.println("restrictions loaded: " + features.size());

        OksConnectionPoint first = ingest.getOksConnectionPoints().get(0);
        RawFeature oksFeature = ingest.oksFeature(first.getId());
        Point target = projectionService.pointToUtm(
                oksFeature.getGeometryWgs84().getCoordinate().x,
                oksFeature.getGeometryWgs84().getCoordinate().y);

        int n = 20;
        double perBundleMs;
        RestrictionEngineFactory.beginRequest();
        try {
            for (int i = 0; i < 3; i++) {
                engineFactory.createBundleForTarget(ingest, 100, target);
            }
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                engineFactory.createBundleForTarget(ingest, 100, target);
            }
            long t1 = System.nanoTime();
            perBundleMs = (t1 - t0) / 1e6 / n;
            System.out.printf("createBundleForTarget (cached): %.3f ms per call (n=%d)%n", perBundleMs, n);
            org.junit.jupiter.api.Assertions.assertTrue(perBundleMs < 15.0,
                    "кэш бандла должен убирать повторную сборку JTS, факт " + perBundleMs + " мс");
        } finally {
            RestrictionEngineFactory.endRequest();
        }

        long t2 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            restrictionsLoader.load(ingest, referenceData);
        }
        long t3 = System.nanoTime();
        System.out.printf("  of which restrictionsLoader.load (reproject): %.1f ms per call%n",
                (t3 - t2) / 1e6 / n);

        int oksCount = ingest.getOksConnectionPoints().size();
        System.out.println("oks count: " + oksCount);
        System.out.printf("JointPlanner alone rebuilds the bundle >= %d times "
                        + "(magistral+leaf per OKS in tryJointPlan, leaf per OKS in trySeparatePlan)%n",
                oksCount * 3);
        System.out.printf("=> lower bound on wasted time: %.1f s%n", oksCount * 3 * perBundleMs / 1000.0);
    }
}
