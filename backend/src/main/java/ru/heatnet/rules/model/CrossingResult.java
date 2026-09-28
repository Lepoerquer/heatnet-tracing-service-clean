package ru.heatnet.rules.model;

import org.locationtech.jts.geom.Geometry;

/** Пересечение трассы со спецзоной (табл. 5.1). */
public final class CrossingResult {

    private final String restrictionId;
    private final String restrictionType;
    private final double kSpec;
    private final Geometry intersection;
    private final Double crossingAngleDeg;

    public CrossingResult(String restrictionId, String restrictionType, double kSpec,
                          Geometry intersection, Double crossingAngleDeg) {
        this.restrictionId = restrictionId;
        this.restrictionType = restrictionType;
        this.kSpec = kSpec;
        this.intersection = intersection;
        this.crossingAngleDeg = crossingAngleDeg;
    }

    public String getRestrictionId() {
        return restrictionId;
    }

    public String getRestrictionType() {
        return restrictionType;
    }

    public double getKSpec() {
        return kSpec;
    }

    public Geometry getIntersection() {
        return intersection;
    }

    /** Угол пересечения с осью препятствия, градусы; null — не применимо (линейный объект). */
    public Double getCrossingAngleDeg() {
        return crossingAngleDeg;
    }
}
