package ru.heatnet.calc.model;

/** Тип существующего объекта, в который выполняется врезка. */
public enum ExistingObjectType {
    HEAT_NETWORK("heat_network"),
    HEAT_CHAMBER("heat_chamber");

    private final String code;

    ExistingObjectType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
