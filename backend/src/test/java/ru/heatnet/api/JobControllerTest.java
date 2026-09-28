package ru.heatnet.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class JobControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("heatnet.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @Timeout(180)
    void uploadThenJobThenResult() throws Exception {
        byte[] payload = Files.readAllBytes(Path.of("src/test/resources/ingest/mini-network.geojson"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "mini-network.geojson", MediaType.APPLICATION_JSON_VALUE, payload);
        MvcResult upload = mockMvc.perform(multipart("/api/files").file(file))
                .andExpect(status().isOk())
                .andReturn();
        String fileId = JsonPath.read(upload.getResponse().getContentAsString(), "$.fileId");

        MvcResult created = mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"" + fileId + "\",\"enableDepth\":false}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").exists())
                .andReturn();
        String jobId = JsonPath.read(created.getResponse().getContentAsString(), "$.jobId");

        MvcResult earlyStatus = mockMvc.perform(get("/api/jobs/" + jobId))
                .andExpect(status().isOk())
                .andReturn();
        String statusNow = JsonPath.read(earlyStatus.getResponse().getContentAsString(), "$.status");
        if (!"DONE".equals(statusNow) && !"FAILED".equals(statusNow)) {
            // Между опросом статуса и скачиванием задача на быстром CI может успеть стать DONE.
            int early = mockMvc.perform(get("/api/jobs/" + jobId + "/result"))
                    .andReturn().getResponse().getStatus();
            assertTrue(early == 409 || early == 200,
                    "пока расчёт не завершён ожидается 409, если он уже готов — 200, получено " + early);
        }

        String status = statusNow;
        String message = "";
        long deadline = System.currentTimeMillis() + 150_000L;
        while (System.currentTimeMillis() < deadline) {
            MvcResult poll = mockMvc.perform(get("/api/jobs/" + jobId)).andExpect(status().isOk()).andReturn();
            String body = poll.getResponse().getContentAsString();
            status = JsonPath.read(body, "$.status");
            if ("FAILED".equals(status) && body.contains("\"message\"")) {
                message = JsonPath.read(body, "$.message");
            }
            if ("DONE".equals(status) || "FAILED".equals(status)) {
                break;
            }
            Thread.sleep(400L);
        }
        assertEquals("DONE", status, message);

        MvcResult result = mockMvc.perform(get("/api/jobs/" + jobId + "/result"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = json.readTree(result.getResponse().getContentAsByteArray());
        assertEquals("FeatureCollection", root.get("type").asText());
        boolean summary = false;
        boolean forbidden = false;
        boolean sawRankOne = false;
        String rankOneSummaryId = null;
        for (JsonNode feature : root.get("features")) {
            String type = feature.get("properties").get("object_type").asText();
            if ("variant_summary".equals(type)) {
                summary = true;
                assertTrue(feature.get("geometry").isNull());
                assertFalse(feature.get("properties").has("reconstruction_cost"));
                assertFalse(feature.get("properties").has("tie_in_cost"));
                if (feature.get("properties").get("rank").asInt() == 1) {
                    sawRankOne = true;
                    rankOneSummaryId = feature.get("properties").get("id").asText();
                }
            }
            if ("tie_in".equals(type) || type.contains("reconstruction")) {
                forbidden = true;
            }
        }
        // §6 приложения / п. 2.8 ТЗ: до трёх содержательно различных вариантов, но всегда
        // ровно один с rank=1 — id этого варианта зависит от того, какая стратегия M7 победила
        // (variant_id больше не хардкод "1"), поэтому берём его из самого ответа.
        assertTrue(summary);
        assertTrue(sawRankOne, "среди вариантов должен быть ровно один с rank=1");
        assertFalse(forbidden);

        mockMvc.perform(get("/api/jobs/" + jobId + "/explain/" + rankOneSummaryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectType").value("variant_summary"));
    }
}
