package ru.heatnet.calc;

/**
 * Часть существующего участка с постоянным дополнительным расходом.
 * Положение — расстояние от конца участка, обращённого к источнику (0) до конца к потребителям.
 */
public final class FlowPiece {

    private final String existingObjectId;
    private final double fromM;
    private final double toM;
    private final double existingFlowTph;
    private final double addedFlowTph;
    private final double calculatedFlowTph;
    private final int existingDiameter;
    private final int requiredDiameter;
    private final boolean existingFlowMissing;

    public FlowPiece(String existingObjectId, double fromM, double toM, double existingFlowTph,
                     double addedFlowTph, double calculatedFlowTph, int existingDiameter,
                     int requiredDiameter, boolean existingFlowMissing) {
        this.existingObjectId = existingObjectId;
        this.fromM = fromM;
        this.toM = toM;
        this.existingFlowTph = existingFlowTph;
        this.addedFlowTph = addedFlowTph;
        this.calculatedFlowTph = calculatedFlowTph;
        this.existingDiameter = existingDiameter;
        this.requiredDiameter = requiredDiameter;
        this.existingFlowMissing = existingFlowMissing;
    }

    public String getExistingObjectId() {
        return existingObjectId;
    }

    /** Начало части, м от конца участка со стороны источника. */
    public double getFromM() {
        return fromM;
    }

    /** Конец части, м от конца участка со стороны источника. */
    public double getToM() {
        return toM;
    }

    public double getLengthM() {
        return toM - fromM;
    }

    public double getExistingFlowTph() {
        return existingFlowTph;
    }

    public double getAddedFlowTph() {
        return addedFlowTph;
    }

    public double getCalculatedFlowTph() {
        return calculatedFlowTph;
    }

    public int getExistingDiameter() {
        return existingDiameter;
    }

    /** Минимальный DN по итоговому расходу (может быть меньше существующего). */
    public int getRequiredDiameter() {
        return requiredDiameter;
    }

    /** Требуется ли реконструкция этой части. */
    public boolean isReconstructionRequired() {
        return requiredDiameter > existingDiameter;
    }

    /** true — flow_tph во входных данных отсутствовал, принят 0 (дефолт команды). */
    public boolean isExistingFlowMissing() {
        return existingFlowMissing;
    }

    /** Весь ли участок затронут (для экспорта: геометрия = исходная линия). */
    public boolean coversWholeSegment(double segmentLengthM, double toleranceM) {
        return fromM <= toleranceM && toM >= segmentLengthM - toleranceM;
    }
}
