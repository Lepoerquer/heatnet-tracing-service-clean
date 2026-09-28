package ru.heatnet.depth;

import org.locationtech.jts.geom.LineString;

import ru.heatnet.calc.model.LayingMethod;

/** Участок после нарезки профиля глубины: готов к экспорту и пересчёту Kгл. */
public final class DepthPiece {

    private final String id;
    private final String fromNodeId;
    private final String toNodeId;
    private final LineString lineUtm;
    private final double lengthM;
    private final double depthStartM;
    private final double depthEndM;
    private final double kDepth;
    private final double kSpec;
    private final LayingMethod layingMethod;
    private final int diameterMm;
    private final double flowTph;
    private final long costRub;
    private final String note;

    public DepthPiece(String id, String fromNodeId, String toNodeId, LineString lineUtm, double lengthM,
                      double depthStartM, double depthEndM, double kDepth, double kSpec,
                      LayingMethod layingMethod, int diameterMm, double flowTph, long costRub, String note) {
        this.id = id;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.lineUtm = lineUtm;
        this.lengthM = lengthM;
        this.depthStartM = depthStartM;
        this.depthEndM = depthEndM;
        this.kDepth = kDepth;
        this.kSpec = kSpec;
        this.layingMethod = layingMethod;
        this.diameterMm = diameterMm;
        this.flowTph = flowTph;
        this.costRub = costRub;
        this.note = note;
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

    public LineString getLineUtm() {
        return lineUtm;
    }

    public double getLengthM() {
        return lengthM;
    }

    public double getDepthStartM() {
        return depthStartM;
    }

    public double getDepthEndM() {
        return depthEndM;
    }

    public double getKDepth() {
        return kDepth;
    }

    public double getKSpec() {
        return kSpec;
    }

    public LayingMethod getLayingMethod() {
        return layingMethod;
    }

    public int getDiameterMm() {
        return diameterMm;
    }

    public double getFlowTph() {
        return flowTph;
    }

    public long getCostRub() {
        return costRub;
    }

    public String getNote() {
        return note;
    }
}
