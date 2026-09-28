package ru.heatnet.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.heatnet.ContestDatasetPaths;
import ru.heatnet.calc.model.ExistingNetwork;

@SpringBootTest
class IngestServiceIntegrationTest {

    @Autowired
    private IngestService ingestService;

    @Test
    void ingestsMiniFixture() throws Exception {
        try (InputStream in = resource("/ingest/mini-network.geojson")) {
            IngestResult result = ingestService.ingest(in);
            assertFalse(result.getReport().hasErrors());
            assertEquals(1, result.getOksConnectionPoints().size());
            assertEquals(1, result.getRestrictionsWgs84().size());

            ExistingNetwork network = result.getExistingNetwork();
            assertEquals(1, network.getSegments().size());
            assertEquals(1, network.getChambers().size());
            assertTrue(network.isSource("1"));
            assertEquals("1", network.segment("3").getUpstreamObjectId());
        }
    }

    @Test
    void ingestsContestDataset() throws Exception {
        Path dataset = ContestDatasetPaths.require();

        try (InputStream in = Files.newInputStream(dataset)) {
            IngestResult result = ingestService.ingest(in);
            IngestReport report = result.getReport();

            assertEquals(17, report.getCountsByType().getOrDefault("oks_connection_point", 0));
            assertEquals(29, report.getCountsByType().getOrDefault("heat_network", 0));
            assertEquals(9, report.getCountsByType().getOrDefault("heat_chamber", 0));
            assertEquals(1, report.getCountsByType().getOrDefault("source", 0));
            assertEquals(88, report.getCountsByType().getOrDefault("restriction", 0));

            ExistingNetwork network = result.getExistingNetwork();
            assertEquals(29, network.getSegments().size());
            assertEquals(9, network.getChambers().size());
            assertEquals(17, result.getOksConnectionPoints().size());
            assertEquals(88, result.getRestrictionsWgs84().size());

            long unreachable = report.getMessages().stream()
                    .filter(m -> "NET_UNREACHABLE".equals(m.getCode()) || "NET_ISOLATED".equals(m.getCode()))
                    .count();
            assertEquals(0, unreachable, "Все участки должны быть достижимы из source");
        }
    }

    @Test
    void brokenAttributeProducesReportNotCrash() throws Exception {
        String broken = "{"
                + "\"type\":\"FeatureCollection\","
                + "\"features\":[{\"type\":\"Feature\","
                + "\"properties\":{\"id\":99,\"object_type\":\"heat_network\"},"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.64,55.70],[37.641,55.701]]}"
                + "}]}";
        IngestResult result = ingestService.ingest(new java.io.ByteArrayInputStream(broken.getBytes()));
        assertTrue(result.getReport().hasErrors());
        assertTrue(result.getExistingNetwork().getSegments().isEmpty());
    }

    private InputStream resource(String path) {
        InputStream in = getClass().getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("Test resource not found: " + path);
        }
        return in;
    }
}
