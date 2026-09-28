package ru.heatnet.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.heatnet.calc.CalcException;
import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.reference.ReferenceData;

/** По тесту на каждую формулу разд. 6.1, 7, 8.1–8.3 и протокола; контрольные числа — вручную. */
class CostFormulasTest {

    private final ReferenceData ref = TestReference.get();

    @Test
    @DisplayName("C_уч спецпрохода: 145,2 м · 120 275 ₽/м (DN200) · 1,60 = 27 942 288 ₽")
    void newSpecialSegment() {
        // 145,2 · 120 275 = 17 463 930; · 1,6 = 27 942 288,0
        assertEquals(27_942_288L, new SegmentCostCalculator(ref.getDiameters()).cost(145.2, 200, 1.0, 1.60));
    }

    @Test
    @DisplayName("C_уч обычный: 60 м · 97 275 ₽/м (DN125) = 5 836 500 ₽; Kгл = 1,1 → 6 420 150 ₽")
    void newBaseSegmentAndDepth() {
        SegmentCostCalculator c = new SegmentCostCalculator(ref.getDiameters());
        assertEquals(5_836_500L, c.cost(60.0, 125, 1.0, 1.0));
        // 5 836 500 · 1,1 = 6 420 150
        assertEquals(6_420_150L, c.cost(60.0, 125, 1.1, 1.0));
    }

    @Test
    @DisplayName("округление до рубля HALF_UP: 0,5 м · 74 023 = 37 011,5 → 37 012 ₽")
    void roundingHalfUp() {
        assertEquals(37_012L, new SegmentCostCalculator(ref.getDiameters()).cost(0.5, 50, 1.0, 1.0));
    }

    @Test
    @DisplayName("C_рек: 75 м · 202 030 ₽/м (DN250) = 15 152 250 ₽")
    void reconstruction() {
        assertEquals(15_152_250L, new ReconstructionCostCalculator(ref.getDiameters()).cost(75.0, 250));
    }

    @Test
    @DisplayName("шкала камер 8.2 на границах: 200→3 млн, 250→5 млн, 500→5 млн, 600→8 млн, 1000→8 млн, 1200→12 млн; врезка 5 млн")
    void chamberScale() {
        ChamberCostScale s = new ChamberCostScale(ref.getRules());
        assertEquals(3_000_000L, s.costFor(50));
        assertEquals(3_000_000L, s.costFor(200));
        assertEquals(5_000_000L, s.costFor(250));
        assertEquals(5_000_000L, s.costFor(500));
        assertEquals(8_000_000L, s.costFor(600));
        assertEquals(8_000_000L, s.costFor(1000));
        assertEquals(12_000_000L, s.costFor(1200));
        assertEquals(12_000_000L, s.costFor(1400));
        assertEquals(5_000_000L, s.tieInCost());
        assertThrows(CalcException.class, () -> s.costFor(1100));
    }

    @Test
    @DisplayName("штраф 8.3: G = 12,5 т/ч → 100 000 000 + 6 250 000 = 106 250 000 ₽")
    void penalty() {
        UnconnectedPenaltyCalculator p = new UnconnectedPenaltyCalculator(ref.getRules());
        assertEquals(106_250_000L, p.penalty(12.5));
        assertEquals(100_000_000L, p.penalty(0.0));
        // 0,3 т/ч · 500 000 = 150 000 ровно (без 149 999,99 из double)
        assertEquals(100_150_000L, p.penalty(0.3));
    }

    @Test
    @DisplayName("Kгл 6.1: h ≤ 3 → 1; h = 4 → 1,1; h = 5,5 → 1,25; спуск 3→5 → (1 + 1,2)/2 = 1,1")
    void depthCoefficient() {
        DepthCoefficient k = new DepthCoefficient(ref.getDepth());
        assertEquals(1.0, k.of(0.7), 1e-12);
        assertEquals(1.0, k.of(3.0), 1e-12);
        assertEquals(1.1, k.of(4.0), 1e-12);
        assertEquals(1.25, k.of(5.5), 1e-12);
        assertEquals(1.1, k.average(3.0, 5.0), 1e-12);
        assertThrows(CalcException.class, () -> k.average(2.0, 4.0));
    }

    @Test
    @DisplayName("Kспец при наложении зон: max без перемножения (дорога 1,60 + газ 1,25 → 1,60, не 2,0); запрет приоритетнее")
    void specialPassOverlap() {
        SpecialPassCoefficient k = new SpecialPassCoefficient(ref.getRules());
        assertEquals(1.0, k.resolve(Collections.<String>emptyList()), 1e-12);
        assertEquals(1.60, k.resolve(Arrays.asList("road", "gas_pipeline")), 1e-12);
        assertEquals(1.75, k.resolve(Arrays.asList("road", "tram_tracks", "power_cable")), 1e-12);
        assertThrows(CalcException.class, () -> k.resolve(Arrays.asList("road", "park")));
        assertThrows(CalcException.class, () -> k.resolve(Collections.singletonList("unknown_type")));
    }

    @Test
    @DisplayName("K_угол: §2.1 отдельного удорожания нет — множитель 1,0 на любом угле")
    void angleCoefficient() {
        AngleCoefficient k = new AngleCoefficient(ref.getRules());
        assertEquals(1.0, k.forTurn(0), 1e-12);
        assertEquals(1.0, k.forTurn(47), 1e-12);
        assertEquals(1.0, k.forTurn(60), 1e-12);
        assertEquals(1.0, k.forTurn(30), 1e-12);
        assertTrue(k.isStandard(180));
        assertFalse(k.isStandard(112.5));
    }
}
