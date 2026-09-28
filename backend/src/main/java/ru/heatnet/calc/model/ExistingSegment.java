package ru.heatnet.calc.model;

import java.util.Objects;

/** Существующий участок тепловой сети (входной heat_network). */
public final class ExistingSegment {

    private final String id;
    private final int diameter;
    private final Double flowTph;
    private final String upstreamObjectId;
    private final double lengthM;

    /**
     * @param flowTph           текущий расход; null, если атрибута нет во входных данных
     * @param upstreamObjectId  следующий объект к источнику (heat_network, heat_chamber или source)
     * @param lengthM           длина участка в EPSG:32637, м
     */
    public ExistingSegment(String id, int diameter, Double flowTph, String upstreamObjectId, double lengthM) {
        this.id = Objects.requireNonNull(id, "id");
        this.diameter = diameter;
        this.flowTph = flowTph;
        this.upstreamObjectId = upstreamObjectId;
        if (!(lengthM > 0)) {
            throw new IllegalArgumentException("Существующий участок " + id + ": длина должна быть > 0");
        }
        this.lengthM = lengthM;
    }

    public String getId() {
        return id;
    }

    public int getDiameter() {
        return diameter;
    }

    public Double getFlowTph() {
        return flowTph;
    }

    public String getUpstreamObjectId() {
        return upstreamObjectId;
    }

    public double getLengthM() {
        return lengthM;
    }
}
