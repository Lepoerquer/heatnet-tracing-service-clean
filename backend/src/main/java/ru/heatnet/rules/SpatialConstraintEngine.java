package ru.heatnet.rules;

import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

import ru.heatnet.rules.model.CrossingResult;
import ru.heatnet.rules.model.PortalGate;
import ru.heatnet.rules.model.SpecialSection;

/**
 * Движок пространственных ограничений M2.
 * Контракт для M3 (маршрутизация) и M6 (K_спец).
 */
public interface SpatialConstraintEngine {

    /** true, если прямой отрезок нельзя проложить (запретные зоны, полигон дороги без портала). */
    boolean isSegmentBlocked(LineString segment, int candidateDn);

    /** Все пересечения со спецзонами на отрезке. */
    List<CrossingResult> findCrossings(LineString segment, int candidateDn);

    /**
     * Участки отрезка в зонах спецпрохода; при наложении — max(K_спец).
     */
    List<SpecialSection> extractSpecialSections(LineString segment, int candidateDn);

    /** Порталы площадных препятствий для visibility graph (M3). */
    List<PortalGate> portalGates();

    /** Проверка угла пересечения дороги/трамвая (протокол: ≥45° к оси). */
    boolean isRoadCrossingAngleValid(LineString segment, String restrictionId, int candidateDn);

    /**
     * Точка лежит в зоне спецпрохода (полигон дороги/трамвая + 3 м; газ/кабель/чужая теплосеть + 2 м).
     * Вершина трассы (поворот) в такой зоне сделала бы спецпроход непрямым (§4: «спецпроход
     * выполняется одним прямым участком»), поэтому маршрутизатор не ставит туда вершины графа.
     */
    default boolean isInsideSpecialZone(Coordinate point) {
        return false;
    }
}
