package ru.heatnet.depth;

import org.locationtech.jts.geom.LineString;

/** Кусок профиля вдоль участка: цепочка в метрах и глубина верха габарита на концах. */
public final class DepthSpan {

    private final double chainageStartM;
    private final double chainageEndM;
    private final double depthStartM;
    private final double depthEndM;
    private final LineString lineUtm;
    private final String note;

    public DepthSpan(double chainageStartM, double chainageEndM, double depthStartM, double depthEndM,
                     LineString lineUtm, String note) {
        this.chainageStartM = chainageStartM;
        this.chainageEndM = chainageEndM;
        this.depthStartM = depthStartM;
        this.depthEndM = depthEndM;
        this.lineUtm = lineUtm;
        this.note = note;
    }

    public double getChainageStartM() {
        return chainageStartM;
    }

    public double getChainageEndM() {
        return chainageEndM;
    }

    public double getDepthStartM() {
        return depthStartM;
    }

    public double getDepthEndM() {
        return depthEndM;
    }

    public LineString getLineUtm() {
        return lineUtm;
    }

    public String getNote() {
        return note;
    }

    public double lengthM() {
        return chainageEndM - chainageStartM;
    }
}
