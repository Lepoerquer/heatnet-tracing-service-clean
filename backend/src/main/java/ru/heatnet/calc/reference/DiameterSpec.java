package ru.heatnet.calc.reference;

/** Строка табл. 4.1 Технического приложения. */
public final class DiameterSpec {

    private final int dn;
    private final double capacityTph;
    private final double maxLengthM;
    private final long newCostRubPerM;
    private final long reconstructionCostRubPerM;

    public DiameterSpec(int dn, double capacityTph, double maxLengthM,
                        long newCostRubPerM, long reconstructionCostRubPerM) {
        this.dn = dn;
        this.capacityTph = capacityTph;
        this.maxLengthM = maxLengthM;
        this.newCostRubPerM = newCostRubPerM;
        this.reconstructionCostRubPerM = reconstructionCostRubPerM;
    }

    /** Условный диаметр, мм. */
    public int getDn() {
        return dn;
    }

    /** Пропускная способность, т/ч. */
    public double getCapacityTph() {
        return capacityTph;
    }

    /** Предельная длина непрерывной части одного DN, м. */
    public double getMaxLengthM() {
        return maxLengthM;
    }

    /** Стоимость 1 м нового строительства, руб./м. */
    public long getNewCostRubPerM() {
        return newCostRubPerM;
    }

    /** Стоимость 1 м реконструкции, руб./м. */
    public long getReconstructionCostRubPerM() {
        return reconstructionCostRubPerM;
    }

    @Override
    public String toString() {
        return "DN" + dn;
    }
}
