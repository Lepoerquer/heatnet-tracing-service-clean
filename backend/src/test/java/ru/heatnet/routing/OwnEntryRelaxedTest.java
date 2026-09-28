package ru.heatnet.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * AUDIT-13, §2.2 / Разъяснение №3: для доводки ветки заходы берутся через ту же ближайшую к точке стену своего
 * полигона (не глубже расстояния до границы + 1 м), а не только строго перпендикулярный луч ±0,2 м.
 */
class OwnEntryRelaxedTest {

    private static final int DN = 200;
    private static final double CLEARANCE = 5.5;

    private final GeometryFactory gf = TestRules.utmFactory();
    private final ReferenceData ref = TestRules.reference();
    private final RestrictionEngineFactory factory = TestRules.engineFactory();

    @Test
    @DisplayName("ENTRY-R1: все заходы доводки — через ближайшую стену и не глубже dmin + 1 м; их больше, чем строгих")
    void relaxedEntriesStayOnNearestWall() {
        Polygon own = RulesTestGeometry.square(gf, 0, 0, 40);
        // здание 40×40, точка в 3 м от верхней стены (y = 40)
        Coordinate p = new Coordinate(20, 37);
        RestrictionFeature oks = RulesTestGeometry.feature(ref, gf, "oks1", "oks_existing", own);
        SpatialConstraintEngine full = factory.create(Collections.singletonList(oks), DN);
        SpatialConstraintEngine approach = factory.create(Collections.<RestrictionFeature>emptyList(), DN);

        List<OwnEntryCandidates.Entry> strict = OwnEntryCandidates.compute(p, own, CLEARANCE, DN, full, approach, gf);
        List<OwnEntryCandidates.Entry> relaxed =
                OwnEntryCandidates.computeRelaxed(p, own, CLEARANCE, DN, full, approach, gf);
        assertFalse(strict.isEmpty());
        assertFalse(relaxed.isEmpty());

        Set<String> strictDirs = new HashSet<>();
        for (OwnEntryCandidates.Entry e : strict) {
            if (e.level == 0) {
                assertTrue(e.insideM <= 3.0 + OwnEntryCandidates.LEVEL_WINDOWS[0] + 1e-6);
                strictDirs.add(key(e.boundary));
            }
        }
        Set<String> relaxedDirs = new HashSet<>();
        for (OwnEntryCandidates.Entry e : relaxed) {
            assertEquals(0, e.level);
            assertEquals(40.0, e.boundary.y, 1e-6, "заход должен пересекать ближайшую (верхнюю) стену");
            assertTrue(e.insideM <= 3.0 + OwnEntryCandidates.RELAXED_EXCESS_M + 1e-6,
                    "заход не глубже расстояния до границы + 1 м: " + e.insideM);
            assertTrue(e.q.y > 40.0 + CLEARANCE - 1e-6, "Q — вне зоны отступа своего полигона");
            relaxedDirs.add(key(e.boundary));
        }
        assertTrue(relaxedDirs.size() > strictDirs.size(),
                "доводке нужно больше направлений через ту же стену: " + relaxedDirs.size() + " vs " + strictDirs.size());
    }

    private static String key(Coordinate c) {
        return Math.round(c.x * 100) + ":" + Math.round(c.y * 100);
    }
}
