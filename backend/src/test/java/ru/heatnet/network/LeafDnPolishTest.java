package ru.heatnet.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.geo.GeoUtils;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestReport;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.ingest.RawFeature;
import ru.heatnet.routing.RouteFinderFactory;
import ru.heatnet.routing.RouteM5Verification;
import ru.heatnet.routing.RoutingPipeline;
import ru.heatnet.rules.IngestRestrictionsLoader;
import ru.heatnet.rules.RestrictionEngineFactory;

/**
 * AUDIT-13, табл. 2 / §3.1: отступы зависят от ДУ участка. Граф плана строится по ДУ магистрали (здесь ДУ 500 —
 * отступ от зданий 7 м), а ветка к маленькому ОКС по M5 получает ДУ 80 (отступ 5 м). Проход 13 м между двумя
 * длинными зданиями (стена 290 м) закрыт при ДУ 500 (нужно 2 × (7 + 0,835) = 15,7 м) и открыт при ДУ 80
 * (2 × (5 + 0,235) = 10,5 м): ветка должна пройти в проход (~70 м), а не обходить квартал (> 300 м).
 */
class LeafDnPolishTest {

    private final ProjectionService projection = TestNetworkFixtures.projection();
    private final GeometryFactory gf = projection.utmFactory();
    private final ReferenceData ref = TestReference.get();
    private final Coordinate origin = projection.toUtm(37.62, 55.70);

    private Coordinate wgs(double dx, double dy) {
        return projection.toWgs(origin.x + dx, origin.y + dy);
    }

    private Coordinate utm(double dx, double dy) {
        return new Coordinate(origin.x + dx, origin.y + dy);
    }

    private RawFeature point(String id, RawFeature.Kind kind, double dx, double dy, Double flow) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (flow != null) {
            props.put("flow_tph", flow);
        }
        Coordinate c = wgs(dx, dy);
        return RawFeature.of(id, kind, props, GeoUtils.pointWgs84(c.x, c.y), null);
    }

    private Polygon rectWgs(double x0, double y0, double x1, double y1) {
        Coordinate[] ring = {wgs(x0, y0), wgs(x1, y0), wgs(x1, y1), wgs(x0, y1), wgs(x0, y0)};
        return GeoUtils.wgs84Factory().createPolygon(ring);
    }

    private Polygon rectUtm(double x0, double y0, double x1, double y1) {
        return gf.createPolygon(new Coordinate[] {utm(x0, y0), utm(x1, y0), utm(x1, y1), utm(x0, y1), utm(x0, y0)});
    }

    @Test
    @DisplayName("LEAF-DN-1: ветка ДУ 80 проходит в проход 13 м, закрытый для графа ДУ 500")
    void leafUsesGapOpenAtItsOwnDn() {
        List<RawFeature> features = new ArrayList<>();
        Map<String, Geometry> restrictions = new LinkedHashMap<>();
        features.add(point("src", RawFeature.Kind.SOURCE, -20, 0, null));
        features.add(point("ch", RawFeature.Kind.HEAT_CHAMBER, 20, 0, null));
        Map<String, Object> pipeProps = new LinkedHashMap<>();
        pipeProps.put("diameter", 600);
        Coordinate[] pipe = {wgs(-20, 0), wgs(20, 0)};
        features.add(RawFeature.of("net", RawFeature.Kind.HEAT_NETWORK, pipeProps,
                GeoUtils.wgs84Factory().createLineString(pipe), pipe));

        features.add(point("o_small", RawFeature.Kind.OKS_CONNECTION_POINT, 0, 62, 10.0));
        features.add(point("o_big", RawFeature.Kind.OKS_CONNECTION_POINT, 10, -62, 1500.0));
        Object[][] buildings = {
                {"b_small", rectWgs(-6, 56, 6, 70)},
                {"b_big", rectWgs(4, -70, 16, -56)},
                {"b_left", rectWgs(-150, 25, -6.5, 40)},
                {"b_right", rectWgs(6.5, 25, 150, 40)}};
        for (Object[] b : buildings) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("restriction_type", "oks");
            features.add(RawFeature.of((String) b[0], RawFeature.Kind.RESTRICTION, props, (Polygon) b[1], null));
            restrictions.put((String) b[0], (Polygon) b[1]);
        }
        IngestReport report = new IngestReport();
        ExistingNetwork network = new ru.heatnet.ingest.NetworkTreeBuilder(projection).build(features, 15.0, report);
        IngestResult ingest = new IngestResult(report, network,
                Arrays.asList(new OksConnectionPoint("o_small", 10.0), new OksConnectionPoint("o_big", 1500.0)),
                restrictions, features);

        NetworkPlan plan = planner().plan(ingest);
        assertTrue(plan.getUnconnectedOks().isEmpty(), "оба ОКС должны быть подключены");
        assertTrue(plan.getDiagnostics().isEmpty(), "план без замечаний §2.1: " + plan.getDiagnostics());

        EngineeringResult eng = new EngineeringCalculator(ref).calculate(plan.getTrees(), network);
        Polygon gap = rectUtm(-6.5, 25, 6.5, 40);
        boolean found = false;
        for (int i = 0; i < plan.getTrees().size(); i++) {
            NewNetworkTree tree = plan.getTrees().get(i);
            NetworkTreeLayout layout = plan.getLayouts().get(i);
            for (NewNode oks : tree.oksNodes()) {
                if (!"o_small".equals(oks.getOksId())) {
                    continue;
                }
                found = true;
                // путь от ОКС вверх до ответвления/врезки
                double inGap = 0.0;
                double length = 0.0;
                String node = oks.getId();
                NewSegment in;
                while ((in = tree.incomingOf(node)) != null) {
                    LineString line = layout.segmentLine(in.getId());
                    assertNotNull(line);
                    inGap += line.intersection(gap).getLength();
                    length += line.getLength();
                    Integer dn = eng.getTrees().get(i).getDiameters().get(in.getId());
                    if (tree.childrenOf(in.getFromNodeId()).size() > 1 || tree.incomingOf(in.getFromNodeId()) == null) {
                        assertEquals(Integer.valueOf(80), dn, "ДУ ветки к маленькому ОКС по расходу 10 т/ч");
                        break;
                    }
                    node = in.getFromNodeId();
                }
                assertTrue(inGap > 10.0, "ветка должна пройти в проход между зданиями, длина в проходе " + inGap
                        + " м, длина ветки " + length + " м");
                // в обход стены зданий (x = ±150 м) ветка была бы длиннее 300 м
                assertTrue(length < 120.0, "ветка не должна обходить квартал: " + length + " м");
            }
        }
        assertTrue(found, "ОКС o_small в плане");
        assertFalse(plan.getTrees().isEmpty());
    }

    private NetworkPlanner planner() {
        RestrictionEngineFactory ef = new RestrictionEngineFactory(new IngestRestrictionsLoader(projection), ref,
                projection);
        RouteFinderFactory rff = new RouteFinderFactory(ef, ref);
        RoutingPipeline pipe = new RoutingPipeline(rff, new RouteM5Verification(ef, rff));
        return new NetworkPlanner(ref, projection, pipe, rff);
    }
}
