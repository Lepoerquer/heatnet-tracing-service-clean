package ru.heatnet.calc.model;

import java.util.Objects;

/**
 * Врезка — корень дерева новой сети.
 *
 * <p>Для врезки в тело трубы ({@link ExistingObjectType#HEAT_NETWORK}) обязательно
 * {@code distanceFromUpstreamEndM} — длина части существующего участка от точки врезки
 * до его конца, связанного по топологии со следующим объектом к источнику
 * (upstream_object_id). Именно на эту часть действует дополнительный расход (разд. 7).
 * Сторону определяет network/ по графу, а не по порядку coordinates.
 * По разд. 8.2 в точке врезки в трубу создаётся новая камера: {@code newChamberId}.</p>
 */
public final class TieInPoint {

    private final String id;
    private final ExistingObjectType existingObjectType;
    private final String existingObjectId;
    private final Double distanceFromUpstreamEndM;
    private final String newChamberId;

    private TieInPoint(String id, ExistingObjectType type, String existingObjectId,
                       Double distanceFromUpstreamEndM, String newChamberId) {
        this.id = Objects.requireNonNull(id, "id");
        this.existingObjectType = Objects.requireNonNull(type, "existingObjectType");
        this.existingObjectId = Objects.requireNonNull(existingObjectId, "existingObjectId");
        this.distanceFromUpstreamEndM = distanceFromUpstreamEndM;
        this.newChamberId = newChamberId;
    }

    /** Врезка в существующую камеру. */
    public static TieInPoint intoChamber(String id, String chamberId) {
        return new TieInPoint(id, ExistingObjectType.HEAT_CHAMBER, chamberId, null, null);
    }

    /**
     * Врезка в тело существующего участка с новой камерой в точке врезки.
     *
     * @param distanceFromUpstreamEndM длина части участка от точки врезки в сторону источника, м
     * @param newChamberId id новой камеры в точке врезки (null — камеру не учитывать)
     */
    public static TieInPoint intoPipe(String id, String pipeId, double distanceFromUpstreamEndM, String newChamberId) {
        if (!(distanceFromUpstreamEndM >= 0)) {
            throw new IllegalArgumentException("Врезка " + id + ": расстояние до конца к источнику должно быть >= 0");
        }
        return new TieInPoint(id, ExistingObjectType.HEAT_NETWORK, pipeId, distanceFromUpstreamEndM, newChamberId);
    }

    public String getId() {
        return id;
    }

    public ExistingObjectType getExistingObjectType() {
        return existingObjectType;
    }

    public String getExistingObjectId() {
        return existingObjectId;
    }

    public Double getDistanceFromUpstreamEndM() {
        return distanceFromUpstreamEndM;
    }

    public String getNewChamberId() {
        return newChamberId;
    }
}
