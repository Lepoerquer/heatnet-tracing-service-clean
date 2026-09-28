package ru.heatnet.cost;

import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.reference.DiameterTable;

/**
 * M6. Стоимость нового участка (разд. 8.1):
 * C_уч = L · c_нов(DN) · Kгл · Kспец; в 2D Kгл = 1; для обычной прокладки Kспец = 1.
 */
public final class SegmentCostCalculator {

    private final DiameterTable diameters;

    public SegmentCostCalculator(DiameterTable diameters) {
        this.diameters = diameters;
    }

    public long cost(double lengthM, int dn, double kDepth, double kSpec) {
        if (!(lengthM >= 0) || !(kDepth >= 1.0) || !(kSpec >= 1.0)) {
            throw new IllegalArgumentException("Некорректные параметры участка: L=" + lengthM
                    + ", Kгл=" + kDepth + ", Kспец=" + kSpec);
        }
        return Money.product(lengthM, diameters.spec(dn).getNewCostRubPerM(), kDepth, kSpec);
    }

    public long cost(NewSegment segment, int dn) {
        return cost(segment.getLengthM(), dn, segment.getKDepth(), segment.getKSpec());
    }
}
