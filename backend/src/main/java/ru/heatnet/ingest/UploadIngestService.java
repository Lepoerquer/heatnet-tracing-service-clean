package ru.heatnet.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ru.heatnet.config.HeatnetProperties;

/**
 * Загрузка GeoJSON на диск + ingest + опционально PostGIS.
 */
@Service
public class UploadIngestService {

    private final HeatnetProperties properties;
    private final IngestService ingestService;
    private final Optional<IngestPersistenceService> persistence;

    public UploadIngestService(HeatnetProperties properties,
                               IngestService ingestService,
                               Optional<IngestPersistenceService> persistence) {
        this.properties = properties;
        this.ingestService = ingestService;
        this.persistence = persistence;
    }

    public UploadIngestResult upload(InputStream inputStream, String originalFilename) throws IOException {
        UUID sessionId = UUID.randomUUID();
        Path uploadDir = Path.of(properties.getDataDir()).resolve("uploads").resolve(sessionId.toString());
        Files.createDirectories(uploadDir);
        Path storedFile = uploadDir.resolve("input.geojson");
        Files.copy(inputStream, storedFile, StandardCopyOption.REPLACE_EXISTING);

        IngestResult result = ingestService.ingest(storedFile);

        Files.writeString(uploadDir.resolve("report.json"), IngestReportJson.toJson(result.getReport()));

        persistence.ifPresent(p -> p.persist(
                sessionId,
                originalFilename,
                storedFile.toString(),
                result,
                result.getAcceptedFeatures()));

        return new UploadIngestResult(sessionId, storedFile, result);
    }

    /** Файл, сохранённый POST /api/files. Нужен M9, чтобы не парсить отчёт заново с нуля. */
    public Optional<Path> findInput(UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        Path stored = Path.of(properties.getDataDir())
                .resolve("uploads")
                .resolve(sessionId.toString())
                .resolve("input.geojson");
        if (Files.isRegularFile(stored)) {
            return Optional.of(stored);
        }
        return Optional.empty();
    }

    public Optional<String> loadReportJson(UUID sessionId) {
        Path reportFile = Path.of(properties.getDataDir())
                .resolve("uploads")
                .resolve(sessionId.toString())
                .resolve("report.json");
        if (Files.isRegularFile(reportFile)) {
            try {
                return Optional.of(Files.readString(reportFile));
            } catch (IOException ex) {
                return Optional.empty();
            }
        }
        return persistence.flatMap(p -> {
            if (p.sessionExists(sessionId)) {
                return Optional.of(p.loadReportJson(sessionId));
            }
            return Optional.empty();
        });
    }

    public static final class UploadIngestResult {
        private final UUID sessionId;
        private final Path storedFile;
        private final IngestResult ingestResult;

        UploadIngestResult(UUID sessionId, Path storedFile, IngestResult ingestResult) {
            this.sessionId = sessionId;
            this.storedFile = storedFile;
            this.ingestResult = ingestResult;
        }

        public UUID getSessionId() {
            return sessionId;
        }

        public Path getStoredFile() {
            return storedFile;
        }

        public IngestResult getIngestResult() {
            return ingestResult;
        }
    }
}
