package ru.heatnet.calc;

import java.util.Collections;
import java.util.List;

/**
 * Непрерывная часть новой сети одного DN: участки, связанные через камеры и
 * технические узлы без смены DN (включая ветви одного DN в точке разветвления —
 * протокол 16.09, п. 7: «протяжённость суммируется по номенклатуре»).
 */
public final class DnRun {

    private final int dn;
    private final double totalLengthM;
    private final double maxLengthM;
    private final List<String> segmentIds;

    public DnRun(int dn, double totalLengthM, double maxLengthM, List<String> segmentIds) {
        this.dn = dn;
        this.totalLengthM = totalLengthM;
        this.maxLengthM = maxLengthM;
        this.segmentIds = Collections.unmodifiableList(segmentIds);
    }

    public int getDn() {
        return dn;
    }

    public double getTotalLengthM() {
        return totalLengthM;
    }

    public double getMaxLengthM() {
        return maxLengthM;
    }

    public List<String> getSegmentIds() {
        return segmentIds;
    }

    public boolean isExceeded(double toleranceM) {
        return totalLengthM > maxLengthM + toleranceM;
    }

    @Override
    public String toString() {
        return "DN" + dn + " " + totalLengthM + "/" + maxLengthM + " м " + segmentIds;
    }
}
