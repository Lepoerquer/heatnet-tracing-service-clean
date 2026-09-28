package ru.heatnet.calc.model;

import java.util.Objects;

/**
 * Участок новой сети. Направление: fromNodeId — ближе к врезке (к источнику),
 * toNodeId — ближе к ОКС. Длина — в EPSG:32637, считает geo/network.
 */
public final class NewSegment {

    private final String id;
    private final String fromNodeId;
    private final String toNodeId;
    private final double lengthM;
    private final LayingMethod layingMethod;
    private final double kSpec;
    private final double kDepth;

    public NewSegment(String id, String fromNodeId, String toNodeId, double lengthM,
                      LayingMethod layingMethod, double kSpec, double kDepth) {
        this.id = Objects.requireNonNull(id, "id");
        this.fromNodeId = Objects.requireNonNull(fromNodeId, "fromNodeId");
        this.toNodeId = Objects.requireNonNull(toNodeId, "toNodeId");
        this.layingMethod = Objects.requireNonNull(layingMethod, "layingMethod");
        if (!(lengthM > 0) || Double.isInfinite(lengthM)) {
            throw new IllegalArgumentException("Участок " + id + ": длина должна быть > 0, получено " + lengthM);
        }
        if (!(kSpec >= 1.0) || !(kDepth >= 1.0)) {
            throw new IllegalArgumentException("Участок " + id + ": коэффициенты Kспец и Kгл должны быть >= 1");
        }
        if (layingMethod == LayingMethod.BASE && kSpec != 1.0) {
            throw new IllegalArgumentException("Участок " + id + ": у обычной прокладки Kспец = 1");
        }
        this.lengthM = lengthM;
        this.kSpec = kSpec;
        this.kDepth = kDepth;
    }

    /** Обычный участок в 2D (Kспец = 1, Kгл = 1). */
    public static NewSegment base(String id, String fromNodeId, String toNodeId, double lengthM) {
        return new NewSegment(id, fromNodeId, toNodeId, lengthM, LayingMethod.BASE, 1.0, 1.0);
    }

    /** Спецпроход в 2D (Kгл = 1). */
    public static NewSegment special(String id, String fromNodeId, String toNodeId, double lengthM, double kSpec) {
        return new NewSegment(id, fromNodeId, toNodeId, lengthM, LayingMethod.SPECIAL, kSpec, 1.0);
    }

    public String getId() {
        return id;
    }

    public String getFromNodeId() {
        return fromNodeId;
    }

    public String getToNodeId() {
        return toNodeId;
    }

    public double getLengthM() {
        return lengthM;
    }

    public LayingMethod getLayingMethod() {
        return layingMethod;
    }

    public double getKSpec() {
        return kSpec;
    }

    public double getKDepth() {
        return kDepth;
    }

    @Override
    public String toString() {
        return "Segment(" + id + ": " + fromNodeId + "->" + toNodeId + ", " + lengthM + " м)";
    }
}
