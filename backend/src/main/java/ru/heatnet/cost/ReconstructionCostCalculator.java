package ru.heatnet.cost;

import ru.heatnet.calc.reference.DiameterTable;

/**
 * M6. Стоимость реконструкции (разд. 7): C_рек = L_рек · c_рек(DN_треб).
 * Коэффициенты спецпрохода и глубины к реконструкции не применяются.
 */
public final class ReconstructionCostCalculator {

    private final DiameterTable diameters;

    public ReconstructionCostCalculator(DiameterTable diameters) {
        this.diameters = diameters;
    }

    public long cost(double lengthM, int requiredDn) {
        if (!(lengthM >= 0)) {
            throw new IllegalArgumentException("Некорректная длина реконструкции: " + lengthM);
        }
        return Money.product(lengthM, diameters.spec(requiredDn).getReconstructionCostRubPerM());
    }
}
