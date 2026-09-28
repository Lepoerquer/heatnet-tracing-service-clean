package ru.heatnet.ingest;

import java.util.Objects;

/** Точка подключения перспективного ОКС (oks_connection_point). */
public final class OksConnectionPoint {

    private final String id;
    private final double flowTph;
    private final boolean numericId;

    public OksConnectionPoint(String id, double flowTph) {
        this(id, flowTph, false);
    }

    public OksConnectionPoint(String id, double flowTph, boolean numericId) {
        this.id = Objects.requireNonNull(id, "id");
        if (!(flowTph > 0)) {
            throw new IllegalArgumentException("oks_connection_point " + id + ": flow_tph должен быть > 0");
        }
        this.flowTph = flowTph;
        this.numericId = numericId;
    }

    public String getId() {
        return id;
    }

    public double getFlowTph() {
        return flowTph;
    }

    public boolean isNumericId() {
        return numericId;
    }

    public Object exportId() {
        if (!numericId) {
            return id;
        }
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException ex) {
            return id;
        }
    }
}
