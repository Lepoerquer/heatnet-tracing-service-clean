package ru.heatnet.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.RulesTestGeometry;
import ru.heatnet.rules.SpatialConstraintEngine;
import ru.heatnet.rules.TestRules;
import ru.heatnet.rules.model.RestrictionFeature;

/**
 * §2.2: заход по нормали к стене, ближайшей к точке подключения. Косой отрезок к углу и ход вплотную
 * к другой грани своего контура не принимаются.
 */
class OwnEntryRelaxedTest {

    private static final int DN = 200;
    private static final double CLEARANCE = 5.5;

    private final GeometryFactory gf = TestRules.utmFactory();
    private final ReferenceData ref = TestRules.reference();
    private final RestrictionEngineFactory factory = TestRules.engineFactory();

    @Test
    @DisplayName("ENTRY-R1: заход по нормали к стене, ближайшей к точке подключения")
    void perpendicularToNearestWall() {
        Polygon own = RulesTestGeometry.square(gf, 0, 0, 40);
        // здание 40×40, точка в 3 м от верхней стены (y = 40) — она ближе боковых (20 м)
        Coordinate p = new Coordinate(20, 37);
        RestrictionFeature oks = RulesTestGeometry.feature(ref, gf, "oks1", "oks_existing", own);
        SpatialConstraintEngine full = factory.create(Collections.singletonList(oks), DN);
        SpatialConstraintEngine approach = factory.create(Collections.<RestrictionFeature>emptyList(), DN);

        List<OwnEntryCandidates.Entry> strict = OwnEntryCandidates.compute(p, own, CLEARANCE, DN, full, approach, gf);
        List<OwnEntryCandidates.Entry> relaxed =
                OwnEntryCandidates.computeRelaxed(p, own, CLEARANCE, DN, full, approach, gf);
        assertFalse(strict.isEmpty());
        assertFalse(relaxed.isEmpty());

        for (OwnEntryCandidates.Entry e : strict) {
            if (e.level == 0) {
                assertNearestTopWall(e);
            }
        }
        for (OwnEntryCandidates.Entry e : relaxed) {
            assertEquals(0, e.level);
            assertNearestTopWall(e);
        }
    }

    @Test
    @DisplayName("ENTRY-R2: косой отрезок мимо выступа — не перпендикуляр к ближайшей стене")
    void obliqueGrazeIsRejected() {
        Polygon own = gf.createPolygon(new Coordinate[] {
                new Coordinate(0, 0),
                new Coordinate(18, 0),
                new Coordinate(18, -4),
                new Coordinate(24, -4),
                new Coordinate(24, 0),
                new Coordinate(40, 0),
                new Coordinate(40, 20),
                new Coordinate(0, 20),
                new Coordinate(0, 0)
        });
        Coordinate p = new Coordinate(32, 6);
        // прямая через точку в 5 см под углом выступа (24, −4), затем в корпус восточнее выступа
        Coordinate skim = new Coordinate(16, -14.1);
        assertFalse(OwnEntryCandidates.straightPerpendicularApproach(skim, p, own, CLEARANCE, gf));

        Coordinate perpendicular = new Coordinate(32, -8);
        assertTrue(OwnEntryCandidates.straightPerpendicularApproach(perpendicular, p, own, CLEARANCE, gf));

        RestrictionFeature oks = RulesTestGeometry.feature(ref, gf, "oks1", "oks_existing", own);
        SpatialConstraintEngine full = factory.create(Collections.singletonList(oks), DN);
        SpatialConstraintEngine approach = factory.create(Collections.<RestrictionFeature>emptyList(), DN);
        List<OwnEntryCandidates.Entry> entries =
                OwnEntryCandidates.compute(p, own, CLEARANCE, DN, full, approach, gf);
        assertFalse(entries.isEmpty());
        for (OwnEntryCandidates.Entry e : entries) {
            if (e.level != 0) {
                continue;
            }
            assertEquals(32.0, e.boundary.x, 0.3, "нормаль к ближайшей (южной) стене");
            assertEquals(0.0, e.boundary.y, 0.3);
            assertEquals(32.0, e.q.x, 0.3);
            assertTrue(e.q.y < -CLEARANCE + 0.2);
            assertTrue(OwnEntryCandidates.straightPerpendicularApproach(e.q, p, own, CLEARANCE, gf));
        }
    }

    private static void assertNearestTopWall(OwnEntryCandidates.Entry e) {
        assertEquals(40.0, e.boundary.y, 0.05, "ближайшая стена — верхняя");
        assertEquals(20.0, e.boundary.x, 0.2, "основание перпендикуляра из точки подключения");
        assertEquals(20.0, e.q.x, 0.3, "Q лежит на той же нормали");
        assertTrue(e.q.y > 40.0 + CLEARANCE - 0.05, "Q — вне зоны отступа своего полигона");
        assertEquals(3.0, e.insideM, 0.15);
    }
}
