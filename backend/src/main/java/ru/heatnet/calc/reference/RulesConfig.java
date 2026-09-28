package ru.heatnet.calc.reference;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import ru.heatnet.calc.CalcException;

/** Содержимое config/rules.yaml: ограничения, стоимость, углы, ранжирование. */
public final class RulesConfig {

    private final Map<String, RestrictionRule> restrictions;
    private final boolean prohibitedHasPriority;

    private final double kAngle;
    private final List<Double> standardAnglesDeg;
    private final double angleToleranceDeg;

    private final double tieInChamberRadiusM;
    private final int maxSegmentsPerChamber;
    private final int lengthLimitMaxDnSteps;
    private final double geometryToleranceM;

    private final double routingWorkspaceMarginM;
    private final double routingVertexOutwardM;
    private final double routingTurnPenaltyM;
    private final double routingGridCellM;
    private final int routingGridFallbackMinVertices;
    private final double routingVertexSimplifyM;
    private final long routingVgDeadlineMs;
    private final long routingGridDeadlineMs;
    private final long routingRouteBudgetMs;
    private final long routingCrossingBudgetMs;
    private final double routingClusterPrefixM;
    private final double routingJointGroupM;
    private final int routingTieInAttempts;
    private final double routingStartExitMaxM;

    private final long tieInCostRub;
    private final List<DnBand> chamberScale;
    private final long penaltyFixedRub;
    private final long penaltyPerTphRub;

    private final double weightCost;
    private final double weightLength;
    private final double baseCostRub;
    private final double baseLengthM;
    private final int scoreScale;
    private final int maxVariants;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public RulesConfig(Map<String, RestrictionRule> restrictions, boolean prohibitedHasPriority,
                       double kAngle, List<Double> standardAnglesDeg, double angleToleranceDeg,
                       double tieInChamberRadiusM, int maxSegmentsPerChamber, int lengthLimitMaxDnSteps,
                       double geometryToleranceM,
                       double routingWorkspaceMarginM, double routingVertexOutwardM,
                       double routingTurnPenaltyM, double routingGridCellM,
                       int routingGridFallbackMinVertices,
                       double routingVertexSimplifyM,
                       long routingVgDeadlineMs, long routingGridDeadlineMs,
                       long routingRouteBudgetMs, long routingCrossingBudgetMs,
                       double routingClusterPrefixM,
                       double routingJointGroupM,
                       int routingTieInAttempts,
                       double routingStartExitMaxM,
                       long tieInCostRub, List<DnBand> chamberScale,
                       long penaltyFixedRub, long penaltyPerTphRub, double weightCost, double weightLength,
                       double baseCostRub, double baseLengthM, int scoreScale, int maxVariants) {
        this.restrictions = Collections.unmodifiableMap(restrictions);
        this.prohibitedHasPriority = prohibitedHasPriority;
        this.kAngle = kAngle;
        this.standardAnglesDeg = Collections.unmodifiableList(standardAnglesDeg);
        this.angleToleranceDeg = angleToleranceDeg;
        this.tieInChamberRadiusM = tieInChamberRadiusM;
        this.maxSegmentsPerChamber = maxSegmentsPerChamber;
        this.lengthLimitMaxDnSteps = lengthLimitMaxDnSteps;
        this.geometryToleranceM = geometryToleranceM;
        this.routingWorkspaceMarginM = routingWorkspaceMarginM;
        this.routingVertexOutwardM = routingVertexOutwardM;
        this.routingTurnPenaltyM = routingTurnPenaltyM;
        this.routingGridCellM = routingGridCellM;
        this.routingGridFallbackMinVertices = routingGridFallbackMinVertices;
        this.routingVertexSimplifyM = routingVertexSimplifyM;
        this.routingVgDeadlineMs = routingVgDeadlineMs;
        this.routingGridDeadlineMs = routingGridDeadlineMs;
        this.routingRouteBudgetMs = routingRouteBudgetMs;
        this.routingCrossingBudgetMs = routingCrossingBudgetMs;
        this.routingClusterPrefixM = routingClusterPrefixM;
        this.routingJointGroupM = routingJointGroupM > 0 ? routingJointGroupM : 250.0;
        this.routingTieInAttempts = routingTieInAttempts > 0 ? routingTieInAttempts : 2;
        this.routingStartExitMaxM = routingStartExitMaxM > 0 ? routingStartExitMaxM : 12.0;
        this.tieInCostRub = tieInCostRub;
        this.chamberScale = Collections.unmodifiableList(chamberScale);
        this.penaltyFixedRub = penaltyFixedRub;
        this.penaltyPerTphRub = penaltyPerTphRub;
        this.weightCost = weightCost;
        this.weightLength = weightLength;
        this.baseCostRub = baseCostRub;
        this.baseLengthM = baseLengthM;
        this.scoreScale = scoreScale;
        this.maxVariants = maxVariants;
        if (Math.abs(weightCost + weightLength - 1.0) > 1e-9) {
            throw new CalcException("rules.yaml: weight_cost + weight_length должны давать 1.0");
        }
        if (baseCostRub <= 0 || baseLengthM <= 0) {
            throw new CalcException("rules.yaml: базовые стоимость и длина должны быть > 0");
        }
        if (lengthLimitMaxDnSteps < 0) {
            throw new CalcException("rules.yaml: length_limit_max_dn_steps не может быть отрицательным");
        }
    }

    public RestrictionRule restriction(String type) {
        RestrictionRule r = restrictions.get(type);
        if (r == null) {
            throw new CalcException("rules.yaml: неизвестный тип ограничения '" + type + "'");
        }
        return r;
    }

    public boolean hasRestriction(String type) {
        return restrictions.containsKey(type);
    }

    public Map<String, RestrictionRule> getRestrictions() {
        return restrictions;
    }

    public boolean isProhibitedHasPriority() {
        return prohibitedHasPriority;
    }

    public double getKAngle() {
        return kAngle;
    }

    public List<Double> getStandardAnglesDeg() {
        return standardAnglesDeg;
    }

    public double getAngleToleranceDeg() {
        return angleToleranceDeg;
    }

    public double getTieInChamberRadiusM() {
        return tieInChamberRadiusM;
    }

    public int getMaxSegmentsPerChamber() {
        return maxSegmentsPerChamber;
    }

    public int getLengthLimitMaxDnSteps() {
        return lengthLimitMaxDnSteps;
    }

    public double getGeometryToleranceM() {
        return geometryToleranceM;
    }

    public double getRoutingWorkspaceMarginM() {
        return routingWorkspaceMarginM;
    }

    public double getRoutingVertexOutwardM() {
        return routingVertexOutwardM;
    }

    public double getRoutingTurnPenaltyM() {
        return routingTurnPenaltyM;
    }

    public double getRoutingGridCellM() {
        return routingGridCellM;
    }

    public int getRoutingGridFallbackMinVertices() {
        return routingGridFallbackMinVertices;
    }

    public double getRoutingVertexSimplifyM() {
        return routingVertexSimplifyM;
    }

    public long getRoutingVgDeadlineMs() {
        return routingVgDeadlineMs;
    }

    public long getRoutingGridDeadlineMs() {
        return routingGridDeadlineMs;
    }

    public long getRoutingRouteBudgetMs() {
        return routingRouteBudgetMs;
    }

    public long getRoutingCrossingBudgetMs() {
        return routingCrossingBudgetMs;
    }

    public double getRoutingClusterPrefixM() {
        return routingClusterPrefixM;
    }

    public double getRoutingJointGroupM() {
        return routingJointGroupM;
    }

    public int getRoutingTieInAttempts() {
        return routingTieInAttempts;
    }

    public double getRoutingStartExitMaxM() {
        return routingStartExitMaxM;
    }

    public long getTieInCostRub() {
        return tieInCostRub;
    }

    public List<DnBand> getChamberScale() {
        return chamberScale;
    }

    public long getPenaltyFixedRub() {
        return penaltyFixedRub;
    }

    public long getPenaltyPerTphRub() {
        return penaltyPerTphRub;
    }

    public double getWeightCost() {
        return weightCost;
    }

    public double getWeightLength() {
        return weightLength;
    }

    public double getBaseCostRub() {
        return baseCostRub;
    }

    public double getBaseLengthM() {
        return baseLengthM;
    }

    public int getScoreScale() {
        return scoreScale;
    }

    public int getMaxVariants() {
        return maxVariants;
    }
}
