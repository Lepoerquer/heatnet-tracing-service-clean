package ru.heatnet.diag;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.heatnet.ContestDatasetPaths;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.network.ExistingNetworkGeometry;
import ru.heatnet.network.TieInCandidate;
import ru.heatnet.network.TieInCandidates;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.SpatialConstraintBundle;

/**
 * Быстрый снимок: длина префикса выхода из запретной зоны у ближайших врезок.
 */
@SpringBootTest
@Tag("slow")
class UnconnectedPrefixDiagTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private ReferenceData reference;

    @Autowired
    private ProjectionService projection;

    @Autowired
    private RestrictionEngineFactory engineFactory;

    @Test
    @EnabledIf("ru.heatnet.ContestDatasetPaths#isPresent")
    void dumpPrefixPerOks() throws Exception {
        Path dataset = ContestDatasetPaths.require();
        IngestResult ingest;
        try (InputStream in = Files.newInputStream(dataset)) {
            ingest = ingestService.ingest(in);
        }
        ExistingNetworkGeometry geometry = ExistingNetworkGeometry.fromIngest(
                ingest.getAcceptedFeatures(), ingest.getExistingNetwork(), projection);
        double maxExit = reference.getRules().getRoutingStartExitMaxM();
        System.out.println("=== PREFIX DIAG start_exit_max_m=" + maxExit + " ===");
        RestrictionEngineFactory.beginRequest();
        try {
            for (OksConnectionPoint oks : ingest.getOksConnectionPoints()) {
                ru.heatnet.ingest.RawFeature f = ingest.oksFeature(oks.getId());
                Point oksUtm = projection.pointToUtm(
                        f.getGeometryWgs84().getCoordinate().x, f.getGeometryWgs84().getCoordinate().y);
                int dn = reference.getDiameters().minFor(oks.getFlowTph())
                        .orElse(reference.getDiameters().byIndex(0)).getDn();
                List<TieInCandidate> cands = TieInCandidates.findNear(
                        ingest.getExistingNetwork(), geometry, reference.getRules(),
                        oksUtm.getCoordinate(), dn);
                SpatialConstraintBundle bundle = engineFactory.createBundleForTarget(ingest, dn, oksUtm);
                System.out.printf("oks=%s G=%.2f dn=%d cands=%d%n",
                        oks.getId(), oks.getFlowTph(), dn, cands.size());
                int shown = 0;
                for (TieInCandidate c : cands) {
                    if (shown >= 4) {
                        break;
                    }
                    double prefix = unionExitM(bundle.getBlockedOutlines(), c.getLocationUtm());
                    String flag = prefix > maxExit ? "OVER" : "ok";
                    System.out.printf("  [%s] obj=%s kind=%s dist=%.1f prefix=%.2f m%n",
                            flag, c.getTieIn().getExistingObjectId(), c.getKind(),
                            c.getReferenceDistanceM(), prefix);
                    shown++;
                }
            }
        } finally {
            RestrictionEngineFactory.endRequest();
        }
    }

    private static double unionExitM(List<Geometry> outlines, Coordinate start) {
        org.locationtech.jts.geom.GeometryFactory gf = new org.locationtech.jts.geom.GeometryFactory();
        Point p = gf.createPoint(start);
        Geometry union = null;
        for (Geometry outline : outlines) {
            if (outline == null || outline.isEmpty()) {
                continue;
            }
            try {
                if (outline.covers(p) || outline.contains(p)) {
                    union = union == null ? outline : union.union(outline);
                }
            } catch (RuntimeException ignored) {
                // skip
            }
        }
        if (union == null || union.isEmpty()) {
            return 0.0;
        }
        try {
            Coordinate[] pts = DistanceOp.nearestPoints(union.getBoundary(), p);
            return pts == null || pts.length == 0 ? 0.0 : start.distance(pts[0]);
        } catch (RuntimeException ex) {
            return -1.0;
        }
    }
}
