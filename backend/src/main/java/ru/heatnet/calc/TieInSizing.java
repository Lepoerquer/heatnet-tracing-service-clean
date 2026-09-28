package ru.heatnet.calc;

import ru.heatnet.calc.model.TieInPoint;

/** Параметры выходного tie_in (разд. 10.2). */
public final class TieInSizing {

    private final TieInPoint tieIn;
    private final int existingDiameter;
    private final int requiredDiameter;
    private final double addedFlowTph;

    public TieInSizing(TieInPoint tieIn, int existingDiameter, int requiredDiameter, double addedFlowTph) {
        this.tieIn = tieIn;
        this.existingDiameter = existingDiameter;
        this.requiredDiameter = requiredDiameter;
        this.addedFlowTph = addedFlowTph;
    }

    public TieInPoint getTieIn() {
        return tieIn;
    }

    /** DN существующего участка или входной diameter камеры. */
    public int getExistingDiameter() {
        return existingDiameter;
    }

    /** DN новой сети в точке врезки. */
    public int getRequiredDiameter() {
        return requiredDiameter;
    }

    public double getAddedFlowTph() {
        return addedFlowTph;
    }
}
