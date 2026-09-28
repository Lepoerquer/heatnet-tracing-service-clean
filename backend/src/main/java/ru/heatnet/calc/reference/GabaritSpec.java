package ru.heatnet.calc.reference;

/** Строка табл. 4.2: расчётный габарит пары труб новой сети. */
public final class GabaritSpec {

    private final int dn;
    private final double outerDiameterM;
    private final double gapM;
    private final double widthM;
    private final double heightM;

    public GabaritSpec(int dn, double outerDiameterM, double gapM, double widthM, double heightM) {
        this.dn = dn;
        this.outerDiameterM = outerDiameterM;
        this.gapM = gapM;
        this.widthM = widthM;
        this.heightM = heightM;
    }

    public int getDn() {
        return dn;
    }

    public double getOuterDiameterM() {
        return outerDiameterM;
    }

    public double getGapM() {
        return gapM;
    }

    /** Расчётная ширина пары труб, м (= 2·D + просвет). */
    public double getWidthM() {
        return widthM;
    }

    /** Расчётная высота, м (= D). */
    public double getHeightM() {
        return heightM;
    }

    /** Половина ширины — расстояние от оси линии до внешней границы габарита. */
    public double getHalfWidthM() {
        return widthM / 2.0;
    }
}
