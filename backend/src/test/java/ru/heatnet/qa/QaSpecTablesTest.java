package ru.heatnet.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.reference.DiameterSpec;
import ru.heatnet.calc.reference.GabaritSpec;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.calc.reference.RestrictionRule;
import ru.heatnet.calc.reference.RulesConfig;
import ru.heatnet.cost.ChamberCostScale;
import ru.heatnet.cost.UnconnectedPenaltyCalculator;

/**
 * QA (роль 4). НЕЗАВИСИМАЯ сверка config/*.yaml с PDF «Техническое-приложение» (табл. 4.1, 4.2, 5.1, разд. 8, 9).
 * Константы ниже перепечатаны с самого PDF (страницы 2-4, 5, 7-8), а не скопированы из YAML/кода.
 */
class QaSpecTablesTest {

    private final ReferenceData ref = TestReference.get();

    // dn, capacity t/h, max length m, new rub/m, recon rub/m  (табл. 4.1)
    private static final Object[][] T41 = {
            {50, 3.5, 181.0, 74023L, 96180L},
            {65, 8.3, 245.0, 78631L, 109989L},
            {80, 13.2, 327.0, 83530L, 117582L},
            {100, 22.3, 419.0, 89748L, 133694L},
            {125, 40.2, 554.0, 97275L, 148030L},
            {150, 65.1, 696.0, 105507L, 152295L},
            {200, 152.3, 1042.0, 120275L, 181766L},
            {250, 274.9, 1379.0, 135323L, 202030L},
            {300, 437.4, 1718.0, 150022L, 228707L},
            {400, 943.1, 2477.0, 190299L, 271317L},
            {500, 1663.4, 3245.0, 224137L, 333884L},
            {600, 2627.7, 4037.0, 264790L, 372703L},
            {700, 3735.1, 4775.0, 324298L, 439571L},
            {800, 5296.8, 5644.0, 325996L, 489918L},
            {900, 7165.0, 6518.0, 327693L, 553607L},
            {1000, 9391.8, 7419.0, 418777L, 606679L},
            {1200, 15012.8, 9288.0, 428074L, 825692L},
            {1400, 22501.9, 11276.0, 683417L, 978584L},
    };

    // dn, outer diameter, gap, width, height (табл. 4.2)
    private static final Object[][] T42 = {
            {50, 0.125, 0.150, 0.400, 0.125},
            {65, 0.140, 0.150, 0.430, 0.140},
            {80, 0.160, 0.150, 0.470, 0.160},
            {100, 0.180, 0.150, 0.510, 0.180},
            {125, 0.225, 0.150, 0.600, 0.225},
            {150, 0.250, 0.150, 0.650, 0.250},
            {200, 0.315, 0.250, 0.880, 0.315},
            {250, 0.400, 0.250, 1.050, 0.400},
            {300, 0.450, 0.250, 1.150, 0.450},
            {400, 0.560, 0.250, 1.370, 0.560},
            {500, 0.710, 0.250, 1.670, 0.710},
            {600, 0.800, 0.250, 1.850, 0.800},
            {700, 0.900, 0.250, 2.050, 0.900},
            {800, 1.000, 0.250, 2.250, 1.000},
            {900, 1.100, 0.250, 2.450, 1.100},
            {1000, 1.200, 0.250, 2.650, 1.200},
            {1200, 1.425, 0.250, 3.100, 1.425},
            {1400, 1.600, 0.250, 3.450, 1.600},
    };

    @Test
    @DisplayName("QA-SPEC-1: табл. 4.1 в YAML == PDF, все 18 строк, все 5 колонок")
    void table41MatchesPdf() {
        assertEquals(T41.length, ref.getDiameters().size());
        for (Object[] row : T41) {
            DiameterSpec s = ref.getDiameters().spec((Integer) row[0]);
            assertEquals((double) row[1], s.getCapacityTph(), 0.0, "capacity DN" + row[0]);
            assertEquals((double) row[2], s.getMaxLengthM(), 0.0, "maxLen DN" + row[0]);
            assertEquals((long) row[3], s.getNewCostRubPerM(), "new cost DN" + row[0]);
            assertEquals((long) row[4], s.getReconstructionCostRubPerM(), "recon cost DN" + row[0]);
        }
    }

    @Test
    @DisplayName("QA-SPEC-2: табл. 4.2 в YAML == PDF (ширина = 2·D + просвет — внутренняя согласованность)")
    void table42MatchesPdf() {
        for (Object[] row : T42) {
            GabaritSpec g = ref.getGabarits().spec((Integer) row[0]);
            assertEquals((double) row[1], g.getOuterDiameterM(), 1e-12, "outer DN" + row[0]);
            assertEquals((double) row[2], g.getGapM(), 1e-12, "gap DN" + row[0]);
            assertEquals((double) row[3], g.getWidthM(), 1e-12, "width DN" + row[0]);
            assertEquals((double) row[4], g.getHeightM(), 1e-12, "height DN" + row[0]);
            // приложение: «расчётная ширина пары = 2·D + просвет»
            assertEquals(2 * g.getOuterDiameterM() + g.getGapM(), g.getWidthM(), 1e-9, "формула ширины DN" + row[0]);
        }
    }

    @Test
    @DisplayName("QA-SPEC-3: табл. 5.1 — отступы, углы, K_спец, зоны спецучастков")
    void table51MatchesPdf() {
        RulesConfig r = ref.getRules();
        // существующий ОКС: 5 м (<500), 7 м (500–800), 9 м (>=900)
        RestrictionRule oks = r.restriction("oks_existing");
        assertTrue(oks.isProhibited());
        for (int dn : new int[] {50, 65, 80, 100, 125, 150, 200, 250, 300, 400}) {
            assertEquals(5.0, oks.minOffsetM(dn), 0.0, "oks DN" + dn);
        }
        for (int dn : new int[] {500, 600, 700, 800}) {
            assertEquals(7.0, oks.minOffsetM(dn), 0.0, "oks DN" + dn);
        }
        for (int dn : new int[] {900, 1000, 1200, 1400}) {
            assertEquals(9.0, oks.minOffsetM(dn), 0.0, "oks DN" + dn);
        }
        for (String t : new String[] {"park", "social_area", "prohibited_site", "water"}) {
            assertTrue(r.restriction(t).isProhibited(), t);
            assertEquals(1.0, r.restriction(t).minOffsetM(200), 0.0, t);
        }
        RestrictionRule road = r.restriction("road");
        assertFalse(road.isProhibited());
        assertEquals(1.5, road.minOffsetM(200), 0.0);
        assertEquals(45.0, road.getMinCrossingAngleDeg(), 0.0);
        assertEquals(1.60, road.getKSpec(), 0.0);
        assertEquals(3.0, road.getZoneMarginM(), 0.0);
        RestrictionRule tram = r.restriction("tram_tracks");
        assertEquals(1.5, tram.minOffsetM(200), 0.0);
        assertEquals(45.0, tram.getMinCrossingAngleDeg(), 0.0);
        assertEquals(1.75, tram.getKSpec(), 0.0);
        assertEquals(3.0, tram.getZoneMarginM(), 0.0);
        RestrictionRule gas = r.restriction("gas_pipeline");
        assertEquals(2.0, gas.minOffsetM(200), 0.0);
        assertEquals(1.25, gas.getKSpec(), 0.0);
        assertEquals(2.0, gas.getZoneMarginM(), 0.0);
        RestrictionRule cable = r.restriction("power_cable");
        assertEquals(2.0, cable.minOffsetM(200), 0.0);
        assertEquals(1.15, cable.getKSpec(), 0.0);
        assertEquals(2.0, cable.getZoneMarginM(), 0.0);
        RestrictionRule heat = r.restriction("heat_network");
        assertEquals(1.0, heat.minOffsetM(200), 0.0);
        assertEquals(1.05, heat.getKSpec(), 0.0);
        assertEquals(2.0, heat.getZoneMarginM(), 0.0);
    }

    @Test
    @DisplayName("QA-SPEC-4: разд. 8.2/8.3/9 — камеры 3/5/8/12 млн (границы DN), врезка 5 млн, штраф, веса S")
    void section8and9() {
        RulesConfig r = ref.getRules();
        ChamberCostScale cs = new ChamberCostScale(r);
        int[][] scale = {{50, 3_000_000}, {200, 3_000_000}, {250, 5_000_000}, {500, 5_000_000},
                {600, 8_000_000}, {1000, 8_000_000}, {1200, 12_000_000}, {1400, 12_000_000}};
        for (int[] row : scale) {
            assertEquals(row[1], cs.costFor(row[0]), "камера DN" + row[0]);
        }
        assertEquals(5_000_000L, cs.tieInCost());
        UnconnectedPenaltyCalculator pc = new UnconnectedPenaltyCalculator(r);
        assertEquals(100_000_000L + 500_000L * 10, pc.penalty(10.0));
        assertEquals(100_000_000L + 4_080_000L, pc.penalty(8.16)); // 500000*8.16 = 4 080 000
        assertEquals(0.7, r.getWeightCost(), 0.0);
        assertEquals(0.3, r.getWeightLength(), 0.0);
        assertEquals(25_000_000.0, r.getBaseCostRub(), 0.0);
        assertEquals(100.0, r.getBaseLengthM(), 0.0);
        // §7.3: единственный пример обновлённого приложения печатает score с 4 знаками (0.6913)
        assertEquals(4, r.getScoreScale());
        assertEquals(3, r.getMaxVariants());
        assertEquals(10.0, r.getTieInChamberRadiusM(), 0.0);
        assertEquals(4, r.getMaxSegmentsPerChamber());
        assertEquals(1, r.getLengthLimitMaxDnSteps());
    }
}
