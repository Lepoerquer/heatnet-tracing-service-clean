package ru.heatnet.rules;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.calc.reference.RestrictionRule;
import ru.heatnet.rules.model.RestrictionFeature;

/** Синтетические геометрии UTM для тестов M2/M3. */
public final class RulesTestGeometry {

    private RulesTestGeometry() {
    }

    public static Polygon square(GeometryFactory gf, double minX, double minY, double size) {
        Coordinate[] ring = new Coordinate[] {
                new Coordinate(minX, minY),
                new Coordinate(minX + size, minY),
                new Coordinate(minX + size, minY + size),
                new Coordinate(minX, minY + size),
                new Coordinate(minX, minY)
        };
        return gf.createPolygon(gf.createLinearRing(ring));
    }

    public static LineString line(GeometryFactory gf, double x1, double y1, double x2, double y2) {
        return gf.createLineString(new Coordinate[] {new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }

    public static RestrictionFeature feature(ReferenceData ref, GeometryFactory gf, String id, String rulesKey,
                                      Polygon geometry) {
        RestrictionRule rule = ref.getRules().restriction(rulesKey);
        return new RestrictionFeature(id, rulesKey, rule, geometry);
    }

    public static RestrictionFeature lineFeature(ReferenceData ref, GeometryFactory gf, String id, String rulesKey,
                                          LineString geometry) {
        RestrictionRule rule = ref.getRules().restriction(rulesKey);
        return new RestrictionFeature(id, rulesKey, rule, geometry);
    }
}
