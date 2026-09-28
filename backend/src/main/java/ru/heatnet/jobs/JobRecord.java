package ru.heatnet.jobs;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/** Состояние одной задачи расчёта. */
public final class JobRecord {

    private final UUID id;
    private final boolean enableDepth;
    private volatile String status;
    private volatile String stage;
    private volatile int progress;
    private volatile String message;
    private volatile Path resultPath;
    private volatile Map<String, Map<String, Object>> explanations = Collections.emptyMap();

    public JobRecord(UUID id, boolean enableDepth) {
        this.id = id;
        this.enableDepth = enableDepth;
        this.status = "QUEUED";
        this.stage = "QUEUED";
        this.progress = 0;
    }

    public UUID getId() {
        return id;
    }

    public boolean isEnableDepth() {
        return enableDepth;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Path getResultPath() {
        return resultPath;
    }

    public void setResultPath(Path resultPath) {
        this.resultPath = resultPath;
    }

    public Map<String, Map<String, Object>> getExplanations() {
        return explanations;
    }

    public void setExplanations(Map<String, Map<String, Object>> explanations) {
        this.explanations = explanations;
    }
}
