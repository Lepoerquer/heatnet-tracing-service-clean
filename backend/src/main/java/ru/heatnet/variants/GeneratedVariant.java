package ru.heatnet.variants;

import java.util.Objects;

import ru.heatnet.cost.VariantCost;
import ru.heatnet.cost.VariantSummary;
import ru.heatnet.network.NetworkPlan;

/** M7. Один сгенерированный вариант: план M4 + смета M6 + стратегия, которой он получен. */
public final class GeneratedVariant {

    private final String variantId;
    private final VariantStrategy strategy;
    private final NetworkPlan plan;
    private final VariantCost cost;

    public GeneratedVariant(String variantId, VariantStrategy strategy, NetworkPlan plan, VariantCost cost) {
        this.variantId = Objects.requireNonNull(variantId, "variantId");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.cost = Objects.requireNonNull(cost, "cost");
    }

    public String getVariantId() {
        return variantId;
    }

    public VariantStrategy getStrategy() {
        return strategy;
    }

    public NetworkPlan getPlan() {
        return plan;
    }

    public VariantCost getCost() {
        return cost;
    }

    public VariantSummary getSummary() {
        return cost.getSummary();
    }

    /** Копия с новой сводкой — используется при простановке {@code rank} после ранжирования. */
    public GeneratedVariant withSummary(VariantSummary summary) {
        return new GeneratedVariant(variantId, strategy, plan, cost.withSummary(summary));
    }

    @Override
    public String toString() {
        return "Вариант " + variantId + " (" + strategy.getCode() + "): деревьев "
                + plan.getTrees().size() + ", неподключённых " + plan.getUnconnectedOks().size()
                + ", S=" + getSummary().getScore();
    }
}
