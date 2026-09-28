package ru.heatnet.cost;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import ru.heatnet.calc.reference.RulesConfig;

/**
 * M6. Ранжирование: по неокруглённому S (меньше — лучше), при равенстве — по стоимости,
 * длине и variantId (детерминированно). Возвращает не более max_variants вариантов с rank 1..N.
 */
public final class VariantRanker {

    private final RulesConfig rules;

    public VariantRanker(RulesConfig rules) {
        this.rules = rules;
    }

    public List<VariantCost> rank(List<VariantCost> variants) {
        List<VariantCost> sorted = new ArrayList<>(variants);
        sorted.sort(Comparator
                .comparingDouble((VariantCost v) -> v.getSummary().getRawScore())
                .thenComparingLong(v -> v.getSummary().getCalculatedCost())
                .thenComparingDouble(v -> v.getSummary().getLength())
                .thenComparing(VariantCost::getVariantId));
        List<VariantCost> result = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < rules.getMaxVariants(); i++) {
            VariantCost v = sorted.get(i);
            result.add(v.withSummary(v.getSummary().withRank(i + 1)));
        }
        return result;
    }
}
