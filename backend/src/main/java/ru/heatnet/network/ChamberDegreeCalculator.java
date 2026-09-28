package ru.heatnet.network;

import ru.heatnet.calc.model.ExistingChamber;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingSegment;

/**
 * Степень камеры: 1 ребро к источнику + число downstream-примыканий (п. 2.3 ТЗ).
 */
public final class ChamberDegreeCalculator {

    private ChamberDegreeCalculator() {
    }

    public static int currentDegree(ExistingNetwork network, String chamberId) {
        ExistingChamber chamber = network.chamber(chamberId);
        int degree = chamber.getUpstreamObjectId() == null ? 0 : 1;
        for (ExistingSegment seg : network.downstreamSegmentsOf(chamberId)) {
            degree++;
        }
        return degree;
    }

    public static boolean canAddTieIn(ExistingNetwork network, String chamberId, int maxSegmentsPerChamber) {
        return currentDegree(network, chamberId) + 1 <= maxSegmentsPerChamber;
    }

    /**
     * AUDIT-24.09 (Claude): степень по геометрии (Разъяснение №12). Если геометрия камеры известна —
     * считается она; топологическая оценка по upstream-цепочке берётся как нижняя граница.
     */
    public static int degree(ExistingNetwork network, ExistingNetworkGeometry geometry, String chamberId) {
        int topo = network != null && network.isChamber(chamberId) ? currentDegree(network, chamberId) : 0;
        if (geometry == null || !geometry.getChamberPoints().containsKey(chamberId)) {
            return topo;
        }
        return Math.max(topo, geometry.chamberDegree(chamberId));
    }

    public static boolean canAddTieIn(ExistingNetwork network, ExistingNetworkGeometry geometry, String chamberId,
                                      int maxSegmentsPerChamber, int newSegments) {
        return degree(network, geometry, chamberId) + newSegments <= maxSegmentsPerChamber;
    }
}
