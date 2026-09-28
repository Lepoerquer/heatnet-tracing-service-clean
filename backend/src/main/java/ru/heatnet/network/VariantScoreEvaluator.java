package ru.heatnet.network;

import java.util.Collections;
import java.util.List;

import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.cost.UnconnectedOks;
import ru.heatnet.cost.VariantCost;
import ru.heatnet.cost.VariantCostCalculator;
import ru.heatnet.cost.VariantSummary;

/**
 * Оценка S для сравнения схем в JointPlanner (решение только по знаку ΔS).
 */
final class VariantScoreEvaluator {

    private final EngineeringCalculator engineering;
    private final VariantCostCalculator costCalculator;

    VariantScoreEvaluator(ReferenceData reference) {
        this.engineering = new EngineeringCalculator(reference);
        this.costCalculator = new VariantCostCalculator(reference);
    }

    double score(List<NewNetworkTree> trees, ExistingNetwork existing, List<UnconnectedOks> unconnected) {
        if (trees == null || trees.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        EngineeringResult eng = engineering.calculate(trees, existing);
        if (eng.hasErrors()) {
            return Double.POSITIVE_INFINITY;
        }
        VariantCost cost = costCalculator.calculate("eval", eng, unconnected);
        VariantSummary summary = cost.getSummary();
        return summary.getScore();
    }
}
