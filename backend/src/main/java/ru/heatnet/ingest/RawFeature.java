package ru.heatnet.ingest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

/** Распарсенный объект GeoJSON до сборки дерева сети. */
public final class RawFeature {

    public enum Kind {
        OKS_CONNECTION_POINT,
        OKS_FUTURE,
        RESTRICTION,
        HEAT_NETWORK,
        HEAT_CHAMBER,
        SOURCE,
        UNKNOWN
    }

    private final String id;
    private final boolean numericId;
    /** Исходное значение id из GeoJSON (Number или String) — для выгрузки с тем же JSON-типом. */
    private final Object originalId;
    private final Kind kind;
    private final Map<String, Object> properties;
    private final Geometry geometryWgs84;
    private final Coordinate[] lineCoordinatesWgs84;

    private RawFeature(String id, boolean numericId, Object originalId, Kind kind, Map<String, Object> properties,
                       Geometry geometryWgs84, Coordinate[] lineCoordinatesWgs84) {
        this.id = id;
        this.numericId = numericId;
        this.originalId = originalId;
        this.kind = kind;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        this.geometryWgs84 = geometryWgs84;
        this.lineCoordinatesWgs84 = lineCoordinatesWgs84;
    }

    public static RawFeature of(String id, Kind kind, Map<String, Object> properties,
                                Geometry geometryWgs84, Coordinate[] lineCoordinatesWgs84) {
        return of(id, kind, properties, geometryWgs84, lineCoordinatesWgs84, false);
    }

    public static RawFeature of(String id, Kind kind, Map<String, Object> properties,
                                Geometry geometryWgs84, Coordinate[] lineCoordinatesWgs84,
                                boolean numericId) {
        return new RawFeature(
                Objects.requireNonNull(id, "id"),
                numericId,
                null,
                Objects.requireNonNull(kind, "kind"),
                properties == null ? Collections.emptyMap() : properties,
                geometryWgs84,
                lineCoordinatesWgs84);
    }

    /**
     * AUDIT-24.09 (Claude): фича с явно заданным внутренним id (например, при совпадении id у объектов
     * разных типов) и исходным значением id для выгрузки.
     */
    public static RawFeature withOriginalId(String internalId, Object originalId, Kind kind,
                                            Map<String, Object> properties, Geometry geometryWgs84,
                                            Coordinate[] lineCoordinatesWgs84) {
        Object normalized = originalId;
        if (originalId instanceof Integer || originalId instanceof Short || originalId instanceof Byte) {
            normalized = Long.valueOf(((Number) originalId).longValue()); // прежний контракт: целые — Long
        }
        return new RawFeature(Objects.requireNonNull(internalId, "id"), originalId instanceof Number, normalized,
                Objects.requireNonNull(kind, "kind"),
                properties == null ? Collections.emptyMap() : properties, geometryWgs84, lineCoordinatesWgs84);
    }

    public String getId() {
        return id;
    }

    public boolean isNumericId() {
        return numericId;
    }

    /**
     * Исходный JSON-тип id для экспорта M8: число остаётся числом, строка — строкой (§7.2).
     */
    public Object exportId() {
        if (originalId != null) {
            return originalId;
        }
        if (!numericId) {
            return id;
        }
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException ex) {
            return id;
        }
    }

    public Kind getKind() {
        return kind;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public Geometry getGeometryWgs84() {
        return geometryWgs84;
    }

    public Coordinate[] getLineCoordinatesWgs84() {
        return lineCoordinatesWgs84;
    }

    public String objectTypeProperty() {
        Object v = properties.get("object_type");
        return v == null ? null : String.valueOf(v);
    }

    /**
     * AUDIT-12 (Claude, 24.09): число из строкового атрибута «рабочей системы»: пробелы, десятичная запятая.
     * @throws NumberFormatException если это не число
     */
    public static double parseDoubleLenient(Object v) {
        String s = String.valueOf(v).trim().replace('\u00a0', ' ').replace(" ", "").replace(',', '.');
        return Double.parseDouble(s);
    }

    /** Целое из строки: "500", " 500 ", "500.0"; дробное значение — NumberFormatException. */
    public static int parseIntLenient(Object v) {
        double d = parseDoubleLenient(v);
        if (Double.isNaN(d) || d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) {
            throw new NumberFormatException("не целое: " + v);
        }
        return (int) d;
    }

    public String restrictionType() {
        Object v = properties.get("restriction_type");
        if (v != null) {
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        if ("oks_existing".equals(objectTypeProperty())) {
            return "oks";
        }
        return null;
    }
}
