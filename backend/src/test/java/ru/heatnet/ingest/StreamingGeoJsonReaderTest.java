package ru.heatnet.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ru.heatnet.geo.ProjectionService;

class StreamingGeoJsonReaderTest {

    @Test
    void readsFeaturesWithoutLoadingWholeFile() throws Exception {
        IngestReport report = new IngestReport();
        StreamingGeoJsonReader reader = new StreamingGeoJsonReader(new ProjectionService());
        List<RawFeature> features;
        try (InputStream in = getClass().getResourceAsStream("/ingest/mini-network.geojson")) {
            features = reader.read(in, report);
        }
        assertEquals(5, features.size());
        assertFalse(report.hasErrors());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void propertiesNullDoesNotHang() throws Exception {
        String json = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":null,\"geometry\":"
                + "{\"type\":\"Point\",\"coordinates\":[37.64,55.70]}}"
                + "]}";
        IngestReport report = new IngestReport();
        new StreamingGeoJsonReader(new ProjectionService())
                .read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), report);
        assertTrue(report.getTotalFeatures() >= 0);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void propertiesArrayDoesNotHang() throws Exception {
        String json = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":[],\"id\":1,\"geometry\":"
                + "{\"type\":\"Point\",\"coordinates\":[37.64,55.70]}}"
                + "]}";
        IngestReport report = new IngestReport();
        new StreamingGeoJsonReader(new ProjectionService())
                .read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), report);
        assertTrue(report.getTotalFeatures() >= 0);
    }

    @Test
    void readsMultiLineStringRestriction() throws Exception {
        String json = "{\"type\":\"FeatureCollection\",\"features\":[{"
                + "\"type\":\"Feature\",\"properties\":{\"id\":9,\"object_type\":\"restriction\","
                + "\"restriction_type\":\"gas_pipeline\"},"
                + "\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":"
                + "[[[37.640,55.700],[37.641,55.701]],[[37.642,55.702],[37.643,55.703]]]}"
                + "}]}";
        IngestReport report = new IngestReport();
        List<RawFeature> features = new StreamingGeoJsonReader(new ProjectionService())
                .read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), report);
        assertEquals(1, features.size());
        assertEquals(RawFeature.Kind.RESTRICTION, features.get(0).getKind());
        assertTrue(features.get(0).getGeometryWgs84() != null);
        assertTrue(features.get(0).isNumericId());
        assertEquals(Long.valueOf(9L), features.get(0).exportId());
    }

    @Test
    void duplicateIdIsSkippedWithError() throws Exception {
        String json = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"id\":7,\"object_type\":\"heat_chamber\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.62,55.70]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"id\":\"7\",\"object_type\":\"heat_chamber\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.63,55.70]}}"
                + "]}";
        IngestReport report = new IngestReport();
        List<RawFeature> features = new StreamingGeoJsonReader(new ProjectionService())
                .read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), report);
        assertEquals(1, features.size());
        assertTrue(report.hasErrors());
    }
}
