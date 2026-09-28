package ru.heatnet.rules.model;

import org.locationtech.jts.geom.LineString;

/** Поперечный створ для пересечения площадного препятствия (дорога, трамвай). */
public final class PortalGate {

    private final String restrictionId;
    private final LineString gateLine;
    private final double axisAngleDeg;
    private final double crossingAngleDeg;

    public PortalGate(String restrictionId, LineString gateLine,
                      double axisAngleDeg, double crossingAngleDeg) {
        this.restrictionId = restrictionId;
        this.gateLine = gateLine;
        this.axisAngleDeg = axisAngleDeg;
        this.crossingAngleDeg = crossingAngleDeg;
    }

    public String getRestrictionId() {
        return restrictionId;
    }

    public LineString getGateLine() {
        return gateLine;
    }

    /** Азимут оси препятствия, градусы [0, 180). */
    public double getAxisAngleDeg() {
        return axisAngleDeg;
    }

    /** Угол створа к оси (для перпендикуляра = 90°). */
    public double getCrossingAngleDeg() {
        return crossingAngleDeg;
    }
}
