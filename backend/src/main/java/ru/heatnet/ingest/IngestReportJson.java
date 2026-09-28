package ru.heatnet.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Сериализация {@link IngestReport} в JSON для хранения и API. */
public final class IngestReportJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IngestReportJson() {
    }

    public static Map<String, Object> toMap(IngestReport report) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("totalFeatures", report.getTotalFeatures());
        root.put("skippedFeatures", report.getSkippedFeatures());
        root.put("countsByType", report.getCountsByType());
        root.put("warningCount", report.warningCount());
        root.put("errorCount", report.errorCount());
        root.put("hasErrors", report.hasErrors());

        List<Map<String, Object>> messages = new ArrayList<>();
        for (IngestMessage msg : report.getMessages()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", msg.getSeverity().name());
            m.put("code", msg.getCode());
            m.put("objectId", msg.getObjectId());
            m.put("message", msg.getMessage());
            messages.add(m);
        }
        root.put("messages", messages);
        return root;
    }

    public static String toJson(IngestReport report) {
        try {
            return MAPPER.writeValueAsString(toMap(report));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Не удалось сериализовать IngestReport", ex);
        }
    }
}
