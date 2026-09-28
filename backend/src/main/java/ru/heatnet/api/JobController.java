package ru.heatnet.api;

import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ru.heatnet.api.dto.CreateJobRequest;
import ru.heatnet.api.dto.JobStatusResponse;
import ru.heatnet.jobs.JobRecord;
import ru.heatnet.jobs.JobService;

@RestController
@RequestMapping(path = "/api/jobs")
@Tag(name = "Jobs", description = "Асинхронный расчёт трассировки (M9)")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Поставить расчёт по уже загруженному fileId")
    public ResponseEntity<JobStatusResponse> create(@RequestBody CreateJobRequest request) {
        if (request == null || request.getFileId() == null) {
            throw new IllegalArgumentException("Не передан fileId");
        }
        JobRecord job = jobService.submit(request.getFileId(), request.isEnableDepth());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(job));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Статус, стадия и прогресс задачи")
    public JobStatusResponse status(@PathVariable("id") UUID id) {
        return toResponse(jobService.get(id));
    }

    @GetMapping(value = "/{id}/result", produces = "application/geo+json")
    @Operation(summary = "Скачать result.geojson (все варианты, §7). Параметр variant — только один вариант "
            + "(для просмотра на карте: без фильтра по variant_id варианты ложатся друг на друга)")
    public ResponseEntity<FileSystemResource> result(@PathVariable("id") UUID id,
                                                     @RequestParam(value = "variant", required = false) String variant)
            throws java.io.IOException {
        java.nio.file.Path path = jobService.result(id);
        if (variant == null || variant.isEmpty()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"result.geojson\"")
                    .contentType(MediaType.parseMediaType("application/geo+json"))
                    .body(new FileSystemResource(path));
        }
        // AUDIT-12 (Claude, 24.09): файл варианта собирается один раз рядом с результатом (потоково, через временный
        // файл и атомарную замену) и отдаётся синхронно, как полный файл. Асинхронная потоковая отдача упиралась бы
        // в тайм-аут async-запроса контейнера (30 с по умолчанию) на больших результатах (ТЗ п. 3.2: до 500 МБ).
        String safe = variant.replaceAll("[^A-Za-z0-9_.-]", "_");
        StringBuilder hex = new StringBuilder();
        for (byte b : variant.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hex.append(String.format("%02x", b & 0xff));
        }
        java.nio.file.Path part = path.resolveSibling("result_variant_" + hex + ".geojson");
        synchronized (this) {
            if (!java.nio.file.Files.isRegularFile(part) || java.nio.file.Files.getLastModifiedTime(part)
                    .compareTo(java.nio.file.Files.getLastModifiedTime(path)) < 0) {
                java.nio.file.Path tmp = java.nio.file.Files.createTempFile(path.getParent(), "variant-", ".tmp");
                try {
                    try (java.io.InputStream in = java.nio.file.Files.newInputStream(path);
                         java.io.OutputStream out = new java.io.BufferedOutputStream(
                                 java.nio.file.Files.newOutputStream(tmp), 1 << 16)) {
                        ru.heatnet.export.VariantSplitter.writeVariant(in, out, variant);
                    }
                    java.nio.file.Files.move(tmp, part, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    java.nio.file.Files.deleteIfExists(tmp);
                }
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"result_" + safe + ".geojson\"")
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .body(new FileSystemResource(part));
    }

    @GetMapping("/{id}/explain/{objectId}")
    @Operation(summary = "Почему участок, камера или сводка посчитаны так")
    public Map<String, Object> explain(@PathVariable("id") UUID id, @PathVariable("objectId") String objectId) {
        return jobService.explain(id, objectId);
    }

    private static JobStatusResponse toResponse(JobRecord job) {
        JobStatusResponse response = new JobStatusResponse();
        response.setJobId(job.getId());
        response.setStatus(job.getStatus());
        response.setStage(job.getStage());
        response.setProgress(job.getProgress());
        response.setMessage(job.getMessage());
        return response;
    }
}
