package ru.heatnet.qa;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import ru.heatnet.HeatnetApplication;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.ingest.RawFeature;
import ru.heatnet.routing.RouteFinder;
import ru.heatnet.routing.RouteFinderFactory;
import ru.heatnet.routing.RouteResult;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.SpatialConstraintBundle;

/**
 * QA (роль 4): воспроизводит сценарий RoutingContestDatasetTest (source -> первый ОКС, bundle DN300) и печатает
 * РЕАЛЬНЫЙ статус, который тест команды не проверяет (assertTrue(found || notFound) — тавтология).
 */
public final class QaReplicateTeamClaim {

    private QaReplicateTeamClaim() {
    }

    public static void main(String[] args) throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HeatnetApplication.class)
                .web(WebApplicationType.NONE).run("--spring.profiles.active=local");
        IngestService ingestService = ctx.getBean(IngestService.class);
        RestrictionEngineFactory ef = ctx.getBean(RestrictionEngineFactory.class);
        RouteFinderFactory rff = ctx.getBean(RouteFinderFactory.class);
        ProjectionService proj = ctx.getBean(ProjectionService.class);
        IngestResult ingest;
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) {
            ingest = ingestService.ingest(in);
        }
        OksConnectionPoint oks = ingest.getOksConnectionPoints().get(0);
        SpatialConstraintBundle bundle = ef.createBundle(ingest, 300);
        RouteFinder finder = rff.createForLeaf(bundle, oks.getFlowTph());
        Point src = pt(ingest, proj, RawFeature.Kind.SOURCE, null);
        Point dst = pt(ingest, proj, RawFeature.Kind.OKS_CONNECTION_POINT, oks.getId());
        long t = System.nanoTime();
        RouteResult r = finder.findRoute(src, dst, 0);
        System.out.printf(Locale.ROOT, "TEAM-CLAIM-REPLICA source->OKS%s DN300 bundle: status=%s length=%.1f time=%.1fs%n",
                oks.getId(), r.getStatus(), r.getLengthM(), (System.nanoTime() - t) / 1e9);
        ctx.close();
    }

    private static Point pt(IngestResult ingest, ProjectionService proj, RawFeature.Kind kind, String id) {
        for (RawFeature f : ingest.getAcceptedFeatures()) {
            if (f.getKind() == kind && (id == null || id.equals(f.getId()))) {
                Coordinate c = f.getGeometryWgs84().getCoordinate();
                return proj.pointToUtm(c.x, c.y);
            }
        }
        throw new IllegalStateException("not found " + kind);
    }
}
