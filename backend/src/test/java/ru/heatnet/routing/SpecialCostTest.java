package ru.heatnet.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
import ru.heatnet.rules.model.SpecialSection;

/**
 * AUDIT-13: стоимость звена в «м × Kспец» считается по кускам (§6 приложения: Kспец только на специальном
 * участке; Разъяснение №8: на наложении — наибольший Kспец), а не «вся длина × наибольший Kспец».
 */
class SpecialCostTest {

    private static final int DN = 200;

    private final GeometryFactory gf = TestRules.utmFactory();
    private final ReferenceData ref = TestRules.reference();
    private final RestrictionEngineFactory factory = TestRules.engineFactory();

    private LineString line(double... xy) {
        Coordinate[] cs = new Coordinate[xy.length / 2];
        for (int i = 0; i < cs.length; i++) {
            cs[i] = new Coordinate(xy[2 * i], xy[2 * i + 1]);
        }
        return gf.createLineString(cs);
    }

    private SpecialSection section(double x1, double y1, double x2, double y2, double k) {
        return new SpecialSection(line(x1, y1, x2, y2), k, Collections.singleton("test"));
    }

    @Test
    @DisplayName("SPEC-1: без спецучастков стоимость = длина")
    void noSections() {
        LineString seg = line(0, 0, 150, 0);
        assertEquals(150.0, SpecialCost.weightedLength(seg, null), 1e-9);
        assertEquals(150.0, SpecialCost.weightedLength(seg, Collections.<SpecialSection>emptyList()), 1e-9);
    }

    @Test
    @DisplayName("SPEC-2: 150 м через дорогу (спецучасток 18 м, K=1,60) = 160,8, а не 240")
    void onlySpecialPartIsWeighted() {
        LineString seg = line(0, 0, 150, 0);
        List<SpecialSection> s = Collections.singletonList(section(60, 0, 78, 0, 1.60));
        assertEquals(132.0 + 18.0 * 1.60, SpecialCost.weightedLength(seg, s), 1e-9);
    }

    @Test
    @DisplayName("SPEC-3: наложение — берётся наибольший K, коэффициенты не суммируются (Разъяснение №8)")
    void overlapTakesMax() {
        LineString seg = line(0, 0, 150, 0);
        // [10;30] K=1,60 и [20;40] K=2,00: 10 + 10·1,6 + 20·2,0 + 110 = 176
        List<SpecialSection> s = Arrays.asList(section(10, 0, 30, 0, 1.60), section(40, 0, 20, 0, 2.00));
        assertEquals(176.0, SpecialCost.weightedLength(seg, s), 1e-9);
    }

    @Test
    @DisplayName("SPEC-4: ломаная — позиции спецучастка считаются вдоль пути, K≤1 не учитывается")
    void polylineAndNeutralSections() {
        LineString path = line(0, 0, 100, 0, 100, 50);
        List<SpecialSection> s = Arrays.asList(
                section(100, 10, 100, 30, 1.25),
                section(0, 0, 50, 0, 1.0));
        assertEquals(150.0 + 20.0 * 0.25, SpecialCost.weightedLength(path, s), 1e-9);
    }

    @Test
    @DisplayName("SPEC-5: реальная дорога из движка — прямая через дорогу дешевле «вся длина × K»")
    void engineRoadCrossing() {
        Polygon road = RulesTestGeometry.square(gf, 60, -50, 12);
        SpatialConstraintEngine engine = factory.create(Collections.singletonList(
                RulesTestGeometry.feature(ref, gf, "road1", "road", road)), DN);
        LineString seg = line(0, -44, 150, -44);
        List<SpecialSection> sections = engine.extractSpecialSections(seg, DN);
        assertFalse(sections.isEmpty());
        double maxK = 1.0;
        double expected = seg.getLength();
        for (SpecialSection sec : sections) {
            maxK = Math.max(maxK, sec.getKSpec());
            expected += sec.getGeometry().getLength() * (sec.getKSpec() - 1.0);
        }
        assertTrue(maxK > 1.0);
        double cost = SpecialCost.weightedLength(seg, sections);
        assertEquals(expected, cost, 1e-6);
        assertTrue(cost < seg.getLength() * maxK - 1.0, "Kспец не должен умножать всю длину звена");
    }
}
