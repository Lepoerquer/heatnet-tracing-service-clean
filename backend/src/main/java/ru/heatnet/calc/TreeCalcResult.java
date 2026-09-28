package ru.heatnet.calc;

import java.util.Collections;
import java.util.Map;

import ru.heatnet.calc.model.NewNetworkTree;

/** Расчёт одного дерева новой сети. */
public final class TreeCalcResult {

    private final NewNetworkTree tree;
    private final Map<String, Double> flows;
    private final LengthLimitResult lengthLimit;

    public TreeCalcResult(NewNetworkTree tree, Map<String, Double> flows, LengthLimitResult lengthLimit) {
        this.tree = tree;
        this.flows = Collections.unmodifiableMap(flows);
        this.lengthLimit = lengthLimit;
    }

    public NewNetworkTree getTree() {
        return tree;
    }

    /** Расход участка, т/ч. */
    public Map<String, Double> getFlows() {
        return flows;
    }

    /** Окончательный DN участка, мм. */
    public Map<String, Integer> getDiameters() {
        return lengthLimit.getDiameters();
    }

    public LengthLimitResult getLengthLimit() {
        return lengthLimit;
    }
}
