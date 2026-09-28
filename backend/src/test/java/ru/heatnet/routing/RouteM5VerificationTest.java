package ru.heatnet.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.geo.GeoUtils;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestReport;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.RawFeature;
import ru.heatnet.rules.RulesTestGeometry;

class RouteM5VerificationTest {

    private static final int ROUTING_DN = 200;
    private static final int ACTUAL_DN = 300;

    private RouteM5Verification verification;
    private GeometryFactory gf;
    private ProjectionService projection;
    private IngestResult ingest;

    @BeforeEach
    void setUp() {
        gf = TestRouting.utmFactory();
        projection = new ProjectionService();
        RouteFinderFactory routeFactory = TestRouting.routeFinderFactory();
        verification = new RouteM5Verification(TestRouting.engineFactory(), routeFactory);
        ingest = ingestWithOks();
    }

    @Test
    @DisplayName("шаг 3: маршрут OK при routing DN, нарушение при actual DN")
    void detectsViolationAtActualDn() {
        RouteResult initial = routeTooCloseToOks();
        assertFalse(verification.hasBlockedSegment(ingest, initial, ROUTING_DN));
        assertTrue(verification.hasBlockedSegment(ingest, initial, ACTUAL_DN));
    }

    @Test
    @DisplayName("шаг 3: ровно одна перетрассировка при нарушении actual DN")
    void reroutesOnceWhenActualDnViolates() {
        Point from = point(50, 80);
        Point to = point(150, 140);
        RouteResult initial = routeTooCloseToOks();

        RouteResult verified = verification.verifyAndRerouteOnce(
                ingest, from, to, initial, ROUTING_DN, ACTUAL_DN);

        assertTrue(verified.isFound());
        assertFalse(verification.hasBlockedSegment(ingest, verified, ACTUAL_DN));
    }

    @Test
    @DisplayName("шаг 3: без нарушения actual DN — исходный маршрут")
    void keepsInitialWhenActualDnOk() {
        RouteResult initial = RouteResult.found(
                RulesTestGeometry.line(gf, 50, 160, 150, 160),
                Collections.emptyList());
        Point from = point(50, 160);
        Point to = point(150, 160);

        RouteResult verified = verification.verifyAndRerouteOnce(
                ingest, from, to, initial, ROUTING_DN, ACTUAL_DN);

        assertEquals(initial.getPathUtm(), verified.getPathUtm());
    }

    @Test
    @DisplayName("шаг 3: перетрассировка не удалась → NOT_FOUND")
    void notFoundWhenRerouteFails() {
        IngestResult ringIngest = ingestWithParkRing();
        Point from = point(50, -20);
        Point to = point(50, 50);
        RouteResult initial = RouteResult.found(
                RulesTestGeometry.line(gf, 50, -20, 50, 50),
                Collections.emptyList());

        RouteResult verified = verification.verifyAndRerouteOnce(
                ringIngest, from, to, initial, ROUTING_DN, ACTUAL_DN);

        assertEquals(RouteStatus.NOT_FOUND, verified.getStatus());
    }

    private RouteResult routeTooCloseToOks() {
        double pairHalf200 = TestRouting.reference().getGabarits().spec(ROUTING_DN).getWidthM() / 2.0;
        double pairHalf300 = TestRouting.reference().getGabarits().spec(ACTUAL_DN).getWidthM() / 2.0;
        double minX200 = 100.0 - 5.0 - pairHalf200;
        double minX300 = 100.0 - 5.0 - pairHalf300;
        double x = (minX200 + minX300) / 2.0;
        LineString path = gf.createLineString(new Coordinate[] {
                new Coordinate(50, 80),
                new Coordinate(x, 80),
                new Coordinate(x, 140),
                new Coordinate(150, 140)
        });
        return RouteResult.found(path, Collections.emptyList());
    }

    private Point point(double x, double y) {
        return gf.createPoint(new Coordinate(x, y));
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

    private IngestResult ingestWithParkRing() {
        Polygon ring = parkRingWithHole();
        org.locationtech.jts.geom.Geometry ringWgs = utmPolygonToWgs(ring);
        Map<String, Object> props = new HashMap<>();
        props.put("restriction_type", "park");
        RawFeature feature = RawFeature.of("ring1", RawFeature.Kind.RESTRICTION, props, ringWgs, null);
        Map<String, org.locationtech.jts.geom.Geometry> restrictions = Collections.singletonMap("ring1", ringWgs);
        return new IngestResult(
                new IngestReport(),
                new ExistingNetwork(Collections.emptyList(), Collections.emptyList(), Collections.emptyList()),
                Collections.emptyList(),
                restrictions,
                Collections.singletonList(feature));
    }

    private Polygon parkRingWithHole() {
        Coordinate[] shell = new Coordinate[] {
                new Coordinate(0, 0),
                new Coordinate(100, 0),
                new Coordinate(100, 100),
                new Coordinate(0, 100),
                new Coordinate(0, 0)
        };
        Coordinate[] hole = new Coordinate[] {
                new Coordinate(40, 40),
                new Coordinate(60, 40),
                new Coordinate(60, 60),
                new Coordinate(40, 60),
                new Coordinate(40, 40)
        };
        return gf.createPolygon(
                gf.createLinearRing(shell),
                new org.locationtech.jts.geom.LinearRing[] {gf.createLinearRing(hole)});
    }
}
