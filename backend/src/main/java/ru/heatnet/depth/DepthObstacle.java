package ru.heatnet.depth;

import org.locationtech.jts.geom.Geometry;

/**
 * Препятствие для профиля глубины уже в EPSG:32637.
 * Точечные коммуникации (газ, кабель, теплосеть) и площадные (дорога, трамвай).
 */
public final class DepthObstacle {

    private final String id;
    private final String type;
    private final Geometry geometryUtm;
    private final int diameterMm;
    private final boolean surface;
    private final double minTopDepthM;

    private DepthObstacle(String id, String type, Geometry geometryUtm, int diameterMm,
                          boolean surface, double minTopDepthM) {
        this.id = id;
        this.type = type;
        this.geometryUtm = geometryUtm;
        this.diameterMm = diameterMm;
        this.surface = surface;
        this.minTopDepthM = minTopDepthM;
    }

    /** Газ, кабель или существующая теплосеть. */
    public static DepthObstacle utility(String id, String type, Geometry geometryUtm, int diameterMm) {
        return new DepthObstacle(id, type, geometryUtm, diameterMm, false, 0);
    }

    /** Дорога или трамвай: пол глубины верха габарита на протяжении пересечения. */
    public static DepthObstacle surface(String id, String type, Geometry geometryUtm, double minTopDepthM) {
        return new DepthObstacle(id, type, geometryUtm, 0, true, minTopDepthM);
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public Geometry getGeometryUtm() {
        return geometryUtm;
    }

    /** DN существующей теплосети; 0, если габарит берётся из табл. 4.3. */
    public int getDiameterMm() {
        return diameterMm;
    }

    public boolean isSurface() {
        return surface;
    }

    public double getMinTopDepthM() {
        return minTopDepthM;
    }
}
