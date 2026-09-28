package ru.heatnet.ingest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import ru.heatnet.geo.GeoUtils;
import ru.heatnet.geo.ProjectionService;

class NetworkTreeBuilderCycleTest {

    private final ProjectionService projectionService = new ProjectionService();
    private final NetworkTreeBuilder builder = new NetworkTreeBuilder(projectionService);
    private final GeometryFactory wgs = GeoUtils.wgs84Factory();

    @Test
    void detectsExplicitUpstreamCycle() {
        Point source = wgs.createPoint(new Coordinate(37.60, 55.75));
        Point chamber = wgs.createPoint(new Coordinate(37.601, 55.751));

        RawFeature sourceFeature = RawFeature.of("1", RawFeature.Kind.SOURCE, map("id", 1), source, null);
        RawFeature chamberFeature = RawFeature.of("2", RawFeature.Kind.HEAT_CHAMBER, map(
                "id", 2,
                "object_type", "heat_chamber",
                "upstream_object_id", "3",
                "diameter", 200), chamber, null);
        RawFeature segmentFeature = RawFeature.of("3", RawFeature.Kind.HEAT_NETWORK, map(
                "id", 3,
                "object_type", "heat_network",
                "upstream_object_id", "2",
                "diameter", 200,
                "flow_tph", 10.0), null, new Coordinate[] {
                        new Coordinate(37.602, 55.752),
                        new Coordinate(37.603, 55.753)
                });

        IngestReport report = new IngestReport();
        builder.build(Arrays.asList(sourceFeature, chamberFeature, segmentFeature), 1.0, report);

        assertTrue(report.getMessages().stream().anyMatch(m -> "UPSTREAM_CYCLE".equals(m.getCode())));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
