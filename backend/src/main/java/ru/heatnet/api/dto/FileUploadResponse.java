package ru.heatnet.api.dto;

import java.util.Map;
import java.util.UUID;

public class FileUploadResponse {

    private UUID fileId;
    private String originalFilename;
    private int totalFeatures;
    private long warningCount;
    private long errorCount;
    private boolean hasErrors;
    private Map<String, Integer> countsByType;

    public UUID getFileId() {
        return fileId;
    }

    public void setFileId(UUID fileId) {
        this.fileId = fileId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public int getTotalFeatures() {
        return totalFeatures;
    }

    public void setTotalFeatures(int totalFeatures) {
        this.totalFeatures = totalFeatures;
    }

    public long getWarningCount() {
        return warningCount;
    }

    public void setWarningCount(long warningCount) {
        this.warningCount = warningCount;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(long errorCount) {
        this.errorCount = errorCount;
    }

    public boolean isHasErrors() {
        return hasErrors;
    }

    public void setHasErrors(boolean hasErrors) {
        this.hasErrors = hasErrors;
    }

    public Map<String, Integer> getCountsByType() {
        return countsByType;
    }

    public void setCountsByType(Map<String, Integer> countsByType) {
        this.countsByType = countsByType;
    }
}
