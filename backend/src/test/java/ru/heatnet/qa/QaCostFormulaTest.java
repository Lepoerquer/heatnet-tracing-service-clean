package ru.heatnet.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.cost.ReconstructionCostCalculator;
import ru.heatnet.cost.ScoreCalculator;
import ru.heatnet.cost.SegmentCostCalculator;
import ru.heatnet.cost.VariantSummary;

/**
 * QA (роль 4): независимый пересчёт формул разд. 7–9 приложения и строгая проверка округления (разд. 9, протокол п. 9).
 */
class QaCostFormulaTest {

    private final ReferenceData ref = TestReference.get();
    private final SegmentCostCalculator seg = new SegmentCostCalculator(ref.getDiameters());
    private final ReconstructionCostCalculator rec = new ReconstructionCostCalculator(ref.getDiameters());
    private final ScoreCalculator score = new ScoreCalculator(ref.getRules());

    @Test
    @DisplayName("QA-M6-1: C_уч = L·c_нов·K_спец (разд. 8.1) — 20000 случайных наборов против независимого BigDecimal")
    void segmentCostMatchesIndependentFormula() {
        Random rnd = new Random(7);
        double[] ks = {1.0, 1.05, 1.15, 1.25, 1.60, 1.75};
        for (int i = 0; i < 20000; i++) {
            int dn = ref.getDiameters().byIndex(rnd.nextInt(ref.getDiameters().size())).getDn();
            double len = Math.round(rnd.nextDouble() * 5_000_000) / 1000.0; // до 5 км, шаг 1 мм
            double k = ks[rnd.nextInt(ks.length)];
            long rate = ref.getDiameters().spec(dn).getNewCostRubPerM();
            BigDecimal exact = new BigDecimal(Double.toString(len)).multiply(BigDecimal.valueOf(rate))
                    .multiply(new BigDecimal(Double.toString(k)));
            long expected = exact.setScale(0, RoundingMode.HALF_UP).longValueExact();
            assertEquals(expected, seg.cost(len, dn, 1.0, k), "L=" + len + " DN" + dn + " K=" + k);
        }
    }

    @Test
    @DisplayName("QA-M6-2: C_рек = L_рек·c_рек(DN_треб) (разд. 7): без K_спец и K_гл")
    void reconstructionCost() {
        Random rnd = new Random(11);
        for (int i = 0; i < 5000; i++) {
            int dn = ref.getDiameters().byIndex(rnd.nextInt(ref.getDiameters().size())).getDn();
            double len = Math.round(rnd.nextDouble() * 2_000_000) / 1000.0;
            long expected = new BigDecimal(Double.toString(len))
                    .multiply(BigDecimal.valueOf(ref.getDiameters().spec(dn).getReconstructionCostRubPerM()))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            assertEquals(expected, rec.cost(len, dn));
        }
    }

    @Test
    @DisplayName("QA-M6-3: S = 0,7·C/25e6 + 0,3·L/100, HALF_UP — округление по ranking.score_scale на точных «ничьих»")
    void scoreRoundingOnExactTies() {
        // S·1000 = 2,8·m + 1,5·k, где C = 100000·m, L = 0,5·k: при m кратном 5 и нечётном k — точная половина.
        int mismatches = 0;
        StringBuilder first = new StringBuilder();
        int checked = 0;
        for (int m = 0; m <= 5 * 400; m += 5) {
            for (int k = 1; k <= 24; k += 2) {
                long c = 100_000L * m;
                double l = 0.5 * k;
                BigDecimal exact = BigDecimal.valueOf(7, 1).multiply(BigDecimal.valueOf(c))
                        .divide(BigDecimal.valueOf(25_000_000L), 30, RoundingMode.HALF_UP)
                        .add(BigDecimal.valueOf(3, 1).multiply(new BigDecimal(Double.toString(l)))
                                .divide(BigDecimal.valueOf(100), 30, RoundingMode.HALF_UP));
                double expected = exact.setScale(ref.getRules().getScoreScale(), RoundingMode.HALF_UP).doubleValue();
                double actual = score.score(c, l);
                checked++;
                if (Double.compare(expected, actual) != 0) {
                    mismatches++;
                    if (first.length() < 400) {
                        first.append("C=").append(c).append(" L=").append(l).append(" exact=").append(exact.toPlainString())
                                .append(" expected=").append(expected).append(" actual=").append(actual).append("; ");
                    }
                }
            }
        }
        assertEquals(0, mismatches, mismatches + " из " + checked + " «ничьих» округлены неверно: " + first);
    }

    @Test
    @DisplayName("QA-M6-4: S монотонен по C и по L; округление один раз (score == round(raw))")
    void scoreMonotonicAndSingleRounding() {
        Random rnd = new Random(3);
        for (int i = 0; i < 5000; i++) {
            long c = rnd.nextInt(2_000_000_000);
            double l = rnd.nextDouble() * 20000;
            double raw = score.raw(c, l);
            assertEquals(score.round(raw), score.score(c, l), 0.0);
            assertTrue(score.raw(c + 1000, l) > raw);
            assertTrue(score.raw(c, l + 0.01) > raw);
        }
    }

    @Test
    @DisplayName("QA-M6-5: числа примера 10.8 по ОБНОВЛЁННОЙ §6 — реконструкция вне C, construction_cost — итог")
    void reference108Summary() {
        VariantSummary s = VariantSummary.builder("1")
                .addNewSegment(145.2, 27_942_307L).addNewChamber(3_000_000L).addTieIn(5_000_000L)
                .addReconstruction(75.0, 15_152_283L).build(score);
        // §6 (19.09): C = участки + камеры + врезки + штраф; реконструкция не входит,
        // L = только new_network_length. Старый эталон 10.8 (51 094 590 / L=220,2 / 2,091)
        // относился к отменённой редакции приложения.
        assertEquals(27_942_307L + 3_000_000L + 5_000_000L, s.getCalculatedCost());
        assertEquals(145.2, s.getLength(), 1e-12);
        assertEquals(35_942_307L, s.getConstructionCost());
    }

    @Test
    @DisplayName("QA-M6-6: длина L в summary без накопления двоичной ошибки: 3000 сегментов по 0,1 м = 300,0 м")
    void lengthSumIsDecimalExact() {
        VariantSummary.Builder b = VariantSummary.builder("x");
        for (int i = 0; i < 3000; i++) {
            b.addNewSegment(0.1, 0L);
        }
        VariantSummary s = b.build(score);
        assertEquals(300.0, s.getNewNetworkLength(), 1e-9);
    }
}
