package ru.heatnet.depth;

import org.locationtech.jts.geom.Coordinate;

/** Технический узел, поставленный профилем глубины (смена глубины / пересечение 3,0 м). */
public final class DepthExtraNode {

    private final String id;
    private final Coordinate utm;
    private final String note;

    public DepthExtraNode(String id, Coordinate utm, String note) {
        this.id = id;
        this.utm = utm;
        this.note = note;
    }

    public String getId() {
        return id;
    }

    public Coordinate getUtm() {
        return utm;
    }

    public String getNote() {
        return note;
    }
}
