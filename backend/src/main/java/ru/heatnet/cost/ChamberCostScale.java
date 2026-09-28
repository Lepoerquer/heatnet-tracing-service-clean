package ru.heatnet.cost;

import ru.heatnet.calc.CalcException;
import ru.heatnet.calc.reference.DnBand;
import ru.heatnet.calc.reference.RulesConfig;

/**
 * M6. Шкала стоимости камеры (разд. 8.2) — одна для новой камеры и реконструкции существующей:
 * DN 50–200 — 3 млн; 250–500 — 5 млн; 600–1000 — 8 млн; 1200–1400 — 12 млн.
 */
public final class ChamberCostScale {

    private final RulesConfig rules;

    public ChamberCostScale(RulesConfig rules) {
        this.rules = rules;
    }

    public long costFor(int maxDn) {
        for (DnBand band : rules.getChamberScale()) {
            if (band.contains(maxDn)) {
                return band.getLongValue();
            }
        }
        throw new CalcException("Нет стоимости камеры для DN" + maxDn + " в rules.yaml costs.chamber_scale");
    }

    /** Стоимость одной независимой врезки. */
    public long tieInCost() {
        return rules.getTieInCostRub();
    }
}
