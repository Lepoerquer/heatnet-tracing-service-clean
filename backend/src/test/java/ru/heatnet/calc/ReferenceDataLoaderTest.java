package ru.heatnet.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.heatnet.calc.reference.DiameterSpec;
import ru.heatnet.calc.reference.GabaritSpec;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.calc.reference.RestrictionRule;
import ru.heatnet.calc.reference.RulesConfig;

/**
 * Контрольная сверка YAML с Техническим приложением. Числа в ожиданиях переписаны вручную
 * из табл. 4.1, 4.2, 5.1, разд. 8.2, 8.3, 9 — это «вторая пара глаз» к сгенерированным YAML.
 */
class ReferenceDataLoaderTest {

    private final ReferenceData ref = TestReference.get();

    @Test
    @DisplayName("табл. 4.1: 18 строк от DN50 до DN1400 (а не 16)")
    void diameterTableHas18Rows() {
        assertEquals(18, ref.getDiameters().size());
        int[] expected = {50, 65, 80, 100, 125, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 1400};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], ref.getDiameters().byIndex(i).getDn());
        }
    }

    @Test
    @DisplayName("табл. 4.1: контрольные строки DN50, DN200, DN250, DN1400")
    void diameterControlRows() {
        check(50, 3.5, 181, 74_023, 96_180);
        check(200, 152.3, 1_042, 120_275, 181_766);
        check(250, 274.9, 1_379, 135_323, 202_030);
        check(700, 3_735.1, 4_775, 324_298, 439_571);
        check(1400, 22_501.9, 11_276, 683_417, 978_584);
    }

    private void check(int dn, double cap, double maxLen, long newCost, long reconCost) {
        DiameterSpec s = ref.getDiameters().spec(dn);
        assertEquals(cap, s.getCapacityTph(), 1e-9, "capacity DN" + dn);
        assertEquals(maxLen, s.getMaxLengthM(), 1e-9, "max length DN" + dn);
        assertEquals(newCost, s.getNewCostRubPerM(), "new cost DN" + dn);
        assertEquals(reconCost, s.getReconstructionCostRubPerM(), "recon cost DN" + dn);
    }

    @Test
    @DisplayName("табл. 4.2: DN200 — 0,315/0,250/0,880/0,315; ширина = 2·D + просвет для всех строк")
    void gabarits() {
        GabaritSpec g = ref.getGabarits().spec(200);
        assertEquals(0.315, g.getOuterDiameterM(), 1e-9);
        assertEquals(0.250, g.getGapM(), 1e-9);
        assertEquals(0.880, g.getWidthM(), 1e-9);
        assertEquals(0.315, g.getHeightM(), 1e-9);
        for (DiameterSpec d : ref.getDiameters().all()) {
            GabaritSpec x = ref.getGabarits().spec(d.getDn());
            assertEquals(2 * x.getOuterDiameterM() + x.getGapM(), x.getWidthM(), 0.0005, "DN" + d.getDn());
        }
    }

    @Test
    @DisplayName("табл. 5.1: отступы и Kспец")
    void restrictions() {
        RulesConfig r = ref.getRules();
        RestrictionRule oks = r.restriction("oks_existing");
        assertTrue(oks.isProhibited());
        assertEquals(5.0, oks.minOffsetM(400), 1e-9);
        assertEquals(7.0, oks.minOffsetM(500), 1e-9);
        assertEquals(7.0, oks.minOffsetM(800), 1e-9);
        assertEquals(9.0, oks.minOffsetM(900), 1e-9);
        for (String t : new String[] {"park", "social_area", "prohibited_site", "water"}) {
            assertTrue(r.restriction(t).isProhibited(), t);
            assertEquals(1.0, r.restriction(t).minOffsetM(200), 1e-9, t);
        }
        assertEquals(1.60, r.restriction("road").getKSpec(), 1e-9);
        assertEquals(45.0, r.restriction("road").getMinCrossingAngleDeg(), 1e-9);
        assertEquals(1.5, r.restriction("road").minOffsetM(200), 1e-9);
        assertEquals(3.0, r.restriction("road").getZoneMarginM(), 1e-9);
        assertEquals(1.75, r.restriction("tram_tracks").getKSpec(), 1e-9);
        assertEquals(1.25, r.restriction("gas_pipeline").getKSpec(), 1e-9);
        assertEquals(2.0, r.restriction("gas_pipeline").minOffsetM(200), 1e-9);
        assertEquals(1.15, r.restriction("power_cable").getKSpec(), 1e-9);
        assertEquals(1.05, r.restriction("heat_network").getKSpec(), 1e-9);
        assertEquals(1.0, r.restriction("heat_network").minOffsetM(200), 1e-9);
        RestrictionRule railway = r.restriction("railway");
        assertTrue(railway.isProhibited());
        assertEquals(1.0, railway.minOffsetM(200), 1e-9);
        assertFalse(railway.isTeamDefault());
        assertFalse(r.restriction("road").isTeamDefault());
    }

    @Test
    @DisplayName("разд. 8–9: веса 0,7/0,3, K_угол 1,0 (§2.1), score_scale 4, врезка 5 млн")
    void costsAndRanking() {
        RulesConfig r = ref.getRules();
        assertEquals(0.7, r.getWeightCost(), 1e-12);
        assertEquals(0.3, r.getWeightLength(), 1e-12);
        assertEquals(25_000_000.0, r.getBaseCostRub(), 1e-9);
        assertEquals(100.0, r.getBaseLengthM(), 1e-9);
        assertEquals(4, r.getScoreScale());
        assertEquals(3, r.getMaxVariants());
        assertEquals(1.0, r.getKAngle(), 1e-12);
        assertEquals(5.0, r.getAngleToleranceDeg(), 1e-12);
        assertEquals(5_000_000L, r.getTieInCostRub());
        assertEquals(100_000_000L, r.getPenaltyFixedRub());
        assertEquals(500_000L, r.getPenaltyPerTphRub());
        assertEquals(1, r.getLengthLimitMaxDnSteps());
        assertEquals(4, r.getMaxSegmentsPerChamber());
        assertEquals(10.0, r.getTieInChamberRadiusM(), 1e-9);
        assertEquals(12.0, r.getRoutingStartExitMaxM(), 1e-9);
        assertEquals(2, r.getRoutingTieInAttempts());
    }

    @Test
    @DisplayName("табл. 4.3 и разд. 6: глубины, зазоры, шаг не захардкожен")
    void depthRules() {
        assertEquals(3.0, ref.getDepth().getNormalDepthM(), 1e-9);
        assertEquals(0.7, ref.getDepth().getMinOffsetM(), 1e-9);
        assertNull(ref.getDepth().getStepM());
        assertEquals(0.10, ref.getDepth().getMaxSlopeMPerM(), 1e-9);
        assertEquals(2.8, ref.getDepth().getUtilities().get("gas_pipeline").getTopDepthM(), 1e-9);
        assertEquals(0.4, ref.getDepth().getUtilities().get("gas_pipeline").getWidthM(), 1e-9);
        assertEquals(0.2, ref.getDepth().getUtilities().get("gas_pipeline").getVerticalClearanceM(), 1e-9);
        assertEquals(2.7, ref.getDepth().getUtilities().get("power_cable").getTopDepthM(), 1e-9);
        assertEquals(0.5, ref.getDepth().getUtilities().get("power_cable").getVerticalClearanceM(), 1e-9);
        assertTrue(ref.getDepth().getUtilities().get("heat_network").isGabaritByDn());
        assertEquals(1.0, ref.getDepth().getSurfaceCrossings().get("road").getMinTopDepthBelowSurfaceM(), 1e-9);
        assertEquals(1.2, ref.getDepth().getSurfaceCrossings().get("tram_tracks").getMinTopDepthBelowSurfaceM(), 1e-9);
    }
}
