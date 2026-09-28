package ru.heatnet.geo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.valid.IsValidOp;

import ru.heatnet.ingest.IngestReport;

class GeometryRepairTest {

    private final GeometryFactory factory = GeoUtils.wgs84Factory();

    @Test
    void repairsSelfIntersectingPolygon() {
        Coordinate[] coords = new Coordinate[] {
                new Coordinate(37.60, 55.75),
                new Coordinate(37.61, 55.76),
                new Coordinate(37.60, 55.76),
                new Coordinate(37.61, 55.75),
                new Coordinate(37.60, 55.75)
        };
        LinearRing ring = factory.createLinearRing(coords);
        Polygon broken = factory.createPolygon(ring);
        assertFalse(new IsValidOp(broken).isValid());

        IngestReport report = new IngestReport();
        Geometry repaired = GeometryRepair.repair(broken, "poly-1", report);
        assertNotNull(repaired);
        assertTrue(new IsValidOp(repaired).isValid());
        assertTrue(report.getMessages().stream().anyMatch(m -> "GEOMETRY_REPAIRED".equals(m.getCode())));
    }
}
