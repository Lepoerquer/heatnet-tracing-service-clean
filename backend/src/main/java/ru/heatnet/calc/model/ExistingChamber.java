package ru.heatnet.calc.model;

import java.util.Objects;

/** Существующая тепловая камера (входной heat_chamber). */
public final class ExistingChamber {

    private final String id;
    private final Integer diameter;
    private final String upstreamObjectId;

    /**
     * @param diameter          входной diameter камеры; null, если атрибута нет
     * @param upstreamObjectId  следующий объект к источнику
     */
    public ExistingChamber(String id, Integer diameter, String upstreamObjectId) {
        this.id = Objects.requireNonNull(id, "id");
        this.diameter = diameter;
        this.upstreamObjectId = upstreamObjectId;
    }

    public String getId() {
        return id;
    }

    public Integer getDiameter() {
        return diameter;
    }

    public String getUpstreamObjectId() {
        return upstreamObjectId;
    }
}
