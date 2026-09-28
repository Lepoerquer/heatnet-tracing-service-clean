package ru.heatnet.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.rules.TestRules;

/**
 * AUDIT-13: вершины графа видимости выносятся наружу по биссектрисе угла контура (и по нормали к ближайшему звену
 * для точек на границе), а не «от центроида». У П-образного здания центроид лежит во дворе, и «от центроида»
 * заталкивал внутренние углы двора внутрь здания: такие вершины отбрасывались, обход двора становился длиннее.
 */
class RoutingGeometryOutwardTest {

    private static final double OUT = 0.4;

    private final GeometryFactory gf = TestRules.utmFactory();

    /** П-образное здание: основание 30×10, два крыла 10×20, двор x∈[10;20], y∈[10;30]. */
    private Polygon uShape() {
        return gf.createPolygon(new Coordinate[] {
                new Coordinate(0, 0), new Coordinate(30, 0), new Coordinate(30, 30), new Coordinate(20, 30),
                new Coordinate(20, 10), new Coordinate(10, 10), new Coordinate(10, 30), new Coordinate(0, 30),
                new Coordinate(0, 0)});
    }

    @Test
    @DisplayName("OUT-1: внутренний (вогнутый) угол двора остаётся снаружи здания")
    void reflexCourtyardCorner() {
        Polygon u = uShape();
        Coordinate prev = new Coordinate(20, 10);
        Coordinate v = new Coordinate(10, 10);
        Coordinate next = new Coordinate(10, 30);
        // старый способ «от центроида» (центроид ≈ (15; 13,6) во дворе) уводит вершину в основание здания
        assertTrue(u.contains(gf.createPoint(RoutingGeometry.outwardVertex(v, u, OUT))));
        Coordinate c = RoutingGeometry.outwardVertex(prev, v, next, u, OUT);
        assertFalse(u.contains(gf.createPoint(c)));
        assertEquals(OUT, c.distance(v), 1e-9);
        assertTrue(c.x > 10.0 && c.y > 10.0, "вершина должна уйти во двор: " + c);
    }

    @Test
    @DisplayName("OUT-2: выпуклый угол выносится наружу по биссектрисе на заданное расстояние")
    void convexCorner() {
        Polygon u = uShape();
        Coordinate c = RoutingGeometry.outwardVertex(new Coordinate(0, 30), new Coordinate(0, 0),
                new Coordinate(30, 0), u, OUT);
        assertFalse(u.contains(gf.createPoint(c)));
        assertEquals(OUT, c.distance(new Coordinate(0, 0)), 1e-9);
        assertTrue(c.x < 0.0 && c.y < 0.0);
    }

    @Test
    @DisplayName("OUT-4: точка границы в вершине (угол здания) — по биссектрисе, а не вдоль соседней стены")
    void boundaryPointAtCorner() {
        Polygon u = uShape();
        Coordinate c = RoutingGeometry.outwardFromBoundary(new Coordinate(0, 0), u, OUT);
        assertFalse(u.covers(gf.createPoint(c)), "точка не должна остаться на границе: " + c);
        assertEquals(OUT, c.distance(new Coordinate(0, 0)), 1e-9);
        assertTrue(c.x < 0.0 && c.y < 0.0);
        Coordinate inner = RoutingGeometry.outwardFromBoundary(new Coordinate(10, 10), u, OUT);
        assertFalse(u.covers(gf.createPoint(inner)));
        assertTrue(inner.x > 10.0 && inner.y > 10.0, "внутренний угол двора — во двор: " + inner);
    }

    @Test
    @DisplayName("OUT-3: точка на стене двора выносится по нормали к стене во двор")
    void boundaryPointInCourtyard() {
        Polygon u = uShape();
        Coordinate onWall = new Coordinate(15, 10);
        assertTrue(u.contains(gf.createPoint(RoutingGeometry.outwardVertex(onWall, u, OUT))));
        Coordinate c = RoutingGeometry.outwardFromBoundary(onWall, u, OUT);
        assertFalse(u.contains(gf.createPoint(c)));
        assertEquals(15.0, c.x, 1e-9);
        assertEquals(10.0 + OUT, c.y, 1e-9);
    }
}
