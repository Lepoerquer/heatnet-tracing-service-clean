package ru.heatnet.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;

import ru.heatnet.calc.reference.RulesConfig;

/**
 * M6. Показатель ранжирования (разд. 9):
 * S = 0,7 · (C / 25 000 000) + 0,3 · (L / 100), меньше — лучше.
 * Считается в double без округления слагаемых, округляется один раз до score_scale знаков HALF_UP
 * (§7.3 приложения 19.09: 4 знака, эталон 0.6913).
 */
public final class ScoreCalculator {

    private final RulesConfig rules;

    public ScoreCalculator(RulesConfig rules) {
        this.rules = rules;
    }

    /** Неокруглённый S (для сравнения вариантов). */
    public double raw(long costRub, double lengthM) {
        return rules.getWeightCost() * (costRub / rules.getBaseCostRub())
                + rules.getWeightLength() * (lengthM / rules.getBaseLengthM());
    }

    /** S, округлённый до score_scale знаков HALF_UP (выходной score). */
    public double score(long costRub, double lengthM) {
        return round(raw(costRub, lengthM));
    }

    public double round(double rawScore) {
        return BigDecimal.valueOf(rawScore).setScale(rules.getScoreScale(), RoundingMode.HALF_UP).doubleValue();
    }
}
