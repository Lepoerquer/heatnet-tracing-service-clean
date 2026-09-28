package ru.heatnet.jobs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import ru.heatnet.config.HeatnetProperties;
import ru.heatnet.ingest.UploadIngestService;

/** Очередь расчётов M9: файл уже загружен через POST /api/files. */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final UploadIngestService uploads;
    private final CalculationPipeline pipeline;
    private final HeatnetProperties properties;
    private final ThreadPoolTaskExecutor executor;
    private final ConcurrentHashMap<UUID, JobRecord> jobs = new ConcurrentHashMap<>();

    public JobService(UploadIngestService uploads, CalculationPipeline pipeline, HeatnetProperties properties,
                      @Qualifier(JobExecutorConfig.EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.uploads = uploads;
        this.pipeline = pipeline;
        this.properties = properties;
        this.executor = executor;
    }

    public JobRecord submit(UUID fileId, boolean enableDepth) {
        if (fileId == null) {
            throw new IllegalArgumentException("Не передан fileId");
        }
        Path input = uploads.findInput(fileId)
                .orElseThrow(() -> new NoSuchElementException("Загрузка не найдена: " + fileId));
        UUID jobId = UUID.randomUUID();
        JobRecord job = new JobRecord(jobId, enableDepth);
        jobs.put(jobId, job);
        try {
            executor.execute(() -> execute(job, input));
        } catch (RejectedExecutionException ex) {
            jobs.remove(jobId);
            throw new JobRejectedException("Очередь расчётов заполнена, повторите позже");
        }
        return job;
    }

    public JobRecord get(UUID jobId) {
        JobRecord job = jobs.get(jobId);
        if (job == null) {
            throw new NoSuchElementException("Задача не найдена: " + jobId);
        }
        return job;
    }

    public Path result(UUID jobId) {
        JobRecord job = get(jobId);
        if (!"DONE".equals(job.getStatus())) {
            throw new JobNotReadyException("Результат ещё не готов, статус " + job.getStatus());
        }
        Path path = job.getResultPath();
        if (path == null || !Files.isRegularFile(path)) {
            throw new NoSuchElementException("Файл результата не найден");
        }
        return path;
    }

    public Map<String, Object> explain(UUID jobId, String objectId) {
        JobRecord job = get(jobId);
        if (!"DONE".equals(job.getStatus())) {
            throw new JobNotReadyException("Объяснение ещё не готово, статус " + job.getStatus());
        }
        Map<String, Object> body = job.getExplanations().get(objectId);
        if (body == null) {
            throw new NoSuchElementException("Объект не найден: " + objectId);
        }
        return body;
    }

    private void execute(JobRecord job, Path input) {
        job.setStatus("RUNNING");
        job.setStage("INGEST");
        job.setProgress(5);
        Path dir = Path.of(properties.getDataDir()).resolve("jobs").resolve(job.getId().toString());
        Path result = dir.resolve("result.geojson");
        try {
            Files.createDirectories(dir);
            try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(result), 1 << 16)) {
                Map<String, Map<String, Object>> explanations = pipeline.run(input, out, job.isEnableDepth(),
                        new JobProgress() {
                            @Override
                            public void update(String stage, int progress) {
                                job.setStage(stage);
                                job.setProgress(progress);
                            }
                        });
                job.setExplanations(explanations);
            }
            job.setResultPath(result);
            job.setStatus("DONE");
            job.setStage("DONE");
            job.setProgress(100);
        } catch (Exception | Error ex) {
            // AUDIT-24.09 (Claude): раньше ловился только Exception — при OutOfMemoryError /
            // StackOverflowError задача навсегда оставалась в статусе RUNNING.
            log.warn("Задача {} завершилась ошибкой: {}", job.getId(), ex.toString());
            job.setStatus("FAILED");
            job.setStage("FAILED");
            job.setMessage(ex.getMessage() == null ? "Ошибка расчёта" : ex.getMessage());
            try {
                Files.deleteIfExists(result);
            } catch (IOException ignored) {
                // файл результата при ошибке не обязателен
            }
        }
    }
}
