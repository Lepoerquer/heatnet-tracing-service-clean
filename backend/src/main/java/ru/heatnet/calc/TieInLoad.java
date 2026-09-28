package ru.heatnet.calc;

import java.util.Objects;

import ru.heatnet.calc.model.ExistingObjectType;
import ru.heatnet.calc.model.TieInPoint;

/** Дополнительный расход, вносимый одной врезкой в существующую сеть. */
public final class TieInLoad {

    private final TieInPoint tieIn;
    private final double addedFlowTph;

    public TieInLoad(TieInPoint tieIn, double addedFlowTph) {
        this.tieIn = Objects.requireNonNull(tieIn, "tieIn");
        if (!(addedFlowTph >= 0) || Double.isInfinite(addedFlowTph)) {
            throw new IllegalArgumentException("Врезка " + tieIn.getId() + ": некорректный расход " + addedFlowTph);
        }
        if (tieIn.getExistingObjectType() == ExistingObjectType.HEAT_NETWORK && tieIn.getDistanceFromUpstreamEndM() == null) {
            throw new IllegalArgumentException("Врезка в трубу " + tieIn.getId() + ": не задана часть участка к источнику");
        }
        this.addedFlowTph = addedFlowTph;
    }

    public TieInPoint getTieIn() {
        return tieIn;
    }

    public double getAddedFlowTph() {
        return addedFlowTph;
    }
}
