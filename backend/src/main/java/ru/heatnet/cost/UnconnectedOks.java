package ru.heatnet.cost;

import java.util.Objects;

/** ОКС, для которого в варианте не построено подключение. */
public final class UnconnectedOks {

    private final String oksId;
    private final double flowTph;

    public UnconnectedOks(String oksId, double flowTph) {
        this.oksId = Objects.requireNonNull(oksId, "oksId");
        this.flowTph = flowTph;
    }

    public String getOksId() {
        return oksId;
    }

    public double getFlowTph() {
        return flowTph;
    }
}
