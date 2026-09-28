package ru.heatnet.calc.reference;

/** Все нормативные справочники из config/*.yaml. Неизменяемый, потокобезопасный. */
public final class ReferenceData {

    private final DiameterTable diameters;
    private final GabaritTable gabarits;
    private final RulesConfig rules;
    private final DepthRules depth;

    public ReferenceData(DiameterTable diameters, GabaritTable gabarits, RulesConfig rules, DepthRules depth) {
        this.diameters = diameters;
        this.gabarits = gabarits;
        this.rules = rules;
        this.depth = depth;
    }

    public DiameterTable getDiameters() {
        return diameters;
    }

    public GabaritTable getGabarits() {
        return gabarits;
    }

    public RulesConfig getRules() {
        return rules;
    }

    public DepthRules getDepth() {
        return depth;
    }
}
