package ru.heatnet.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.rules.model.CrossingResult;
import ru.heatnet.rules.model.RestrictionFeature;
import ru.heatnet.rules.model.SpecialSection;

class SpatialConstraintEngineTest {

    private static final int DN = 200;

    private ReferenceData reference;
    private GeometryFactory gf;
    private RestrictionEngineFactory factory;

    @BeforeEach
    void setUp() {
        reference = TestRules.reference();
        gf = TestRules.utmFactory();
        factory = TestRules.engineFactory();
    }

    @Test
    @DisplayName("park: отрезок через полигон заблокирован, обход снаружи — нет")
    void parkBlocksInterior() {
        Polygon park = RulesTestGeometry.square(gf, 0, 0, 20);
        SpatialConstraintEngine engine = engine(Collections.singletonList(
                RulesTestGeometry.feature(reference, gf, "park1", "park", park)));

        LineString through = RulesTestGeometry.line(gf, -5, 10, 25, 10);
        LineString bypass = RulesTestGeometry.line(gf, -5, 30, 25, 30);

        assertTrue(engine.isSegmentBlocked(through, DN));
        assertFalse(engine.isSegmentBlocked(bypass, DN));
    }

    @Test
    @DisplayName("road: продольный ход по полигону заблокирован; поперечный через створ ≥45° — нет")
    void roadBarrierAndPortal() {
        Polygon road = RulesTestGeometry.square(gf, 0, 0, 40);
        SpatialConstraintEngine engine = engine(Collections.singletonList(
                RulesTestGeometry.feature(reference, gf, "road1", "road", road)));

        LineString along = RulesTestGeometry.line(gf, 5, 5, 35, 5);
        LineString across = RulesTestGeometry.line(gf, 20, -10, 20, 50);

        assertTrue(engine.isSegmentBlocked(along, DN));
        assertFalse(engine.isSegmentBlocked(across, DN));
        assertTrue(engine.isRoadCrossingAngleValid(across, "road1", DN));
    }

    @Test
    @DisplayName("дорога: угол 50° к оси — OK, 30° — запрет")
    void roadCrossingAngles() {
        Polygon road = RulesTestGeometry.square(gf, 0, 0, 100);
        RestrictionFeature roadFeature = RulesTestGeometry.feature(reference, gf, "road1", "road", road);
        SpatialConstraintEngine engine = engine(Collections.singletonList(roadFeature));

        LineString ok50 = RulesTestGeometry.line(gf, 0, 0, 100, 119.1);   // ~50° к оси X
        LineString bad30 = RulesTestGeometry.line(gf, 0, 0, 100, 57.7);   // ~30° к оси X

        assertTrue(engine.isRoadCrossingAngleValid(ok50, "road1", DN));
        assertFalse(engine.isRoadCrossingAngleValid(bad30, "road1", DN));
    }

    @Test
    @DisplayName("наложение спецзон: max(K_спец) = 1,75 (трамвай > кабель)")
    void overlapMaxKSpec() {
        Polygon tram = RulesTestGeometry.square(gf, 0, 0, 50);
        Polygon cable = RulesTestGeometry.square(gf, 10, 10, 30);
        SpatialConstraintEngine engine = engine(Arrays.asList(
                RulesTestGeometry.feature(reference, gf, "t1", "tram_tracks", tram),
                RulesTestGeometry.feature(reference, gf, "c1", "power_cable", cable)));

        LineString segment = RulesTestGeometry.line(gf, 25, -10, 25, 60);
        List<CrossingResult> crossings = engine.findCrossings(segment, DN);
        assertFalse(crossings.isEmpty());
        double maxK = 0.0;
        for (CrossingResult c : crossings) {
            maxK = Math.max(maxK, c.getKSpec());
        }
        assertEquals(1.75, maxK, 1e-9);

        List<SpecialSection> sections = engine.extractSpecialSections(segment, DN);
        assertFalse(sections.isEmpty());
        double maxSectionK = 0.0;
        for (SpecialSection section : sections) {
            maxSectionK = Math.max(maxSectionK, section.getKSpec());
        }
        assertEquals(1.75, maxSectionK, 1e-9);
    }

    @Test
    @DisplayName("MultiPolygon дорога: без ClassCast, поперечный ход ≥45° к границе")
    void multiPolygonRoad() {
        Polygon a = RulesTestGeometry.square(gf, 0, 0, 40);
        Polygon b = RulesTestGeometry.square(gf, 80, 0, 40);
        org.locationtech.jts.geom.MultiPolygon multi = gf.createMultiPolygon(new Polygon[] {a, b});
        RestrictionFeature road = new RestrictionFeature("road_mp", "road",
                reference.getRules().restriction("road"), multi);
        SpatialConstraintEngine engine = engine(Collections.singletonList(road));

        LineString across = RulesTestGeometry.line(gf, 20, -10, 20, 50);
        LineString along = RulesTestGeometry.line(gf, 5, 5, 35, 5);
        assertFalse(engine.isSegmentBlocked(across, DN));
        assertTrue(engine.isSegmentBlocked(along, DN));
    }

    @Test
    @DisplayName("§4: ход рядом с газом ближе min_offset запрещён, пересечение — спецпроход")
    void nearbyGasBlocksParallel() {
        LineString gas = RulesTestGeometry.line(gf, 0, 0, 100, 0);
        SpatialConstraintEngine engine = engine(Collections.singletonList(
                RulesTestGeometry.lineFeature(reference, gf, "g1", "gas_pipeline", gas)));

        LineString tooClose = RulesTestGeometry.line(gf, 10, 1.0, 90, 1.0);
        LineString far = RulesTestGeometry.line(gf, 10, 8.0, 90, 8.0);
        LineString across = RulesTestGeometry.line(gf, 50, -10, 50, 10);

        assertTrue(engine.isSegmentBlocked(tooClose, DN));
        assertFalse(engine.isSegmentBlocked(far, DN));
        assertFalse(engine.isSegmentBlocked(across, DN));
        assertFalse(engine.findCrossings(across, DN).isEmpty());
    }

    @Test
    @DisplayName("§4: два отдельных пересечения одного газа — два спецпрохода")
    void twoSeparateGasCrossings() {
        LineString gas = RulesTestGeometry.line(gf, 0, 0, 120, 0);
        SpatialConstraintEngine engine = engine(Collections.singletonList(
                RulesTestGeometry.lineFeature(reference, gf, "g1", "gas_pipeline", gas)));
        LineString uTurn = gf.createLineString(new org.locationtech.jts.geom.Coordinate[] {
                new org.locationtech.jts.geom.Coordinate(20, -10),
                new org.locationtech.jts.geom.Coordinate(20, 10),
                new org.locationtech.jts.geom.Coordinate(80, 10),
                new org.locationtech.jts.geom.Coordinate(80, -10)
        });
        List<SpecialSection> sections = engine.extractSpecialSections(uTurn, DN);
        assertTrue(sections.size() >= 2);
    }

    @Test
    @DisplayName("запрет приоритетнее спецпрохода: park поверх road")
    void prohibitedPriorityOverSpecial() {
        Polygon road = RulesTestGeometry.square(gf, 0, 0, 60);
        Polygon park = RulesTestGeometry.square(gf, 20, 20, 20);
        SpatialConstraintEngine engine = engine(Arrays.asList(
                RulesTestGeometry.feature(reference, gf, "road1", "road", road),
                RulesTestGeometry.feature(reference, gf, "park1", "park", park)));

        LineString throughPark = RulesTestGeometry.line(gf, 30, 0, 30, 60);
        assertTrue(engine.isSegmentBlocked(throughPark, DN));
    }

    @Test
    @DisplayName("oks_existing DN300: отступ 4,9 м → blocked, 5,1 м → ok (DoD M2)")
    void oksExistingOffsetToleranceDn300() {
        Polygon oks = RulesTestGeometry.square(gf, 100, 100, 30);
        int dn = 300;
        SpatialConstraintEngine engine = factory.create(Collections.singletonList(
                RulesTestGeometry.feature(reference, gf, "oks1", "oks_existing", oks)), dn);
        double pairHalf = reference.getGabarits().spec(dn).getWidthM() / 2.0;
        double westEdge = 100.0;
        double xTooClose = westEdge - 4.9 - pairHalf;
        double xOk = westEdge - 5.1 - pairHalf;

        LineString blocked = RulesTestGeometry.line(gf, xTooClose, 90, xTooClose, 140);
        LineString allowed = RulesTestGeometry.line(gf, xOk, 90, xOk, 140);

        assertTrue(engine.isSegmentBlocked(blocked, dn));
        assertFalse(engine.isSegmentBlocked(allowed, dn));
    }

    @Test
    @DisplayName("railway: запрет 1,0 м (team_default clarifications №9)")
    void railwayProhibited() {
        Polygon railway = RulesTestGeometry.square(gf, 0, 0, 10);
        SpatialConstraintEngine engine = engine(Collections.singletonList(
                RulesTestGeometry.feature(reference, gf, "rw1", "railway", railway)));

        LineString through = RulesTestGeometry.line(gf, 5, -5, 5, 15);
        assertTrue(engine.isSegmentBlocked(through, DN));
    }

    private SpatialConstraintEngine engine(List<RestrictionFeature> features) {
        return factory.create(features, DN);
    }
}
