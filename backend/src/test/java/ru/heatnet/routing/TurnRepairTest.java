package ru.heatnet.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.RulesTestGeometry;
import ru.heatnet.rules.SpatialConstraintEngine;
import ru.heatnet.rules.TestRules;
import ru.heatnet.rules.model.RestrictionFeature;

/** §2.1: «Допускается произвольный угол поворота до 90° включительно». */
class TurnRepairTest {

    private static final int DN = 200;

    private final ReferenceData ref = TestRules.reference();
    private final GeometryFactory gf = TestRules.utmFactory();
    private final RestrictionEngineFactory factory = TestRules.engineFactory();

    private SpatialConstraintEngine freeEngine() {
        return factory.create(Collections.<RestrictionFeature>emptyList(), DN);
    }

    private LineString line(double... xy) {
        Coordinate[] cs = new Coordinate[xy.length / 2];
        for (int i = 0; i < cs.length; i++) {
            cs[i] = new Coordinate(xy[2 * i], xy[2 * i + 1]);
        }
        return gf.createLineString(cs);
    }

    @Test
    @DisplayName("TURN-1: отклонение считается от продолжения — 0° прямо, 180° разворот")
    void deviationConvention() {
        assertEquals(0.0, TurnRepair.deviationDeg(
                new Coordinate(0, 0), new Coordinate(1, 0), new Coordinate(2, 0)), 1e-9);
        assertEquals(90.0, TurnRepair.deviationDeg(
                new Coordinate(0, 0), new Coordinate(1, 0), new Coordinate(1, 1)), 1e-9);
        assertEquals(180.0, TurnRepair.deviationDeg(
                new Coordinate(0, 0), new Coordinate(1, 0), new Coordinate(0, 0)), 1e-9);
    }

    @Test
    @DisplayName("TURN-2: разворот 180° на свободном поле спрямляется")
    void spikeIsRemovedWhenShortcutIsFree() {
        LineString spike = line(0, 0, 100, 0, 60, 0.5, 60, 200);
        assertTrue(TurnRepair.violations(spike, 90.0) > 0, "предусловие: излом есть");
        LineString fixed = TurnRepair.repair(spike, freeEngine(), DN);
        assertEquals(0, TurnRepair.violations(fixed, 90.0), "после починки изломов быть не должно");
        assertEquals(spike.getCoordinateN(0), fixed.getCoordinateN(0), "начало не меняется");
        assertEquals(spike.getCoordinateN(spike.getNumPoints() - 1),
                fixed.getCoordinateN(fixed.getNumPoints() - 1), "конец не меняется");
    }

    @Test
    @DisplayName("TURN-3: спрямление не длиннее исходной трассы")
    void repairNeverIncreasesLength() {
        LineString spike = line(0, 0, 100, 0, 60, 0.5, 60, 200);
        LineString fixed = TurnRepair.repair(spike, freeEngine(), DN);
        assertTrue(fixed.getLength() <= spike.getLength() + 1e-9,
                "починка только удаляет вершины: " + fixed.getLength() + " против " + spike.getLength());
    }

    @Test
    @DisplayName("TURN-4: угол ровно 90° допустим и не трогается")
    void rightAngleIsKept() {
        LineString right = line(0, 0, 100, 0, 100, 100);
        LineString fixed = TurnRepair.repair(right, freeEngine(), DN);
        assertEquals(right.getNumPoints(), fixed.getNumPoints(), "90° — в пределах §2.1");
        assertEquals(0, TurnRepair.violations(right, 90.0));
    }

    @Test
    @DisplayName("TURN-5: если спрямление упирается в запрет — вершина сохраняется, трасса не ломается")
    void blockedShortcutKeepsVertex() {
        // парк ровно на пути спрямления
        Polygon park = RulesTestGeometry.square(gf, 40, -30, 60);
        SpatialConstraintEngine e = factory.create(Collections.singletonList(
                new RestrictionFeature("p1", "park", ref.getRules().restriction("park"), park)), DN);
        LineString spike = line(0, 0, 100, 0, 60, 0.5, 60, 200);
        LineString fixed = TurnRepair.repair(spike, e, DN);
        assertTrue(fixed.getNumPoints() >= 2, "трасса остаётся связной");
        assertEquals(spike.getCoordinateN(0), fixed.getCoordinateN(0));
        assertEquals(spike.getCoordinateN(spike.getNumPoints() - 1),
                fixed.getCoordinateN(fixed.getNumPoints() - 1));
    }

    @Test
    @DisplayName("TURN-6: прямая и слишком короткая линия обрабатываются без изменений")
    void degenerateInputs() {
        LineString straight = line(0, 0, 50, 0, 100, 0);
        assertEquals(0, TurnRepair.violations(straight, 90.0));
        assertEquals(3, TurnRepair.repair(straight, freeEngine(), DN).getNumPoints());
        LineString two = line(0, 0, 10, 0);
        assertEquals(2, TurnRepair.repair(two, freeEngine(), DN).getNumPoints());
        assertEquals(0, TurnRepair.violations(two, 90.0));
    }

    @Test
    @DisplayName("TURN-7: несколько изломов подряд убираются все")
    void multipleSpikes() {
        LineString zigzag = line(0, 0, 100, 0, 50, 1, 150, 2, 100, 3, 200, 4);
        assertTrue(TurnRepair.violations(zigzag, 90.0) >= 2, "предусловие: изломов несколько");
        LineString fixed = TurnRepair.repair(zigzag, freeEngine(), DN);
        assertEquals(0, TurnRepair.violations(fixed, 90.0));
    }
}
