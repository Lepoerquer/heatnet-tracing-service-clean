package ru.heatnet.export;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AUDIT-12 (Claude, 24.09). Выгрузка одного варианта из общего result.geojson.
 *
 * <p>§7 приложения: все варианты режима — в одном FeatureCollection, различаются {@code variant_id}. Это
 * формат сдачи, и он не меняется. Но при открытии такого файла в QGIS/на карте без фильтра по
 * {@code variant_id} три варианта ложатся друг на друга: камеры разных вариантов стоят в 4–5 м друг от
 * друга, линии почти совпадают — это выглядело как «кривые камеры» и «сломанные трубы». Для просмотра
 * отдаётся отдельный файл на вариант (REST {@code GET /api/jobs/{id}/result?variant=vA}, CLI).</p>
 *
 * <p>Потоковая обработка: объекты читаются и пишутся по одному (выгрузка до 500 МБ, ТЗ 3.2).</p>
 */
public final class VariantSplitter {

    private static final JsonFactory FACTORY = new JsonFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VariantSplitter() {
    }

    /** Копирует в {@code out} только объекты варианта {@code variantId}. @return число объектов */
    public static int writeVariant(InputStream in, OutputStream out, String variantId) throws IOException {
        int n = 0;
        try (JsonParser p = MAPPER.getFactory().createParser(in);
             JsonGenerator g = FACTORY.createGenerator(out, JsonEncoding.UTF8)) {
            g.setPrettyPrinter(new DefaultPrettyPrinter());
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");
            g.writeArrayFieldStart("features");
            if (seekFeatures(p)) {
                while (p.nextToken() == JsonToken.START_OBJECT) {
                    JsonNode feature = MAPPER.readTree(p);
                    JsonNode props = feature.get("properties");
                    JsonNode vid = props == null ? null : props.get("variant_id");
                    if (vid != null && variantId.equals(vid.asText())) {
                        MAPPER.writeTree(g, feature);
                        n++;
                    }
                }
            }
            g.writeEndArray();
            g.writeEndObject();
        }
        return n;
    }

    /** Идентификаторы вариантов в файле в порядке появления. */
    public static List<String> variantIds(InputStream in) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        try (JsonParser p = MAPPER.getFactory().createParser(in)) {
            if (seekFeatures(p)) {
                while (p.nextToken() == JsonToken.START_OBJECT) {
                    JsonNode feature = MAPPER.readTree(p);
                    JsonNode props = feature.get("properties");
                    JsonNode vid = props == null ? null : props.get("variant_id");
                    if (vid != null) {
                        ids.add(vid.asText());
                    }
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private static boolean seekFeatures(JsonParser p) throws IOException {
        if (p.nextToken() != JsonToken.START_OBJECT) {
            return false;
        }
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.getCurrentName();
            JsonToken t = p.nextToken();
            if ("features".equals(name) && t == JsonToken.START_ARRAY) {
                return true;
            }
            p.skipChildren();
        }
        return false;
    }
}
