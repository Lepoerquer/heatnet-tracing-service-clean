package ru.heatnet.rules;

import java.util.Collections;
import java.util.List;

import org.locationtech.jts.geom.Geometry;

/**
 * Движок M2 и контуры запретных зон для построения visibility graph (M3).
 */
public final class SpatialConstraintBundle {

    private final SpatialConstraintEngine engine;
    private final List<Geometry> blockedOutlines;
    private final Geometry ownApproachPolygon;

    public SpatialConstraintBundle(SpatialConstraintEngine engine, List<Geometry> blockedOutlines) {
        this(engine, blockedOutlines, null);
    }

    public SpatialConstraintBundle(SpatialConstraintEngine engine, List<Geometry> blockedOutlines,
                                   Geometry ownApproachPolygon) {
        this.engine = engine;
        this.blockedOutlines = Collections.unmodifiableList(blockedOutlines);
        this.ownApproachPolygon = ownApproachPolygon;
    }

    public SpatialConstraintEngine getEngine() {
        return engine;
    }

    /** Контуры blocked-зон в EPSG:32637 для извлечения вершин графа видимости. */
    public List<Geometry> getBlockedOutlines() {
        return blockedOutlines;
    }

    /** Собственный полигон ОКС цели — для прямого захода §2.2; в движке уже исключён. */
    public Geometry getOwnApproachPolygon() {
        return ownApproachPolygon;
    }

    public SpatialConstraintBundle withOwnApproach(Geometry polygon) {
        return new SpatialConstraintBundle(engine, blockedOutlines, polygon);
    }
}
