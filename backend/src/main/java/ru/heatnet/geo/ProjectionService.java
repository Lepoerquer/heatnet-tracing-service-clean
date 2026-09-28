package ru.heatnet.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.stereotype.Service;

/**
 * Перепроецирование WGS84 (EPSG:4326, [lon, lat]) ↔ UTM 37N (EPSG:32637, метры).
 */
@Service
public class ProjectionService {

    private static final double MOSCOW_LON_MIN = 35.0;
    private static final double MOSCOW_LON_MAX = 40.0;
    private static final double MOSCOW_LAT_MIN = 54.0;
    private static final double MOSCOW_LAT_MAX = 57.0;

    private final CoordinateTransform toUtm;
    private final CoordinateTransform toWgs;
    private final GeometryFactory utmFactory;

    public ProjectionService() {
        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem crs4326 = crsFactory.createFromParameters("EPSG:4326",
                "+proj=longlat +datum=WGS84 +no_defs");
        CoordinateReferenceSystem crs32637 = crsFactory.createFromParameters("EPSG:32637",
                "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs");
        CoordinateTransformFactory transformFactory = new CoordinateTransformFactory();
        this.toUtm = transformFactory.createTransform(crs4326, crs32637);
        this.toWgs = transformFactory.createTransform(crs32637, crs4326);
        this.utmFactory = new GeometryFactory(new PrecisionModel(), GeoUtils.SRID_UTM37N);
    }

    /**
     * Проверяет порядок [lon, lat] для точки в районе Москвы.
     */
    public void assertLonLat(double lon, double lat) {
        if (lon > 45.0 && lat < 45.0) {
            throw new IllegalCoordinateOrderException(
                    "Перепутаны широта и долгота! Ожидается [lon, lat], получено [" + lon + ", " + lat + "]");
        }
        if (lon >= MOSCOW_LAT_MIN && lon <= MOSCOW_LAT_MAX && lat >= MOSCOW_LON_MIN && lat <= MOSCOW_LON_MAX) {
            throw new IllegalCoordinateOrderException(
                    "Перепутаны широта и долгота! Ожидается [lon, lat], получено [" + lon + ", " + lat + "]");
        }
    }

    public Coordinate toUtm(double lon, double lat) {
        assertLonLat(lon, lat);
        ProjCoordinate src = new ProjCoordinate(lon, lat);
        ProjCoordinate dst = new ProjCoordinate();
        toUtm.transform(src, dst);
        return new Coordinate(dst.x, dst.y);
    }

    public Coordinate toWgs(double easting, double northing) {
        ProjCoordinate src = new ProjCoordinate(easting, northing);
        ProjCoordinate dst = new ProjCoordinate();
        toWgs.transform(src, dst);
        return new Coordinate(dst.x, dst.y);
    }

    public Point pointToUtm(double lon, double lat) {
        Coordinate c = toUtm(lon, lat);
        return utmFactory.createPoint(c);
    }

    public Point pointToWgs(double easting, double northing) {
        Coordinate c = toWgs(easting, northing);
        return GeoUtils.wgs84Factory().createPoint(c);
    }

    public LineString lineToUtm(Coordinate[] wgsCoords) {
        Coordinate[] utm = new Coordinate[wgsCoords.length];
        for (int i = 0; i < wgsCoords.length; i++) {
            utm[i] = toUtm(wgsCoords[i].x, wgsCoords[i].y);
        }
        return utmFactory.createLineString(utm);
    }

    public Polygon polygonToUtm(Coordinate[][] ringsWgs) {
        return polygonFromRings(ringsWgs);
    }

    public MultiPolygon multiPolygonToUtm(Coordinate[][][] polysWgs) {
        Polygon[] polygons = new Polygon[polysWgs.length];
        for (int i = 0; i < polysWgs.length; i++) {
            polygons[i] = polygonFromRings(polysWgs[i]);
        }
        return utmFactory.createMultiPolygon(polygons);
    }

    public GeometryFactory utmFactory() {
        return utmFactory;
    }

    private Polygon polygonFromRings(Coordinate[][] ringsWgs) {
        LinearRing shell = ringToUtm(ringsWgs[0]);
        if (ringsWgs.length == 1) {
            return utmFactory.createPolygon(shell);
        }
        LinearRing[] holes = new LinearRing[ringsWgs.length - 1];
        for (int i = 1; i < ringsWgs.length; i++) {
            holes[i - 1] = ringToUtm(ringsWgs[i]);
        }
        return utmFactory.createPolygon(shell, holes);
    }

    private LinearRing ringToUtm(Coordinate[] ringWgs) {
        Coordinate[] utm = new Coordinate[ringWgs.length];
        for (int i = 0; i < ringWgs.length; i++) {
            utm[i] = toUtm(ringWgs[i].x, ringWgs[i].y);
        }
        return utmFactory.createLinearRing(utm);
    }
}
