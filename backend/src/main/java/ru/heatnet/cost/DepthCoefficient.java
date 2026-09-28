package ru.heatnet.cost;

import ru.heatnet.calc.CalcException;
import ru.heatnet.calc.reference.DepthRules;

/**
 * M6/M11. Коэффициент стоимости по глубине (разд. 6.1):
 * Kгл = 1 + 0,10 · (h − 3) при h > 3,0 м, иначе 1.
 * Для спуска/подъёма — среднее коэффициентов концов; участок, пересекающий 3,0 м,
 * должен быть предварительно разделён техническим узлом.
 */
public final class DepthCoefficient {

    private final DepthRules rules;

    public DepthCoefficient(DepthRules rules) {
        this.rules = rules;
    }

    public double of(double depthM) {
        if (!(depthM >= 0)) {
            throw new IllegalArgumentException("Некорректная глубина: " + depthM);
        }
        double threshold = rules.getCostThresholdDepthM();
        return depthM <= threshold ? 1.0 : 1.0 + rules.getCostRatePerExtraM() * (depthM - threshold);
    }

    public double average(double depthStartM, double depthEndM) {
        double t = rules.getCostThresholdDepthM();
        if ((depthStartM < t && depthEndM > t) || (depthStartM > t && depthEndM < t)) {
            throw new CalcException("Участок пересекает глубину " + t + " м — нужен технический узел (разд. 6.1)");
        }
        return (of(depthStartM) + of(depthEndM)) / 2.0;
    }
}
