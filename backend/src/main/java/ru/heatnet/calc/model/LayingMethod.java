package ru.heatnet.calc.model;

/** Способ прокладки участка (выходной атрибут laying_method). */
public enum LayingMethod {
    BASE("base"),
    SPECIAL("special");

    private final String code;

    LayingMethod(String code) {
        this.code = code;
    }

    /** Значение для выходного GeoJSON. */
    public String getCode() {
        return code;
    }
}
