package ru.heatnet.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.heatnet.calc.TestReference;

/**
 * Эталон §7.3 обновлённого приложения: self-consistent (100 м × 89 748 = 8 974 800).
 */
class Reference73IntegrationTest {

    private final ScoreCalculator score = new ScoreCalculator(TestReference.get().getRules());

    @Test
    @DisplayName("§7.3: construction = 13 974 800, L = 100, score = 0.6913")
    void example73() {
        VariantSummary s = VariantSummary.builder("v1")
                .addNewSegment(100.0, 8_974_800L)
                .addTieIn(5_000_000L)
                .build(score);
        assertEquals(8_974_800L, s.getSegmentCost());
        assertEquals(0L, s.getChamberConstructionCost());
        assertEquals(5_000_000L, s.getExistingChamberTieInCost());
        assertEquals(1, s.getExistingChamberTieInCount());
        assertEquals(13_974_800L, s.getConstructionCost());
        assertEquals(13_974_800L, s.getCalculatedCost());
        assertEquals(100.0, s.getNewNetworkLength(), 0.0);
        assertEquals(0.6913, s.getScore(), 0.0);
    }
}
