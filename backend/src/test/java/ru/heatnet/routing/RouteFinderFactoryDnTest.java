package ru.heatnet.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.geo.GeoUtils;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestReport;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.RawFeature;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.RulesTestGeometry;

class RouteFinderFactoryDnTest {

    private RouteFinderFactory factory;
    private RestrictionEngineFactory engineFactory;
    private GeometryFactory gf;
    private ProjectionService projection;

    @BeforeEach
    void setUp() {
        factory = TestRouting.routeFinderFactory();
        engineFactory = TestRouting.engineFactory();
        gf = TestRouting.utmFactory();
        projection = new ProjectionService();
    }

    @Test
    @DisplayName("шаг 1: leaf DN по расходу одного ОКС")
    void leafDnFromOksFlow() {
        assertEquals(150, factory.leafDn(65.0));
        assertEquals(300, factory.leafDn(400.0));
    }

    @Test
    @DisplayName("шаг 2: magistral DN по суммарному расходу группы ОКС")
    void magistralDnFromTotalFlow() {
        assertEquals(250, factory.magistralDn(200.0));
        assertEquals(400, factory.magistralDn(900.0));
        assertNotEquals(factory.leafDn(65.0), factory.magistralDn(200.0));
    }

    @Test
    @DisplayName("createForMagistral строит bundle с DN суммарного расхода")
    void createForMagistralUsesTotalFlowBuffer() {
        IngestResult ingest = ingestWithOks();
        int dnLeaf = factory.leafDn(65.0);
        int dnMag = factory.magistralDn(200.0);

        double minLeaf = engineFactory.createBundle(ingest, dnLeaf)
                .getBlockedOutlines().get(0).getEnvelopeInternal().getMinX();
        double minMag = engineFactory.createBundle(ingest, dnMag)
                .getBlockedOutlines().get(0).getEnvelopeInternal().getMinX();

        assertTrue(minMag < minLeaf);
    }

    private IngestResult ingestWithOks() {
        Polygon oksUtm = RulesTestGeometry.square(gf, 100, 100, 30);
        org.locationtech.jts.geom.Geometry oksWgs = utmPolygonToWgs(oksUtm);
        Map<String, Object> props = new HashMap<>();
        props.put("restriction_type", "oks");
        RawFeature feature = RawFeature.of("oks1", RawFeature.Kind.RESTRICTION, props, oksWgs, null);
        Map<String, org.locationtech.jts.geom.Geometry> restrictions = Collections.singletonMap("oks1", oksWgs);
        return new IngestResult(
                new IngestReport(),
                new ExistingNetwork(Collections.emptyList(), Collections.emptyList(), Collections.emptyList()),
                Collections.emptyList(),
                restrictions,
                Collections.singletonList(feature));
    }

    private org.locationtech.jts.geom.Geometry utmPolygonToWgs(Polygon utm) {
        Coordinate[] ring = utm.getExteriorRing().getCoordinates();
        Coordinate[] wgs = new Coordinate[ring.length];
        for (int i = 0; i < ring.length; i++) {
            wgs[i] = projection.toWgs(ring[i].x, ring[i].y);
        }
        GeometryFactory wgsFactory = GeoUtils.wgs84Factory();
        return wgsFactory.createPolygon(wgsFactory.createLinearRing(wgs));
    }
}
