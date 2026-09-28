package ru.heatnet.ingest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * DoD M1: ingest файла ~500 МБ при heap &lt; 1 ГБ ({@code -Xmx1024m} в surefire).
 * Размер файла на диске создаётся пробелами между небольшим числом feature (как в конкурсном наборе),
 * чтобы проверить потоковый JsonParser, а не раздувание in-memory модели.
 */
@SpringBootTest
class LargeIngestMemoryTest {

    private static final int CI_MB = 20;
    private static final int FULL_MB = 500;
    private static final long MAX_HEAP_MB = 980;
    private static final int CONTEST_LIKE_FEATURES = 150;
    private static final char[] SPACE_CHUNK = new char[64 * 1024];

    static {
        java.util.Arrays.fill(SPACE_CHUNK, ' ');
    }

    @Autowired
    private IngestService ingestService;

    @Test
    void streams20MbFileWithinMemoryBudget() throws Exception {
        runBench(CI_MB);
    }

    @Test
    void streams500MbFileWithinMemoryBudget() throws Exception {
        runBench(FULL_MB);
    }

    private void runBench(int sizeMb) throws Exception {
        Path tempFile = Files.createTempFile("large-ingest-" + sizeMb + "-", ".geojson");
        try {
            writeSyntheticGeoJson(tempFile, sizeMb);
            long fileBytes = Files.size(tempFile);
            assertTrue(fileBytes >= sizeMb * 1024L * 1024L * 9 / 10,
                    "Синтетический файл ~" + sizeMb + " МБ, фактически " + (fileBytes / 1024 / 1024) + " МБ");

            System.gc();
            long before = usedHeapMb();
            try (InputStream in = Files.newInputStream(tempFile)) {
                IngestResult result = ingestService.ingest(in);
                assertTrue(result.getReport().getTotalFeatures() >= CONTEST_LIKE_FEATURES);
                assertTrue(result.getRestrictionsWgs84().size() >= CONTEST_LIKE_FEATURES);
            }
            System.gc();
            long after = usedHeapMb();
            assertTrue(Math.max(before, after) < MAX_HEAP_MB,
                    "Ingest " + sizeMb + " МБ: heap ~" + Math.max(before, after)
                            + " МБ, лимит DoD < " + MAX_HEAP_MB + " МБ");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static void writeSyntheticGeoJson(Path target, int sizeMb) throws Exception {
        long targetBytes = sizeMb * 1024L * 1024L;
        int featureCount = CONTEST_LIKE_FEATURES;

        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writer.write("{\"type\":\"FeatureCollection\",\"features\":[");
            long written = estimateUtf8("{\"type\":\"FeatureCollection\",\"features\":[");

            long payloadBudget = targetBytes - written - estimateUtf8("]}");
            long paddingTotal = Math.max(0, payloadBudget - featurePayloadEstimate(featureCount));
            long paddingPerGap = paddingTotal / Math.max(1, featureCount - 1);

            for (int id = 1; id <= featureCount; id++) {
                if (id > 1) {
                    writer.write(',');
                    written += 1;
                    written += writePadding(writer, paddingPerGap);
                }
                String feature = smallRestrictionFeature(id);
                writer.write(feature);
                written += estimateUtf8(feature);
            }
            writer.write("]}");
        }
    }

    private static long writePadding(BufferedWriter writer, long bytes) throws Exception {
        long remaining = bytes;
        while (remaining > 0) {
            int chunk = (int) Math.min(SPACE_CHUNK.length, remaining);
            writer.write(SPACE_CHUNK, 0, chunk);
            remaining -= chunk;
        }
        return bytes;
    }

    private static String smallRestrictionFeature(int id) {
        double lon = 37.60 + (id % 100) * 0.0001;
        double lat = 55.70 + (id % 100) * 0.0001;
        return "{"
                + "\"type\":\"Feature\","
                + "\"properties\":{\"id\":" + id + ",\"object_type\":\"restriction\",\"restriction_type\":\"park\"},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[["
                + lon + "," + lat + "],[" + (lon + 0.0001) + "," + lat + "],"
                + "[" + (lon + 0.0001) + "," + (lat + 0.0001) + "],[" + lon + "," + (lat + 0.0001) + "],"
                + "[" + lon + "," + lat + "]]]}"
                + "}";
    }

    private static long featurePayloadEstimate(int count) {
        return estimateUtf8(smallRestrictionFeature(1)) * (long) count;
    }

    private static long estimateUtf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long usedHeapMb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }
}
