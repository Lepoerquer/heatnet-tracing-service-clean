package ru.heatnet.cost;

import java.math.BigDecimal;

import ru.heatnet.calc.reference.RulesConfig;

/** M6. Штраф за неподключённый ОКС (разд. 8.3): Ш = 100 000 000 + 500 000 · G_ОКС. */
public final class UnconnectedPenaltyCalculator {

    private final RulesConfig rules;

    public UnconnectedPenaltyCalculator(RulesConfig rules) {
        this.rules = rules;
    }

    public long penalty(double oksFlowTph) {
        if (!(oksFlowTph >= 0) || Double.isInfinite(oksFlowTph)) {
            throw new IllegalArgumentException("Некорректный расход ОКС: " + oksFlowTph);
        }
        BigDecimal variable = BigDecimal.valueOf(oksFlowTph).multiply(BigDecimal.valueOf(rules.getPenaltyPerTphRub()));
        return Money.add(rules.getPenaltyFixedRub(), Money.toRub(variable));
    }
}
