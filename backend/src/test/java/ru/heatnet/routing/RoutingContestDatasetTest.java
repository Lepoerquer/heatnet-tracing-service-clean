package ru.heatnet.routing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.heatnet.ContestDatasetPaths;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.ingest.RawFeature;

@SpringBootTest
class RoutingContestDatasetTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private RouteFinderFactory routeFinderFactory;

    @Autowired
    private ProjectionService projectionService;

    @Test
    // §2.2/keepOut теперь проверяется на каждом ребре графа и на каждом шаге string-pulling
    // (10-audit-and-fixes-2026-09-24.md §8.1) — корректность вместо срезания угла стоит времени;
    // запас увеличен, чтобы не путать «стало медленнее из-за честной проверки» с реальным зависанием.
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    @DisplayName("конкурсный dataset: маршрут к первому ОКС с исключением своего полигона (§2.2)")
    void routeOnContestDataset() throws Exception {
        Path dataset = ContestDatasetPaths.find().orElse(null);
        if (dataset == null) {
            return;
        }

        try (InputStream in = Files.newInputStream(dataset)) {
            IngestResult ingest = ingestService.ingest(in);
            assertFalse(ingest.getOksConnectionPoints().isEmpty());

            OksConnectionPoint oks = ingest.getOksConnectionPoints().get(0);
            Point oksUtm = findPointUtm(ingest, RawFeature.Kind.OKS_CONNECTION_POINT, oks.getId());
            RouteResult result = RouteResult.notFound();
            double[][] deltas = {
                    {-80, 0}, {80, 0}, {0, -80}, {0, 80},
                    {-40, 0}, {40, 0}, {0, -40}, {0, 40},
                    {-120, 0}, {120, 0}
            };
            for (double[] d : deltas) {
                Point from = projectionService.utmFactory().createPoint(
                        new Coordinate(oksUtm.getX() + d[0], oksUtm.getY() + d[1]));
                RouteFinder finder = routeFinderFactory.createForLeaf(ingest, oks.getFlowTph(), from, oksUtm);
                result = finder.findRoute(from, oksUtm, 0);
                if (result.isFound()) {
                    break;
                }
            }
            assertTrue(result.isFound(), "ожидался маршрут к ОКС " + oks.getId());
            assertTrue(result.getLengthM() > 0.0);
            assertTrue(result.getPathUtm().getNumPoints() >= 2);
        }
    }

    private Point findPointUtm(IngestResult ingest, RawFeature.Kind kind, String id) {
        for (RawFeature feature : ingest.getAcceptedFeatures()) {
            if (feature.getKind() != kind) {
                continue;
            }
            if (id != null && !id.equals(feature.getId())) {
                continue;
            }
            Coordinate c = feature.getGeometryWgs84().getCoordinate();
            return projectionService.pointToUtm(c.x, c.y);
        }
        throw new IllegalStateException("Feature not found: " + kind + " id=" + id);
    }
}
