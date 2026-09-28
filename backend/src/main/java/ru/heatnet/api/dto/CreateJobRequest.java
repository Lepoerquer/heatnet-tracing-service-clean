package ru.heatnet.api.dto;

import java.util.UUID;

public class CreateJobRequest {

    private UUID fileId;
    private boolean enableDepth;

    public UUID getFileId() {
        return fileId;
    }

    public void setFileId(UUID fileId) {
        this.fileId = fileId;
    }

    public boolean isEnableDepth() {
        return enableDepth;
    }

    public void setEnableDepth(boolean enableDepth) {
        this.enableDepth = enableDepth;
    }
}
