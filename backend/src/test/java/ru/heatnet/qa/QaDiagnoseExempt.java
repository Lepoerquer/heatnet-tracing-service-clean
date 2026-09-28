package ru.heatnet.qa;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.locationtech.jts.geom.Coordinate;
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
import ru.heatnet.rules.IngestRestrictionsLoader;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.SpatialConstraintBundle;
import ru.heatnet.rules.model.RestrictionFeature;

/**
 * QA (роль 4): доказательство достижимости. Для ОКС из списка исключает из ограничений полигон, ВНУТРИ которого лежит
 * точка подключения (собственное здание цели), и заново ищет маршрут. Репозиторий не меняется.
 */
public final class QaDiagnoseExempt {

    private QaDiagnoseExempt() {
    }

    public static void main(String[] args) throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HeatnetApplication.class)
                .web(WebApplicationType.NONE).run("--spring.profiles.active=local");
        IngestService ingestService = ctx.getBean(IngestService.class);
        ProjectionService proj = ctx.getBean(ProjectionService.class);
        ReferenceData ref = ctx.getBean(ReferenceData.class);
        RestrictionEngineFactory ef = ctx.getBean(RestrictionEngineFactory.class);
        RouteFinderFactory rff = ctx.getBean(RouteFinderFactory.class);
        IngestRestrictionsLoader loader = ctx.getBean(IngestRestrictionsLoader.class);
        IngestResult ingest;
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) {
            ingest = ingestService.ingest(in);
        }
        ExistingNetworkGeometry geometry = ExistingNetworkGeometry.fromIngest(
                ingest.getAcceptedFeatures(), ingest.getExistingNetwork(), proj);
        List<RestrictionFeature> all = loader.load(ingest, ref);
        List<String> wanted = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            wanted.add(args[i]);
        }
        System.out.println("id | DN | tie(kind,dist) | route WITH own-building exemption (status,len,vertices,sec)");
        for (OksConnectionPoint oks : ingest.getOksConnectionPoints()) {
            if (!wanted.isEmpty() && !wanted.contains(oks.getId())) {
                continue;
            }
            Point p = null;
            for (RawFeature f : ingest.getAcceptedFeatures()) {
                if (f.getKind() == RawFeature.Kind.OKS_CONNECTION_POINT && oks.getId().equals(f.getId())) {
                    Coordinate c = f.getGeometryWgs84().getCoordinate();
                    p = proj.pointToUtm(c.x, c.y);
                }
            }
            int dn = rff.leafDn(oks.getFlowTph());
            List<RestrictionFeature> filtered = new ArrayList<>();
            for (RestrictionFeature r : all) {
                if (!r.getGeometryUtm().contains(p)) {
                    filtered.add(r);
                }
            }
            TieInCandidate cand = TieInCandidates.bestNear(ingest.getExistingNetwork(), geometry, ref.getRules(),
                    p.getCoordinate(), dn);
            Point tie = proj.utmFactory().createPoint(cand.getLocationUtm());
            SpatialConstraintBundle bundle = ef.createBundle(filtered, dn);
            RouteFinder finder = rff.create(bundle, dn);
            long t = System.nanoTime();
            RouteResult r = finder.findRoute(tie, p, dn);
            System.out.println(oks.getId() + " | " + dn + " | " + cand.getKind() + ","
                    + String.format(Locale.ROOT, "%.0f", cand.getReferenceDistanceM()) + "m | " + r.getStatus() + ","
                    + String.format(Locale.ROOT, "%.1f", r.getLengthM()) + "m,"
                    + (r.isFound() ? r.getPathUtm().getNumPoints() : 0) + " vertices,"
                    + String.format(Locale.ROOT, "%.1f", (System.nanoTime() - t) / 1e9) + "s");
            System.out.flush();
        }
        ctx.close();
    }
}
