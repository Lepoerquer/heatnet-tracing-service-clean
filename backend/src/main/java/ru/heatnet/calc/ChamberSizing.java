package ru.heatnet.calc;

/**
 * Диаметр камеры в итоговом варианте.
 * Для новой камеры — наибольший DN примыкающих участков (разд. 8.2, 10.4).
 * Для существующей камеры врезки — исходный и требуемый DN (разд. 8.2, 10.5).
 */
public final class ChamberSizing {

    private final String chamberId;
    private final boolean existing;
    private final Integer originalDiameter;
    private final int requiredDiameter;
    private final boolean originalDiameterMissing;

    private ChamberSizing(String chamberId, boolean existing, Integer originalDiameter, int requiredDiameter,
                          boolean originalDiameterMissing) {
        this.chamberId = chamberId;
        this.existing = existing;
        this.originalDiameter = originalDiameter;
        this.requiredDiameter = requiredDiameter;
        this.originalDiameterMissing = originalDiameterMissing;
    }

    public static ChamberSizing newChamber(String chamberId, int diameter) {
        return new ChamberSizing(chamberId, false, null, diameter, false);
    }

    public static ChamberSizing existingChamber(String chamberId, int originalDiameter, int requiredDiameter,
                                                boolean originalDiameterMissing) {
        return new ChamberSizing(chamberId, true, originalDiameter, requiredDiameter, originalDiameterMissing);
    }

    public String getChamberId() {
        return chamberId;
    }

    public boolean isExisting() {
        return existing;
    }

    /** Входной diameter существующей камеры; null для новой. */
    public Integer getOriginalDiameter() {
        return originalDiameter;
    }

    /** Наибольший DN примыкающих участков в итоговом варианте. */
    public int getRequiredDiameter() {
        return requiredDiameter;
    }

    /** Для существующей камеры: нужна ли реконструкция. Новая камера всегда строится. */
    public boolean isReconstructionRequired() {
        return existing && requiredDiameter > originalDiameter;
    }

    public boolean isOriginalDiameterMissing() {
        return originalDiameterMissing;
    }
}
