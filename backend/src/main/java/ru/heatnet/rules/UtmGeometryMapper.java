package ru.heatnet.rules;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.geo.ProjectionService;

/** Перепроецирование геометрий ingest WGS84 → UTM 37N. */
public final class UtmGeometryMapper {

    private UtmGeometryMapper() {
    }

    public static Geometry toUtm(Geometry wgs84, ProjectionService projectionService, GeometryFactory utmFactory) {
        if (wgs84 == null) {
            throw new RulesException("Геометрия ограничения отсутствует");
        }
        switch (wgs84.getGeometryType()) {
            case "Point":
                return pointToUtm((Point) wgs84, projectionService, utmFactory);
            case "LineString":
                return lineToUtm((LineString) wgs84, projectionService, utmFactory);
            case "MultiLineString":
                return multiLineToUtm((MultiLineString) wgs84, projectionService, utmFactory);
            case "Polygon":
                return polygonToUtm((Polygon) wgs84, projectionService, utmFactory);
            case "MultiPolygon":
                return multiPolygonToUtm((MultiPolygon) wgs84, projectionService, utmFactory);
            default:
                throw new RulesException("Неподдерживаемый тип геометрии ограничения: " + wgs84.getGeometryType());
        }
    }

    private static Point pointToUtm(Point point, ProjectionService projectionService, GeometryFactory utmFactory) {
        Coordinate c = projectionService.toUtm(point.getX(), point.getY());
        return utmFactory.createPoint(c);
    }

    private static LineString lineToUtm(LineString line, ProjectionService projectionService, GeometryFactory utmFactory) {
        Coordinate[] utm = new Coordinate[line.getNumPoints()];
        for (int i = 0; i < line.getNumPoints(); i++) {
            Coordinate wgs = line.getCoordinateN(i);
            utm[i] = projectionService.toUtm(wgs.x, wgs.y);
        }
        return utmFactory.createLineString(utm);
    }

    private static MultiLineString multiLineToUtm(MultiLineString multi, ProjectionService projectionService,
                                                GeometryFactory utmFactory) {
        LineString[] lines = new LineString[multi.getNumGeometries()];
        for (int i = 0; i < multi.getNumGeometries(); i++) {
            lines[i] = lineToUtm((LineString) multi.getGeometryN(i), projectionService, utmFactory);
        }
        return utmFactory.createMultiLineString(lines);
    }

    private static Polygon polygonToUtm(Polygon polygon, ProjectionService projectionService, GeometryFactory utmFactory) {
        LinearRing shell = ringToUtm(polygon.getExteriorRing(), projectionService, utmFactory);
        LinearRing[] holes = new LinearRing[polygon.getNumInteriorRing()];
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            holes[i] = ringToUtm(polygon.getInteriorRingN(i), projectionService, utmFactory);
        }
        return utmFactory.createPolygon(shell, holes);
    }

    private static MultiPolygon multiPolygonToUtm(MultiPolygon multi, ProjectionService projectionService,
                                                GeometryFactory utmFactory) {
        Polygon[] polygons = new Polygon[multi.getNumGeometries()];
        for (int i = 0; i < multi.getNumGeometries(); i++) {
            polygons[i] = polygonToUtm((Polygon) multi.getGeometryN(i), projectionService, utmFactory);
        }
        return utmFactory.createMultiPolygon(polygons);
    }

    private static LinearRing ringToUtm(LineString ring, ProjectionService projectionService, GeometryFactory utmFactory) {
        Coordinate[] utm = new Coordinate[ring.getNumPoints()];
        for (int i = 0; i < ring.getNumPoints(); i++) {
            Coordinate wgs = ring.getCoordinateN(i);
            utm[i] = projectionService.toUtm(wgs.x, wgs.y);
        }
        return utmFactory.createLinearRing(utm);
    }
}
