package ru.heatnet.qa;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import ru.heatnet.HeatnetApplication;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.ingest.RawFeature;
import ru.heatnet.network.ExistingNetworkGeometry;
import ru.heatnet.network.TieInCandidate;
import ru.heatnet.network.TieInCandidates;
import ru.heatnet.routing.RouteFinder;
import ru.heatnet.routing.RouteFinderFactory;
import ru.heatnet.routing.RouteResult;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.SpatialConstraintBundle;

/**
 * QA (роль 4): почему конвейер не подключает ОКС на конкурсном наборе.
 * Для каждого ОКС: DN_leaf, лежит ли точка ОКС / точка врезки внутри запретной зоны, результат маршрута и время.
 */
public final class QaDiagnoseContest {

    private QaDiagnoseContest() {
    }

    public static void main(String[] args) throws Exception {
        boolean routes = args.length < 2 || !"noroute".equals(args[1]);
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HeatnetApplication.class)
                .web(WebApplicationType.NONE).run("--spring.profiles.active=local");
        IngestService ingestService = ctx.getBean(IngestService.class);
        ProjectionService proj = ctx.getBean(ProjectionService.class);
        ReferenceData ref = ctx.getBean(ReferenceData.class);
        RestrictionEngineFactory ef = ctx.getBean(RestrictionEngineFactory.class);
        RouteFinderFactory rff = ctx.getBean(RouteFinderFactory.class);

        IngestResult ingest;
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) {
            ingest = ingestService.ingest(in);
        }
        ExistingNetworkGeometry geometry = ExistingNetworkGeometry.fromIngest(
                ingest.getAcceptedFeatures(), ingest.getExistingNetwork(), proj);

        System.out.println("id | flow | DN | oksPointInBlocked | tie(kind,dist,inBlocked) | route(status,len,sec)");
        for (OksConnectionPoint oks : ingest.getOksConnectionPoints()) {
            Point p = null;
            for (RawFeature f : ingest.getAcceptedFeatures()) {
                if (f.getKind() == RawFeature.Kind.OKS_CONNECTION_POINT && oks.getId().equals(f.getId())) {
                    Coordinate c = f.getGeometryWgs84().getCoordinate();
                    p = proj.pointToUtm(c.x, c.y);
                }
            }
            int dn = rff.leafDn(oks.getFlowTph());
            SpatialConstraintBundle bundle = ef.createBundle(ingest, dn);
            String oksBlocked = blockedBy(bundle.getBlockedOutlines(), p);
            TieInCandidate cand = TieInCandidates.bestNear(ingest.getExistingNetwork(), geometry, ref.getRules(),
                    p.getCoordinate(), dn);
            String tieInfo = "none";
            Point tie = null;
            if (cand != null) {
                tie = proj.utmFactory().createPoint(cand.getLocationUtm());
                tieInfo = cand.getKind() + "," + String.format(Locale.ROOT, "%.1f", cand.getReferenceDistanceM())
                        + "m," + blockedBy(bundle.getBlockedOutlines(), tie);
            }
            String routeInfo = "skipped";
            if (routes && tie != null) {
                RouteFinder finder = rff.create(bundle, dn);
                long t = System.nanoTime();
                RouteResult r = finder.findRoute(tie, p, dn);
                routeInfo = r.getStatus() + "," + String.format(Locale.ROOT, "%.1f", r.getLengthM()) + "m,"
                        + String.format(Locale.ROOT, "%.1f", (System.nanoTime() - t) / 1e9) + "s";
            }
            System.out.println(oks.getId() + " | " + oks.getFlowTph() + " | " + dn + " | " + oksBlocked + " | "
                    + tieInfo + " | " + routeInfo);
            System.out.flush();
        }
        ctx.close();
    }

    private static String blockedBy(List<Geometry> outlines, Point p) {
        int n = 0;
        for (Geometry g : outlines) {
            if (g.contains(p)) {
                n++;
            }
        }
        return n == 0 ? "free" : ("BLOCKED(" + n + ")");
    }
}

