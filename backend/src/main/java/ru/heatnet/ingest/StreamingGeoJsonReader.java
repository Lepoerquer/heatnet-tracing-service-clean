package ru.heatnet.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import ru.heatnet.geo.GeoUtils;
import ru.heatnet.geo.GeometryRepair;
import ru.heatnet.geo.IllegalCoordinateOrderException;
import ru.heatnet.geo.ProjectionService;

/**
 * Потоковый парсер GeoJSON FeatureCollection (Jackson Streaming, без readTree).
 */
@Component
public class StreamingGeoJsonReader {

    private final JsonFactory jsonFactory = new JsonFactory();
    private final ProjectionService projectionService;
    private final GeometryFactory wgs84Factory = GeoUtils.wgs84Factory();

    public StreamingGeoJsonReader(ProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    public List<RawFeature> read(InputStream input, IngestReport report) throws IOException {
        return read(input, report, null, null);
    }

    /**
     * AUDIT-24.09 (Claude): потоковое чтение с фильтрами для больших файлов (ТЗ 3.2: до 3 ГБ без загрузки
     * файла в память).
     *
     * @param kinds  какие типы вообще рассматривать в этом проходе (null — все); прочие пропускаются молча
     * @param retain какие из рассматриваемых объектов удерживать в памяти (null — все); остальные только
     *               учитываются в отчёте
     */
    public List<RawFeature> read(InputStream input, IngestReport report,
                                 java.util.function.Predicate<RawFeature.Kind> kinds,
                                 java.util.function.Predicate<RawFeature> retain) throws IOException {
        List<RawFeature> features = new ArrayList<>();
        int notRetained = 0;
        int skippedBefore = report.getSkippedFeatures();
        java.util.Map<String, RawFeature.Kind> seenIds = new java.util.HashMap<>();
        try (JsonParser parser = jsonFactory.createParser(input)) {
            if (!seekFeaturesArray(parser)) {
                report.error("GEOJSON_STRUCTURE", null, "Ожидается FeatureCollection с массивом features");
                return features;
            }
            while (parser.nextToken() != JsonToken.END_ARRAY && parser.currentToken() != null) {
                if (parser.currentToken() == JsonToken.START_OBJECT) {
                    RawFeature feature = parseFeature(parser, report);
                    if (feature != null && kinds != null && !kinds.test(feature.getKind())) {
                        continue;
                    }
                    if (feature != null) {
                        RawFeature.Kind prevKind = seenIds.get(feature.getId());
                        if (prevKind != null && prevKind == feature.getKind()) {
                            report.error("DUPLICATE_ID", feature.getId(),
                                    "id встречается повторно у объектов одного типа "
                                            + "(возможно, число и строка с одним значением) — объект пропущен");
                            report.incrementSkipped();
                            continue;
                        }
                        if (prevKind != null) {
                            // AUDIT-24.09 (Claude): данные «из рабочих систем» (Разъяснение №17) могут иметь
                            // независимую нумерацию по таблицам. Раньше второй объект с тем же id молча
                            // выбрасывался (мог пропасть ОКС или участок сети). Теперь он получает
                            // внутренний id, а в выгрузку идёт исходный id с исходным JSON-типом.
                            String internal = feature.getId() + "#" + feature.getKind().name().toLowerCase();
                            report.warn("DUPLICATE_ID_OTHER_TYPE", feature.getId(),
                                    "id совпадает с объектом типа " + prevKind.name().toLowerCase()
                                            + " — внутренний id " + internal + ", в выгрузке исходный id");
                            feature = RawFeature.withOriginalId(internal, feature.exportId(), feature.getKind(),
                                    feature.getProperties(), feature.getGeometryWgs84(),
                                    feature.getLineCoordinatesWgs84());
                            if (seenIds.containsKey(internal)) {
                                report.error("DUPLICATE_ID", feature.getId(), "повтор id — объект пропущен");
                                report.incrementSkipped();
                                continue;
                            }
                        }
                        seenIds.put(feature.getId(), feature.getKind());
                        if (retain != null && !retain.test(feature)) {
                            notRetained++;
                            report.incrementType(feature.objectTypeProperty() == null
                                    ? feature.getKind().name().toLowerCase()
                                    : feature.objectTypeProperty());
                            continue;
                        }
                        features.add(feature);
                        report.incrementType(feature.objectTypeProperty() == null
                                ? feature.getKind().name().toLowerCase()
                                : feature.objectTypeProperty());
                    }
                }
            }
        }
        report.setTotalFeatures(report.getTotalFeatures() + features.size() + notRetained
                + (report.getSkippedFeatures() - skippedBefore));
        return features;
    }

    private boolean seekFeaturesArray(JsonParser parser) throws IOException {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            return false;
        }
        while (parser.nextToken() != JsonToken.END_OBJECT && parser.currentToken() != null) {
            String field = parser.currentName();
            parser.nextToken();
            if ("features".equals(field) && parser.currentToken() == JsonToken.START_ARRAY) {
                return true;
            }
            parser.skipChildren();
        }
        return false;
    }

    private RawFeature parseFeature(JsonParser parser, IngestReport report) {
        Map<String, Object> properties = new LinkedHashMap<>();
        Geometry geometryWgs84 = null;
        Coordinate[] lineCoords = null;
        String objectType = null;
        String id = null;
        Object topLevelId = null;

        try {
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                if ("properties".equals(field)) {
                    readProperties(parser, properties);
                    normalizeCodes(properties);
                    objectType = stringProp(properties, "object_type");
                    id = stringId(properties.get("id"));
                } else if ("id".equals(field)) {
                    // AUDIT-12 (Claude, 24.09): GeoJSON допускает id на уровне Feature (RFC 7946 §3.2). Если в
                    // properties id нет, берётся он — иначе объект отбрасывался как «Feature без id».
                    topLevelId = readPropertyValue(parser);
                } else if ("geometry".equals(field)) {
                    ParsedGeometry parsed = readGeometry(parser, report, id);
                    geometryWgs84 = parsed.geometry;
                    lineCoords = parsed.lineCoordinates;
                } else {
                    parser.skipChildren();
                }
            }
        } catch (IOException ex) {
            report.error("FEATURE_PARSE", id, "Ошибка разбора feature: " + ex.getMessage());
            report.incrementSkipped();
            return null;
        } catch (IllegalCoordinateOrderException ex) {
            report.error("COORD_ORDER", id, ex.getMessage());
            report.incrementSkipped();
            return null;
        } catch (RuntimeException ex) {
            report.error("GEOMETRY_INVALID", id, ex.getMessage());
            report.incrementSkipped();
            return null;
        }

        if ((id == null || id.isBlank()) && topLevelId != null && stringId(topLevelId) != null) {
            properties.put("id", topLevelId);
            id = stringId(topLevelId);
        }
        if (id == null || id.isBlank()) {
            report.error("MISSING_ID", null, "Feature без id");
            report.incrementSkipped();
            return null;
        }
        if (objectType == null || objectType.isBlank()) {
            report.error("MISSING_OBJECT_TYPE", id, "Feature без object_type");
            report.incrementSkipped();
            return null;
        }
        if (geometryWgs84 == null && lineCoords == null) {
            report.error("MISSING_GEOMETRY", id, "Feature без геометрии");
            report.incrementSkipped();
            return null;
        }

        RawFeature.Kind kind = mapKind(objectType);
        Object rawId = properties.get("id");
        if (rawId instanceof Number) {
            // исходное число (в т.ч. дробное/большое) уходит в выгрузку тем же JSON-типом (§7.2)
            return RawFeature.withOriginalId(id, rawId, kind, properties, geometryWgs84, lineCoords);
        }
        return RawFeature.of(id, kind, properties, geometryWgs84, lineCoords, false);
    }

    private void readProperties(JsonParser parser, Map<String, Object> target) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return;
        }
        if (token != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() != JsonToken.END_OBJECT && parser.currentToken() != null) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                parser.skipChildren();
                continue;
            }
            String name = parser.currentName();
            parser.nextToken();
            target.put(name, readPropertyValue(parser));
        }
    }

    private Object readPropertyValue(JsonParser parser) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.VALUE_STRING) {
            return parser.getText();
        }
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return parser.getNumberValue();
        }
        if (token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE) {
            return parser.getBooleanValue();
        }
        parser.skipChildren();
        return null;
    }

    private ParsedGeometry readGeometry(JsonParser parser, IngestReport report, String featureId) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return ParsedGeometry.empty();
        }
        String type = null;
        Coordinate[] lineCoordinates = null;
        Geometry geometry = null;
        Object deferredCoordinates = null;

        while (parser.nextToken() != JsonToken.END_OBJECT && parser.currentToken() != null) {
            String field = parser.currentName();
            parser.nextToken();
            if ("type".equals(field)) {
                type = parser.getText();
            } else if ("coordinates".equals(field) && type == null) {
                // AUDIT-12 (Claude, 24.09): порядок ключей в JSON-объекте не задан (RFC 8259). Выгрузки с
                // сортировкой ключей пишут "coordinates" раньше "type" — раньше такие координаты
                // пропускались, и ВСЕ объекты файла отбрасывались как «пустая геометрия».
                deferredCoordinates = readNested(parser);
            } else if ("coordinates".equals(field)) {
                if ("Point".equals(type)) {
                    Coordinate c = readCoordinatePair(parser);
                    projectionService.assertLonLat(c.x, c.y);
                    geometry = wgs84Factory.createPoint(c);
                } else if ("LineString".equals(type)) {
                    lineCoordinates = readLineCoordinates(parser);
                    geometry = wgs84Factory.createLineString(lineCoordinates.clone());
                } else if ("Polygon".equals(type)) {
                    geometry = readPolygon(parser);
                } else if ("MultiPolygon".equals(type)) {
                    geometry = readMultiPolygon(parser);
                } else if ("MultiLineString".equals(type)) {
                    geometry = readMultiLineString(parser);
                    if (geometry != null && geometry.getNumGeometries() > 1) {
                        // AUDIT-24.09 (Claude): части, стыкующиеся концами, сшиваются в одну линию —
                        // иначе у heat_network бралась только первая часть, остальные терялись.
                        org.locationtech.jts.operation.linemerge.LineMerger merger =
                                new org.locationtech.jts.operation.linemerge.LineMerger();
                        merger.add(geometry);
                        java.util.Collection<?> merged = merger.getMergedLineStrings();
                        if (merged.size() == 1) {
                            geometry = (Geometry) merged.iterator().next();
                        } else if (featureId != null) {
                            report.warn("MULTILINE_PARTS", featureId, "MultiLineString из " + merged.size()
                                    + " несвязанных частей: для трубы используется первая часть");
                        }
                    }
                    if (geometry instanceof LineString) {
                        lineCoordinates = ((LineString) geometry).getCoordinates();
                    } else if (geometry != null && geometry.getNumGeometries() > 0
                            && geometry.getGeometryN(0) instanceof LineString) {
                        lineCoordinates = ((LineString) geometry.getGeometryN(0)).getCoordinates();
                    }
                } else {
                    parser.skipChildren();
                }
            } else {
                parser.skipChildren();
            }
        }

        if (type == null) {
            throw new IllegalArgumentException("geometry.type отсутствует");
        }
        if (geometry == null && deferredCoordinates != null) {
            geometry = fromNested(type, deferredCoordinates);
            if (geometry instanceof LineString) {
                lineCoordinates = ((LineString) geometry).getCoordinates();
            } else if (geometry != null && "MultiLineString".equals(type)) {
                org.locationtech.jts.operation.linemerge.LineMerger merger =
                        new org.locationtech.jts.operation.linemerge.LineMerger();
                merger.add(geometry);
                java.util.Collection<?> merged = merger.getMergedLineStrings();
                if (merged.size() == 1) {
                    geometry = (Geometry) merged.iterator().next();
                } else if (featureId != null) {
                    report.warn("MULTILINE_PARTS", featureId, "MultiLineString из " + merged.size()
                            + " несвязанных частей: для трубы используется первая часть");
                }
                if (geometry instanceof LineString) {
                    lineCoordinates = ((LineString) geometry).getCoordinates();
                } else if (geometry.getNumGeometries() > 0 && geometry.getGeometryN(0) instanceof LineString) {
                    lineCoordinates = ((LineString) geometry.getGeometryN(0)).getCoordinates();
                }
            }
        }
        if (geometry == null && !"GeometryCollection".equals(type)) {
            throw new IllegalArgumentException("Неподдерживаемая или пустая геометрия: " + type);
        }
        if (geometry != null) {
            geometry = GeometryRepair.repair(geometry, featureId, report);
            if (geometry == null) {
                return ParsedGeometry.empty();
            }
            if (lineCoordinates != null && geometry instanceof LineString) {
                lineCoordinates = ((LineString) geometry).getCoordinates();
            }
        }
        return new ParsedGeometry(geometry, lineCoordinates);
    }

    /** Вложенные массивы координат как списки чисел (для geometry, где "type" идёт после "coordinates"). */
    private Object readNested(JsonParser parser) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_ARRAY) {
            List<Object> out = new ArrayList<>();
            while (parser.nextToken() != JsonToken.END_ARRAY && parser.currentToken() != null) {
                out.add(readNested(parser));
            }
            return out;
        }
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return parser.getDoubleValue();
        }
        parser.skipChildren();
        return null;
    }

    @SuppressWarnings("unchecked")
    private Geometry fromNested(String type, Object nested) {
        switch (type) {
            case "Point":
                return wgs84Factory.createPoint(nestedPoint(nested));
            case "LineString":
                return wgs84Factory.createLineString(nestedLine(nested));
            case "MultiLineString": {
                List<LineString> lines = new ArrayList<>();
                for (Object o : (List<Object>) nested) {
                    lines.add(wgs84Factory.createLineString(nestedLine(o)));
                }
                if (lines.isEmpty()) {
                    throw new IllegalArgumentException("MultiLineString без линий");
                }
                return lines.size() == 1 ? lines.get(0)
                        : wgs84Factory.createMultiLineString(lines.toArray(new LineString[0]));
            }
            case "Polygon":
                return nestedPolygon(nested);
            case "MultiPolygon": {
                List<Polygon> polygons = new ArrayList<>();
                for (Object o : (List<Object>) nested) {
                    polygons.add(nestedPolygon(o));
                }
                return wgs84Factory.createMultiPolygon(polygons.toArray(new Polygon[0]));
            }
            default:
                return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Coordinate nestedPoint(Object nested) {
        if (!(nested instanceof List) || ((List<Object>) nested).size() < 2) {
            throw new IllegalArgumentException("Ожидается массив координат [lon, lat]");
        }
        List<Object> xy = (List<Object>) nested;
        if (!(xy.get(0) instanceof Double) || !(xy.get(1) instanceof Double)) {
            throw new IllegalArgumentException("Ожидается массив координат [lon, lat]");
        }
        Coordinate c = new Coordinate((Double) xy.get(0), (Double) xy.get(1));
        projectionService.assertLonLat(c.x, c.y);
        return c;
    }

    @SuppressWarnings("unchecked")
    private Coordinate[] nestedLine(Object nested) {
        List<Coordinate> coords = new ArrayList<>();
        for (Object o : (List<Object>) nested) {
            coords.add(nestedPoint(o));
        }
        if (coords.size() < 2) {
            throw new IllegalArgumentException("LineString должен содержать минимум 2 точки");
        }
        return coords.toArray(new Coordinate[0]);
    }

    @SuppressWarnings("unchecked")
    private Polygon nestedPolygon(Object nested) {
        List<LinearRing> rings = new ArrayList<>();
        for (Object ring : (List<Object>) nested) {
            List<Coordinate> coords = new ArrayList<>();
            for (Object o : (List<Object>) ring) {
                coords.add(nestedPoint(o));
            }
            if (coords.size() < 4) {
                throw new IllegalArgumentException("LinearRing должен содержать минимум 4 точки");
            }
            rings.add(wgs84Factory.createLinearRing(coords.toArray(new Coordinate[0])));
        }
        if (rings.isEmpty()) {
            throw new IllegalArgumentException("Polygon без колец");
        }
        return rings.size() == 1 ? wgs84Factory.createPolygon(rings.get(0))
                : wgs84Factory.createPolygon(rings.get(0), rings.subList(1, rings.size()).toArray(new LinearRing[0]));
    }

    private Coordinate readCoordinatePair(JsonParser parser) throws IOException {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            throw new IllegalArgumentException("Ожидается массив координат [lon, lat]");
        }
        parser.nextToken();
        double lon = parser.getDoubleValue();
        parser.nextToken();
        double lat = parser.getDoubleValue();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            // 3D coords — пропускаем Z
        }
        return new Coordinate(lon, lat);
    }

    private Coordinate[] readLineCoordinates(JsonParser parser) throws IOException {
        List<Coordinate> coords = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY && parser.currentToken() != null) {
            coords.add(readCoordinatePair(parser));
            projectionService.assertLonLat(coords.get(coords.size() - 1).x, coords.get(coords.size() - 1).y);
        }
        if (coords.size() < 2) {
            throw new IllegalArgumentException("LineString должен содержать минимум 2 точки");
        }
        return coords.toArray(new Coordinate[0]);
    }

    private Polygon readPolygon(JsonParser parser) throws IOException {
        List<LinearRing> rings = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY && parser.currentToken() != null) {
            rings.add(readLinearRing(parser));
        }
        if (rings.isEmpty()) {
            throw new IllegalArgumentException("Polygon без колец");
        }
        LinearRing shell = rings.get(0);
        if (rings.size() == 1) {
            return wgs84Factory.createPolygon(shell);
        }
        LinearRing[] holes = rings.subList(1, rings.size()).toArray(new LinearRing[0]);
        return wgs84Factory.createPolygon(shell, holes);
    }

    private Geometry readMultiLineString(JsonParser parser) throws IOException {
        List<LineString> lines = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY && parser.currentToken() != null) {
            lines.add(wgs84Factory.createLineString(readLineCoordinates(parser)));
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("MultiLineString без линий");
        }
        if (lines.size() == 1) {
            return lines.get(0);
        }
        return wgs84Factory.createMultiLineString(lines.toArray(new LineString[0]));
    }

    private MultiPolygon readMultiPolygon(JsonParser parser) throws IOException {
        List<Polygon> polygons = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY && parser.currentToken() != null) {
            polygons.add(readPolygon(parser));
        }
        return wgs84Factory.createMultiPolygon(polygons.toArray(new Polygon[0]));
    }

    private LinearRing readLinearRing(JsonParser parser) throws IOException {
        List<Coordinate> coords = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY && parser.currentToken() != null) {
            Coordinate c = readCoordinatePair(parser);
            projectionService.assertLonLat(c.x, c.y);
            coords.add(c);
        }
        if (coords.size() < 4) {
            throw new IllegalArgumentException("LinearRing должен содержать минимум 4 точки");
        }
        return wgs84Factory.createLinearRing(coords.toArray(new Coordinate[0]));
    }

    /**
     * AUDIT-12 (Claude, 24.09). Коды object_type/restriction_type из «рабочих систем» (Разъяснение №17) могут
     * отличаться регистром, пробелами, дефисом: "Heat_Network", " road ", "tram-tracks". Раньше такие объекты
     * отбрасывались (неизвестный object_type) или неизвестный restriction_type становился запретом 1 м —
     * дорога превращалась в непроходимую стену. Нормализация: trim, нижний регистр, пробел/дефис → «_».
     */
    static void normalizeCodes(Map<String, Object> properties) {
        for (String key : new String[] {"object_type", "restriction_type"}) {
            Object v = properties.get(key);
            if (v instanceof String) {
                String n = ((String) v).trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
                if (!n.equals(v)) {
                    properties.put(key, n);
                }
            }
        }
    }

    private RawFeature.Kind mapKind(String objectType) {
        switch (objectType) {
            case "oks_connection_point":
                return RawFeature.Kind.OKS_CONNECTION_POINT;
            case "oks_future":
                return RawFeature.Kind.OKS_FUTURE;
            case "oks_existing":
                return RawFeature.Kind.RESTRICTION;
            case "restriction":
                return RawFeature.Kind.RESTRICTION;
            case "heat_network":
                return RawFeature.Kind.HEAT_NETWORK;
            case "heat_chamber":
                return RawFeature.Kind.HEAT_CHAMBER;
            case "source":
                return RawFeature.Kind.SOURCE;
            default:
                return RawFeature.Kind.UNKNOWN;
        }
    }

    private static String stringProp(Map<String, Object> props, String key) {
        Object v = props.get(key);
        return v == null ? null : String.valueOf(v);
    }

    static String stringId(Object rawId) {
        if (rawId == null) {
            return null;
        }
        if (rawId instanceof Number) {
            Number n = (Number) rawId;
            if (n.doubleValue() == n.longValue()) {
                return String.valueOf(n.longValue());
            }
            return String.valueOf(n.doubleValue());
        }
        String s = String.valueOf(rawId).trim();
        return s.isEmpty() ? null : s;
    }

    private static final class ParsedGeometry {
        final Geometry geometry;
        final Coordinate[] lineCoordinates;

        ParsedGeometry(Geometry geometry, Coordinate[] lineCoordinates) {
            this.geometry = geometry;
            this.lineCoordinates = lineCoordinates;
        }

        static ParsedGeometry empty() {
            return new ParsedGeometry(null, null);
        }
    }
}
