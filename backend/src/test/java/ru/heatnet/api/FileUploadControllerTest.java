package ru.heatnet.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class FileUploadControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("heatnet.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetUploads() throws Exception {
        Path uploads = tempDir.resolve("uploads");
        if (Files.exists(uploads)) {
            try (var walk = Files.walk(uploads)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // ignore cleanup errors in tests
                    }
                });
            }
        }
    }

    @Test
    void uploadAndFetchReport() throws Exception {
        byte[] payload = Files.readAllBytes(Path.of("src/test/resources/ingest/mini-network.geojson"));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "mini-network.geojson",
                MediaType.APPLICATION_JSON_VALUE,
                payload);

        MvcResult upload = mockMvc.perform(multipart("/api/files").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").exists())
                .andExpect(jsonPath("$.totalFeatures").value(5))
                .andExpect(jsonPath("$.hasErrors").value(false))
                .andReturn();

        String fileId = com.jayway.jsonpath.JsonPath.read(
                upload.getResponse().getContentAsString(), "$.fileId");

        mockMvc.perform(get("/api/files/{id}/report", fileId).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFeatures").value(5))
                .andExpect(jsonPath("$.countsByType.heat_network").value(1));

        assertTrue(Files.isRegularFile(tempDir.resolve("uploads").resolve(fileId).resolve("report.json")));
    }

    @Test
    void brokenSchemaReturnsReportNotHttp500() throws Exception {
        String broken = "{"
                + "\"type\":\"FeatureCollection\","
                + "\"features\":[{\"type\":\"Feature\","
                + "\"properties\":{\"id\":99,\"object_type\":\"heat_network\"},"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.64,55.70],[37.641,55.701]]}"
                + "}]}";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "broken.geojson",
                MediaType.APPLICATION_JSON_VALUE,
                broken.getBytes());

        mockMvc.perform(multipart("/api/files").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasErrors").value(true))
                .andExpect(jsonPath("$.errorCount").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void missingUploadReturns404() throws Exception {
        mockMvc.perform(get("/api/files/{id}/report", "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/files/{id}/report", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingFilePartReturns400() throws Exception {
        mockMvc.perform(multipart("/api/files"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putOnUploadReturns405() throws Exception {
        mockMvc.perform(put("/api/files"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void truncatedGeoJsonReturns400Not500() throws Exception {
        byte[] truncated = "{\"type\":\"FeatureCollection\",\"features\":[{".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "truncated.geojson", MediaType.APPLICATION_JSON_VALUE, truncated);
        mockMvc.perform(multipart("/api/files").file(file))
                .andExpect(status().isBadRequest());
    }
}
