package ru.heatnet.calc.reference;

import java.util.Collections;
import java.util.Map;

/** Содержимое config/depth-rules.yaml (табл. 4.3, разд. 6). */
public final class DepthRules {

    /** Условные параметры существующей коммуникации (табл. 4.3 + просвет из 5.1). */
    public static final class Utility {
        private final Double widthM;
        private final Double heightM;
        private final boolean gabaritByDn;
        private final double topDepthM;
        private final double verticalClearanceM;

        public Utility(Double widthM, Double heightM, boolean gabaritByDn,
                       double topDepthM, double verticalClearanceM) {
            this.widthM = widthM;
            this.heightM = heightM;
            this.gabaritByDn = gabaritByDn;
            this.topDepthM = topDepthM;
            this.verticalClearanceM = verticalClearanceM;
        }

        /** Ширина габарита, м; null, если габарит берётся по DN (теплосеть). */
        public Double getWidthM() {
            return widthM;
        }

        public Double getHeightM() {
            return heightM;
        }

        public boolean isGabaritByDn() {
            return gabaritByDn;
        }

        public double getTopDepthM() {
            return topDepthM;
        }

        public double getVerticalClearanceM() {
            return verticalClearanceM;
        }
    }

    /** Площадное пересечение (дорога, трамвай). */
    public static final class SurfaceCrossing {
        private final double minTopDepthBelowSurfaceM;
        private final double zoneMarginM;

        public SurfaceCrossing(double minTopDepthBelowSurfaceM, double zoneMarginM) {
            this.minTopDepthBelowSurfaceM = minTopDepthBelowSurfaceM;
            this.zoneMarginM = zoneMarginM;
        }

        public double getMinTopDepthBelowSurfaceM() {
            return minTopDepthBelowSurfaceM;
        }

        public double getZoneMarginM() {
            return zoneMarginM;
        }
    }

    private final double normalDepthM;
    private final double minDepthM;
    private final double minOffsetM;
    private final Double stepM;
    private final double maxSlopeMPerM;
    private final double pointCrossingPlateauM;
    private final double costThresholdDepthM;
    private final double costRatePerExtraM;
    private final Map<String, Utility> utilities;
    private final Map<String, SurfaceCrossing> surfaceCrossings;

    public DepthRules(double normalDepthM, double minDepthM, double minOffsetM, Double stepM,
                      double maxSlopeMPerM, double pointCrossingPlateauM, double costThresholdDepthM,
                      double costRatePerExtraM, Map<String, Utility> utilities,
                      Map<String, SurfaceCrossing> surfaceCrossings) {
        this.normalDepthM = normalDepthM;
        this.minDepthM = minDepthM;
        this.minOffsetM = minOffsetM;
        this.stepM = stepM;
        this.maxSlopeMPerM = maxSlopeMPerM;
        this.pointCrossingPlateauM = pointCrossingPlateauM;
        this.costThresholdDepthM = costThresholdDepthM;
        this.costRatePerExtraM = costRatePerExtraM;
        this.utilities = Collections.unmodifiableMap(utilities);
        this.surfaceCrossings = Collections.unmodifiableMap(surfaceCrossings);
    }

    public double getNormalDepthM() {
        return normalDepthM;
    }

    public double getMinDepthM() {
        return minDepthM;
    }

    public double getMinOffsetM() {
        return minOffsetM;
    }

    /** Шаг дискретизации глубины, м; null — непрерывный поиск. */
    public Double getStepM() {
        return stepM;
    }

    public double getMaxSlopeMPerM() {
        return maxSlopeMPerM;
    }

    public double getPointCrossingPlateauM() {
        return pointCrossingPlateauM;
    }

    public double getCostThresholdDepthM() {
        return costThresholdDepthM;
    }

    public double getCostRatePerExtraM() {
        return costRatePerExtraM;
    }

    public Map<String, Utility> getUtilities() {
        return utilities;
    }

    public Map<String, SurfaceCrossing> getSurfaceCrossings() {
        return surfaceCrossings;
    }
}
