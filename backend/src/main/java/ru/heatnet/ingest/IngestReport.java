package ru.heatnet.ingest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Накопитель диагностики и счётчиков по типам объектов. */
public final class IngestReport {

    private final List<IngestMessage> messages = new ArrayList<>();
    private final Map<String, Integer> countsByType = new LinkedHashMap<>();
    private int skippedFeatures;
    private int totalFeatures;

    public void add(IngestMessage message) {
        messages.add(message);
    }

    public void info(String code, String objectId, String message) {
        add(IngestMessage.info(code, objectId, message));
    }

    public void warn(String code, String objectId, String message) {
        add(IngestMessage.warning(code, objectId, message));
    }

    public void error(String code, String objectId, String message) {
        add(IngestMessage.error(code, objectId, message));
    }

    public void incrementType(String objectType) {
        countsByType.merge(objectType, 1, Integer::sum);
    }

    public void setTotalFeatures(int totalFeatures) {
        this.totalFeatures = totalFeatures;
    }

    public void incrementSkipped() {
        skippedFeatures++;
    }

    public List<IngestMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public Map<String, Integer> getCountsByType() {
        return Collections.unmodifiableMap(countsByType);
    }

    public int getSkippedFeatures() {
        return skippedFeatures;
    }

    public int getTotalFeatures() {
        return totalFeatures;
    }

    public boolean hasErrors() {
        return messages.stream().anyMatch(m -> m.getSeverity() == IngestSeverity.ERROR);
    }

    public long errorCount() {
        return messages.stream().filter(m -> m.getSeverity() == IngestSeverity.ERROR).count();
    }

    public long warningCount() {
        return messages.stream().filter(m -> m.getSeverity() == IngestSeverity.WARNING).count();
    }
}
