package ru.heatnet.api;

import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ru.heatnet.api.dto.FileUploadResponse;
import ru.heatnet.ingest.IngestReport;
import ru.heatnet.ingest.UploadIngestService;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Files", description = "Загрузка GeoJSON и отчёт ingest (M1/M9)")
public class FileUploadController {

    private final UploadIngestService uploadIngestService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FileUploadController(UploadIngestService uploadIngestService) {
        this.uploadIngestService = uploadIngestService;
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Загрузить GeoJSON и выполнить ingest")
    public FileUploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Файл не передан или пустой");
        }
        String filename = file.getOriginalFilename() == null ? "upload.geojson" : file.getOriginalFilename();
        UploadIngestService.UploadIngestResult result =
                uploadIngestService.upload(file.getInputStream(), filename);
        IngestReport report = result.getIngestResult().getReport();

        FileUploadResponse response = new FileUploadResponse();
        response.setFileId(result.getSessionId());
        response.setOriginalFilename(filename);
        response.setTotalFeatures(report.getTotalFeatures());
        response.setWarningCount(report.warningCount());
        response.setErrorCount(report.errorCount());
        response.setHasErrors(report.hasErrors());
        response.setCountsByType(report.getCountsByType());
        return response;
    }

    @GetMapping("/files/{id}/report")
    @Operation(summary = "Получить JSON-отчёт ingest по id загрузки")
    public ResponseEntity<Map<String, Object>> report(@PathVariable("id") UUID id) throws IOException {
        String json = uploadIngestService.loadReportJson(id)
                .orElseThrow(() -> new NoSuchElementException("Загрузка не найдена: " + id));
        Map<String, Object> body = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        return ResponseEntity.ok(body);
    }
}
