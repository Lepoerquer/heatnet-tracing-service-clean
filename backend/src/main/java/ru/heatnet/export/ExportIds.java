package ru.heatnet.export;

import java.util.LinkedHashMap;
import java.util.Map;

import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.RawFeature;

/** Исходный JSON-тип id: число остаётся числом (§7.2, N-4). */
public final class ExportIds {

    private final Map<String, Object> byId;

    public ExportIds(Map<String, Object> byId) {
        this.byId = byId;
    }

    public static ExportIds fromIngest(IngestResult ingest) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (ingest != null) {
            for (RawFeature feature : ingest.getAcceptedFeatures()) {
                map.put(feature.getId(), feature.exportId());
            }
        }
        return new ExportIds(map);
    }

    public static ExportIds of(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return new ExportIds(map);
    }

    public Object of(String id) {
        if (id == null) {
            return null;
        }
        Object typed = byId.get(id);
        return typed != null ? typed : id;
    }
}
