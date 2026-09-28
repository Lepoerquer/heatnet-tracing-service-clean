package ru.heatnet.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.heatnet.calc.TestReference;

class ScoreAndRankingTest {

    private final ScoreCalculator score = new ScoreCalculator(TestReference.get().getRules());

    @Test
    @DisplayName("S: C = 51 094 590, L = 220,2 → 0,7·2,0437836 + 0,3·2,202 = 2,0912485 → 2,091")
    void score108() {
        assertEquals(2.0912485, score.raw(51_094_590L, 220.2), 1e-7);
        assertEquals(2.0912, score.score(51_094_590L, 220.2), 0.0);
    }

    @Test
    @DisplayName("S: округление один раз HALF_UP: 1,0005 → 1,001; 1,0004 → 1,000")
    void roundingOnce() {
        assertEquals(1.0005, score.round(1.0005), 0.0);
        assertEquals(1.0004, score.round(1.0004), 0.0);
    }

    @Test
    @DisplayName("S: C = 25 млн, L = 100 м → ровно 1,000; 1 м длины ≈ 107 143 ₽ (0,3/100 ÷ 0,7/25 млн)")
    void scoreScale() {
        assertEquals(1.0, score.score(25_000_000L, 100.0), 0.0);
        assertEquals(score.raw(0, 1.0), score.raw(107_143L, 0.0), 1e-8);
    }

    private static VariantCost variant(String id, long cost, double length) {
        VariantSummary s = VariantSummary.builder(id).addNewSegment(length, cost)
                .build(new ScoreCalculator(TestReference.get().getRules()));
        return new VariantCost(id, null, new java.util.HashMap<String, Long>(), new java.util.HashMap<String, Long>(),
                new java.util.HashMap<String, Long>(), new ArrayList<CostedPiece>(), new java.util.HashMap<String, Long>(),
                new java.util.HashMap<String, Long>(), s);
    }

    @Test
    @DisplayName("ранжирование: по S, не более 3 вариантов, rank 1..3")
    void ranking() {
        List<VariantCost> ranked = new VariantRanker(TestReference.get().getRules()).rank(Arrays.asList(
                variant("A", 30_000_000L, 150),   // S = 0,84 + 0,45 = 1,29
                variant("B", 20_000_000L, 300),   // S = 0,56 + 0,90 = 1,46
                variant("C", 25_000_000L, 100),   // S = 0,70 + 0,30 = 1,00
                variant("D", 60_000_000L, 50)));  // S = 1,68 + 0,15 = 1,83
        assertEquals(3, ranked.size());
        assertEquals("C", ranked.get(0).getVariantId());
        assertEquals(1, ranked.get(0).getSummary().getRank());
        assertEquals("A", ranked.get(1).getVariantId());
        assertEquals(2, ranked.get(1).getSummary().getRank());
        assertEquals("B", ranked.get(2).getVariantId());
        assertEquals(3, ranked.get(2).getSummary().getRank());
        assertEquals(1.29, ranked.get(1).getSummary().getScore(), 0.0);
    }
}
