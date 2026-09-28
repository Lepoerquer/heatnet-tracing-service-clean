package ru.heatnet.cost;

import ru.heatnet.calc.FlowPiece;

/** Реконструируемая часть существующего участка со стоимостью (heat_network_reconstruction). */
public final class CostedPiece {

    private final FlowPiece piece;
    private final long cost;

    public CostedPiece(FlowPiece piece, long cost) {
        this.piece = piece;
        this.cost = cost;
    }

    public FlowPiece getPiece() {
        return piece;
    }

    public long getCost() {
        return cost;
    }
}
