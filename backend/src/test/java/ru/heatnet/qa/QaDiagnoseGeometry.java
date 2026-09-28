package ru.heatnet.qa;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
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
import ru.heatnet.routing.RouteFinderFactory;
import ru.heatnet.rules.IngestRestrictionsLoader;
import ru.heatnet.rules.model.RestrictionFeature;

/** QA (роль 4): геометрия точек подключения ОКС относительно ограничений конкурсного набора (быстро, без маршрутизации). */
public final class QaDiagnoseGeometry {

    private QaDiagnoseGeometry() {
    }

    public static void main(String[] args) throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HeatnetApplication.class)
                .web(WebApplicationType.NONE).run("--spring.profiles.active=local");
        IngestService ingestService = ctx.getBean(IngestService.class);
        ProjectionService proj = ctx.getBean(ProjectionService.class);
        ReferenceData ref = ctx.getBean(ReferenceData.class);
        RouteFinderFactory rff = ctx.getBean(RouteFinderFactory.class);
        IngestRestrictionsLoader loader = ctx.getBean(IngestRestrictionsLoader.class);

        IngestResult ingest;
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) {
            ingest = ingestService.ingest(in);
        }
        List<RestrictionFeature> rs = loader.load(ingest, ref);
        System.out.println("restrictions=" + rs.size());
        for (OksConnectionPoint oks : ingest.getOksConnectionPoints()) {
            Point p = null;
            for (RawFeature f : ingest.getAcceptedFeatures()) {
                if (f.getKind() == RawFeature.Kind.OKS_CONNECTION_POINT && oks.getId().equals(f.getId())) {
                    Coordinate c = f.getGeometryWgs84().getCoordinate();
                    p = proj.pointToUtm(c.x, c.y);
                }
            }
            final Point pp = p;
            List<RestrictionFeature> sorted = new ArrayList<>(rs);
            sorted.sort(Comparator.comparingDouble(r -> r.getGeometryUtm().distance(pp)));
            int dn = rff.leafDn(oks.getFlowTph());
            double need = 5.0 + ref.getGabarits().spec(dn).getWidthM() / 2.0;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "OKS %s flow=%.2f DN%d (для oks нужен отступ оси %.2f м): ", oks.getId(),
                    oks.getFlowTph(), dn, need));
            for (int i = 0; i < 3; i++) {
                RestrictionFeature r = sorted.get(i);
                double d = r.getGeometryUtm().distance(p);
                sb.append(String.format(Locale.ROOT, "[%s %s d=%.2f%s] ", r.getRulesKey(), r.getId(), d,
                        r.getGeometryUtm().contains(p) ? " INSIDE" : ""));
            }
            System.out.println(sb);
        }
        ctx.close();
    }
}
