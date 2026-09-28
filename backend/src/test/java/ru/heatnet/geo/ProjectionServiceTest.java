package ru.heatnet.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

class ProjectionServiceTest {

    private ProjectionService projectionService;

    @BeforeEach
    void setUp() {
        projectionService = new ProjectionService();
    }

    @Test
    void moscowPointProjectsToUtmMeters() {
        Coordinate utm = projectionService.toUtm(37.64103, 55.702353);
        assertTrue(utm.x > 400_000 && utm.x < 450_000, "Easting должен быть в UTM 37N");
        assertTrue(utm.y > 6_100_000 && utm.y < 6_200_000, "Northing должен быть в UTM 37N");
    }

    @Test
    void roundTripWgs84() {
        double lon = 37.64103;
        double lat = 55.702353;
        Coordinate utm = projectionService.toUtm(lon, lat);
        Coordinate back = projectionService.toWgs(utm.x, utm.y);
        assertEquals(lon, back.x, 1e-5);
        assertEquals(lat, back.y, 1e-5);
    }

    @Test
    void rejectsSwappedLonLat() {
        IllegalCoordinateOrderException ex = assertThrows(
                IllegalCoordinateOrderException.class,
                () -> projectionService.assertLonLat(55.75, 37.62));
        assertTrue(ex.getMessage().contains("lon, lat"));
    }

    @Test
    void rejectsHeuristicSwapInMoscowRange() {
        assertThrows(
                IllegalCoordinateOrderException.class,
                () -> projectionService.toUtm(55.702353, 37.64103));
    }
}
