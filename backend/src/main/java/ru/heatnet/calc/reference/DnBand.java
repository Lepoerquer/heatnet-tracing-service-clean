package ru.heatnet.calc.reference;

/**
 * Диапазон DN [dnFrom; dnTo] (включительно) со значением: отступ, стоимость камеры и т.п.
 * Числовое значение хранится как double; для денег используйте {@link #getLongValue()}.
 */
public final class DnBand {

    private final int dnFrom;
    private final int dnTo;
    private final double value;

    public DnBand(int dnFrom, int dnTo, double value) {
        this.dnFrom = dnFrom;
        this.dnTo = dnTo;
        this.value = value;
    }

    public boolean contains(int dn) {
        return dn >= dnFrom && dn <= dnTo;
    }

    public int getDnFrom() {
        return dnFrom;
    }

    public int getDnTo() {
        return dnTo;
    }

    public double getValue() {
        return value;
    }

    public long getLongValue() {
        return Math.round(value);
    }
}
