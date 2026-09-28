package ru.heatnet.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.RulesTestGeometry;
import ru.heatnet.rules.SpatialConstraintBundle;
import ru.heatnet.rules.model.RestrictionFeature;

class RouteFinderTest {

    private static final int DN = 200;

    private GeometryFactory gf;
    private RestrictionEngineFactory engineFactory;
    private RouteFinderFactory routeFactory;

    @BeforeEach
    void setUp() {
        gf = TestRouting.utmFactory();
        engineFactory = TestRouting.engineFactory();
        routeFactory = TestRouting.routeFinderFactory();
    }

    @Test
    @DisplayName("обход парка: маршрут огибает запретную зону")
    void routeBypassesPark() {
        Polygon park = RulesTestGeometry.square(gf, 0, 0, 20);
        SpatialConstraintBundle bundle = bundle(Collections.singletonList(
                RulesTestGeometry.feature(TestRouting.reference(), gf, "park1", "park", park)));
        RouteFinder finder = routeFactory.create(bundle, DN);

        Point from = point(-10, 30);
        Point to = point(30, 30);
        RouteResult result = finder.findRoute(from, to, DN);

        assertTrue(result.isFound());
        assertFalse(result.getPathUtm().intersects(park));
    }

    @Test
    @DisplayName("дорога: маршрут пересекает полигон поперёк через портал")
    void routeCrossesRoadPerpendicular() {
        Polygon road = RulesTestGeometry.square(gf, 0, 0, 40);
        SpatialConstraintBundle bundle = bundle(Collections.singletonList(
                RulesTestGeometry.feature(TestRouting.reference(), gf, "road1", "road", road)));
        RouteFinder finder = routeFactory.create(bundle, DN);

        Point from = point(20, -20);
        Point to = point(20, 60);
        RouteResult result = finder.findRoute(from, to, DN);

        assertTrue(result.isFound());
        assertTrue(result.getLengthM() > 50.0);
    }

    @Test
    @DisplayName("ОКС в кольце запретов: NOT_FOUND без Exception")
    void destinationSurroundedReturnsNotFound() {
        Polygon ring = parkRingWithHole(gf);
        SpatialConstraintBundle bundle = bundle(Collections.singletonList(
                RulesTestGeometry.feature(TestRouting.reference(), gf, "ring", "park", ring)));
        RouteFinder finder = routeFactory.create(bundle, DN);

        Point from = point(50, -20);
        Point to = point(50, 50);
        RouteResult result = finder.findRoute(from, to, DN);

        assertEquals(RouteStatus.NOT_FOUND, result.getStatus());
    }

    private static Polygon parkRingWithHole(GeometryFactory gf) {
        org.locationtech.jts.geom.Coordinate[] shell = new org.locationtech.jts.geom.Coordinate[] {
                new org.locationtech.jts.geom.Coordinate(0, 0),
                new org.locationtech.jts.geom.Coordinate(100, 0),
                new org.locationtech.jts.geom.Coordinate(100, 100),
                new org.locationtech.jts.geom.Coordinate(0, 100),
                new org.locationtech.jts.geom.Coordinate(0, 0)
        };
        org.locationtech.jts.geom.Coordinate[] hole = new org.locationtech.jts.geom.Coordinate[] {
                new org.locationtech.jts.geom.Coordinate(40, 40),
                new org.locationtech.jts.geom.Coordinate(60, 40),
                new org.locationtech.jts.geom.Coordinate(60, 60),
                new org.locationtech.jts.geom.Coordinate(40, 60),
                new org.locationtech.jts.geom.Coordinate(40, 40)
        };
        return gf.createPolygon(
                gf.createLinearRing(shell),
                new org.locationtech.jts.geom.LinearRing[] {gf.createLinearRing(hole)});
    }

    @Test
    @DisplayName("старт на оси существующей теплосети: выход из буфера не NOT_FOUND")
    void startOnExistingHeatNetworkFindsRoute() {
        org.locationtech.jts.geom.LineString net = RulesTestGeometry.line(gf, 0, 0, 0, 80);
        SpatialConstraintBundle bundle = bundle(Collections.singletonList(
                RulesTestGeometry.lineFeature(TestRouting.reference(), gf, "hn1", "heat_network", net)));
        RouteFinder finder = routeFactory.create(bundle, DN);

        Point from = point(0, 40);
        Point to = point(40, 40);
        RouteResult result = finder.findRoute(from, to, DN);

        assertTrue(result.isFound(), "врезка лежит в буфере существующей сети — короткий выход должен быть разрешён");
        assertTrue(result.getLengthM() > 30.0);
    }

    @Test
    @DisplayName("старт у края парка: короткий выход из буфера — маршрут находится")
    void startJustInsideParkFindsRoute() {
        Polygon park = RulesTestGeometry.square(gf, 0, 0, 20);
        SpatialConstraintBundle bundle = bundle(Collections.singletonList(
                RulesTestGeometry.feature(TestRouting.reference(), gf, "parkEdge", "park", park)));
        RouteFinder finder = routeFactory.create(bundle, DN);

        Point from = point(1, 10);
        Point to = point(40, 10);
        RouteResult result = finder.findRoute(from, to, DN);

        assertTrue(result.isFound());
        assertTrue(result.getLengthM() > 20.0);
    }

    @Test
    @DisplayName("старт глубоко внутри парка: длинный префикс — NOT_FOUND (N-5)")
    void startDeepInsideParkIsRejected() {
        Polygon park = RulesTestGeometry.square(gf, 0, 0, 80);
        SpatialConstraintBundle bundle = bundle(Collections.singletonList(
                RulesTestGeometry.feature(TestRouting.reference(), gf, "parkDeep", "park", park)));
        RouteFinder finder = routeFactory.create(bundle, DN);

        Point from = point(40, 40);
        Point to = point(120, 40);
        RouteResult result = finder.findRoute(from, to, DN);

        assertEquals(RouteStatus.NOT_FOUND, result.getStatus());
    }

    @Test
    @DisplayName("прямой видимый путь без препятствий")
    void directRouteWhenClear() {
        SpatialConstraintBundle bundle = bundle(Collections.emptyList());
        RouteFinder finder = routeFactory.create(bundle, DN);

        Point from = point(0, 0);
        Point to = point(100, 0);
        RouteResult result = finder.findRoute(from, to, DN);

        assertTrue(result.isFound());
        assertEquals(100.0, result.getLengthM(), 1.0);
    }

    @Test
    @DisplayName("§2.1: K_угол = 1,0 — нестандартный поворот не умножает длину")
    void kAngleCancelled() {
        ru.heatnet.calc.reference.RulesConfig rules = TestRouting.reference().getRules();
        assertEquals(1.0, rules.getKAngle(), 1e-12);
        double standard = EdgeCostCalculatorTest.edgeCostWithTurn(rules, 0.0, 100.0, 200.0, 100.0);
        double sharp = EdgeCostCalculatorTest.edgeCostWithTurn(rules, 0.0, 100.0, 130.0, 70.0);
        assertEquals(100.0, standard, 1e-9);
        assertEquals(Math.hypot(30.0, 30.0), sharp, 1e-9);
    }

    @Test
    @DisplayName("§2.2: точка внутри своего полигона ОКС — маршрут находится, финал прямой")
    void ownOksPolygonFinalStraight() {
        Polygon building = RulesTestGeometry.square(gf, 0, 0, 40);
        SpatialConstraintBundle bundle = engineFactory.createBundle(Collections.emptyList(), DN)
                .withOwnApproach(building);
        RouteFinder finder = routeFactory.create(bundle, DN);

        Point from = point(-20, 20);
        Point to = point(5, 20);
        RouteResult result = finder.findRoute(from, to, DN);

        assertTrue(result.isFound());
        assertTrue(result.getLengthM() > 24.0);
        org.locationtech.jts.geom.Coordinate[] coords = result.getPathUtm().getCoordinates();
        org.locationtech.jts.geom.Coordinate last = coords[coords.length - 1];
        org.locationtech.jts.geom.Coordinate prev = coords[coords.length - 2];
        assertEquals(5.0, last.x, 0.2);
        assertEquals(20.0, last.y, 0.2);
        assertEquals(0.0, prev.x, 0.5);
        assertEquals(20.0, prev.y, 0.5);
    }

    private SpatialConstraintBundle bundle(List<RestrictionFeature> features) {
        return engineFactory.createBundle(features, DN);
    }

    private Point point(double x, double y) {
        return gf.createPoint(new org.locationtech.jts.geom.Coordinate(x, y));
    }
}
