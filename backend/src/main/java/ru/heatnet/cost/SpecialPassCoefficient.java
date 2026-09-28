package ru.heatnet.cost;

import java.util.Collection;

import ru.heatnet.calc.CalcException;
import ru.heatnet.calc.reference.RestrictionRule;
import ru.heatnet.calc.reference.RulesConfig;

/**
 * M6. Kспец участка по набору пересекаемых ограничений (табл. 5.1, протокол 16.09 п. 9):
 * при наложении зон — один спецучасток и максимальный коэффициент, без перемножения.
 * Запрещённое пересечение имеет приоритет — спецпроходом через него пройти нельзя.
 */
public final class SpecialPassCoefficient {

    private final RulesConfig rules;

    public SpecialPassCoefficient(RulesConfig rules) {
        this.rules = rules;
    }

    /** 1.0 для пустого набора (обычная прокладка). */
    public double resolve(Collection<String> restrictionTypes) {
        double k = 1.0;
        for (String type : restrictionTypes) {
            RestrictionRule rule = rules.restriction(type);
            if (rule.isProhibited()) {
                if (rules.isProhibitedHasPriority()) {
                    throw new CalcException("Пересечение '" + type + "' запрещено — спецпроход невозможен");
                }
                continue;
            }
            k = Math.max(k, rule.getKSpec());
        }
        return k;
    }
}
