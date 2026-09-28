package ru.heatnet.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

/**
 * Общие геометрические константы и утилиты.
 */
public final class GeoUtils {

    public static final int SRID_WGS84 = 4326;
    public static final int SRID_UTM37N = 32637;

    private static final GeometryFactory WGS84_FACTORY =
            new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    private GeoUtils() {
    }

    public static GeometryFactory wgs84Factory() {
        return WGS84_FACTORY;
    }

    public static Point pointWgs84(double lon, double lat) {
        return WGS84_FACTORY.createPoint(new Coordinate(lon, lat));
    }

    public static double distanceMeters(Coordinate a32637, Coordinate b32637) {
        double dx = a32637.x - b32637.x;
        double dy = a32637.y - b32637.y;
        return Math.hypot(dx, dy);
    }
}
