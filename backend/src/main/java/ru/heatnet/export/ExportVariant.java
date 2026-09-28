package ru.heatnet.export;

import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.cost.VariantCost;
import ru.heatnet.depth.DepthOutcome;
import ru.heatnet.network.NetworkPlan;

/** Один вариант, готовый к выгрузке в GeoJSON. */
public final class ExportVariant {

    private final NetworkPlan plan;
    private final EngineeringResult engineering;
    private final VariantCost cost;
    private final DepthOutcome depth;

    public ExportVariant(NetworkPlan plan, EngineeringResult engineering, VariantCost cost, DepthOutcome depth) {
        this.plan = plan;
        this.engineering = engineering;
        this.cost = cost;
        this.depth = depth;
    }

    public NetworkPlan getPlan() {
        return plan;
    }

    public EngineeringResult getEngineering() {
        return engineering;
    }

    public VariantCost getCost() {
        return cost;
    }

    public DepthOutcome getDepth() {
        return depth;
    }
}
