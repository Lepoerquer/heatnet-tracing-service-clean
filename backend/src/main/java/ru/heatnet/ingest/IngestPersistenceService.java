package ru.heatnet.ingest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import ru.heatnet.calc.model.ExistingChamber;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingSegment;
import ru.heatnet.geo.ProjectionService;

/**
 * Сохранение результатов ingest в PostGIS (Flyway V1, GiST-индексы).
 */
@Service
@ConditionalOnBean(JdbcTemplate.class)
public class IngestPersistenceService {

    private final JdbcTemplate jdbc;
    private final ProjectionService projectionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IngestPersistenceService(JdbcTemplate jdbc, ProjectionService projectionService) {
        this.jdbc = jdbc;
        this.projectionService = projectionService;
    }

    @Transactional
    public void persist(UUID sessionId,
                        String originalFilename,
                        String storedPath,
                        IngestResult result,
                        Collection<RawFeature> acceptedFeatures) {
        IngestReport report = result.getReport();
        jdbc.update(
                "INSERT INTO upload_session (id, original_filename, stored_path, ingested_at, report_json,"
                        + " feature_count, warning_count, error_count) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                sessionId,
                originalFilename,
                storedPath,
                Timestamp.from(Instant.now()),
                IngestReportJson.toJson(report),
                report.getTotalFeatures(),
                report.warningCount(),
                report.errorCount());

        persistFeatures(sessionId, acceptedFeatures);

        Map<String, RawFeature> byId = indexById(acceptedFeatures);
        ExistingNetwork network = result.getExistingNetwork();
        for (ExistingSegment segment : network.getSegments().values()) {
            persistSegment(sessionId, segment, byId.get(segment.getId()));
        }
        for (ExistingChamber chamber : network.getChambers().values()) {
            persistChamber(sessionId, chamber, byId.get(chamber.getId()));
        }
        for (RawFeature feature : acceptedFeatures) {
            if (feature.getKind() == RawFeature.Kind.SOURCE) {
                persistSource(sessionId, feature);
            }
        }
    }

    private void persistFeatures(UUID sessionId, Collection<RawFeature> acceptedFeatures) {
        if (acceptedFeatures.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO ingested_feature (session_id, external_id, object_type, restriction_type, properties,"
                + " geom_wgs84, geom_utm, diameter, flow_tph, heat_load, upstream_object_id, reference_only)"
                + " VALUES (?, ?, ?, ?, ?::jsonb,"
                + " CASE WHEN ? IS NULL THEN NULL ELSE ST_SetSRID(ST_GeomFromText(?), 4326) END,"
                + " CASE WHEN ? IS NULL THEN NULL ELSE ST_SetSRID(ST_GeomFromText(?), 32637) END,"
                + " ?, ?, ?, ?, ?)";
        List<Object[]> rows = new ArrayList<>(acceptedFeatures.size());
        for (RawFeature feature : acceptedFeatures) {
            Geometry wgs = feature.getGeometryWgs84();
            Geometry utm = wgs == null ? null : projectToUtm(wgs);
            boolean referenceOnly = feature.getKind() == RawFeature.Kind.OKS_FUTURE;
            rows.add(new Object[] {
                    sessionId,
                    feature.getId(),
                    feature.objectTypeProperty(),
                    feature.restrictionType(),
                    toJson(feature.getProperties()),
                    wgs == null ? null : wgs.toText(),
                    wgs == null ? null : wgs.toText(),
                    utm == null ? null : utm.toText(),
                    utm == null ? null : utm.toText(),
                    intProp(feature, "diameter"),
                    doubleProp(feature, "flow_tph"),
                    doubleProp(feature, "heat_load"),
                    stringProp(feature, "upstream_object_id"),
                    referenceOnly
            });
        }
        jdbc.batchUpdate(sql, rows);
    }

    private void persistSegment(UUID sessionId, ExistingSegment segment, RawFeature raw) {
        LineString lineUtm = null;
        if (raw != null && raw.getLineCoordinatesWgs84() != null) {
            lineUtm = projectionService.lineToUtm(raw.getLineCoordinatesWgs84());
        }
        jdbc.update(
                "INSERT INTO ingested_network_segment (session_id, external_id, diameter, flow_tph,"
                        + " upstream_object_id, length_m, geom_utm)"
                        + " VALUES (?, ?, ?, ?, ?, ?,"
                        + " CASE WHEN ? IS NULL THEN NULL ELSE ST_SetSRID(ST_GeomFromText(?), 32637) END)",
                sessionId,
                segment.getId(),
                segment.getDiameter(),
                segment.getFlowTph(),
                segment.getUpstreamObjectId(),
                segment.getLengthM(),
                lineUtm == null ? null : lineUtm.toText(),
                lineUtm == null ? null : lineUtm.toText());
    }

    private void persistChamber(UUID sessionId, ExistingChamber chamber, RawFeature raw) {
        Point utm = null;
        if (raw != null && raw.getGeometryWgs84() instanceof Point) {
            Point wgs = (Point) raw.getGeometryWgs84();
            utm = projectionService.pointToUtm(wgs.getX(), wgs.getY());
        }
        jdbc.update(
                "INSERT INTO ingested_network_chamber (session_id, external_id, diameter, upstream_object_id, geom_utm)"
                        + " VALUES (?, ?, ?, ?,"
                        + " CASE WHEN ? IS NULL THEN NULL ELSE ST_SetSRID(ST_GeomFromText(?), 32637) END)",
                sessionId,
                chamber.getId(),
                chamber.getDiameter(),
                chamber.getUpstreamObjectId(),
                utm == null ? null : utm.toText(),
                utm == null ? null : utm.toText());
    }

    private void persistSource(UUID sessionId, RawFeature feature) {
        if (feature == null || !(feature.getGeometryWgs84() instanceof Point)) {
            return;
        }
        Point wgs = (Point) feature.getGeometryWgs84();
        jdbc.update(
                "INSERT INTO ingested_source (session_id, external_id, geom_wgs84)"
                        + " VALUES (?, ?, ST_SetSRID(ST_GeomFromText(?), 4326))",
                sessionId,
                feature.getId(),
                wgs.toText());
    }

    public boolean sessionExists(UUID sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM upload_session WHERE id = ?",
                Integer.class,
                sessionId);
        return count != null && count > 0;
    }

    public String loadReportJson(UUID sessionId) {
        return jdbc.queryForObject(
                "SELECT report_json::text FROM upload_session WHERE id = ?",
                String.class,
                sessionId);
    }

    private Geometry projectToUtm(Geometry wgs) {
        if (wgs instanceof Point) {
            Point p = (Point) wgs;
            return projectionService.pointToUtm(p.getX(), p.getY());
        }
        if (wgs instanceof LineString) {
            return projectionService.lineToUtm(wgs.getCoordinates());
        }
        return wgs;
    }

    private static Map<String, RawFeature> indexById(Collection<RawFeature> features) {
        Map<String, RawFeature> byId = new HashMap<>();
        for (RawFeature feature : features) {
            byId.put(feature.getId(), feature);
        }
        return byId;
    }

    private String toJson(Map<String, Object> props) {
        try {
            return objectMapper.writeValueAsString(props);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static Integer intProp(RawFeature feature, String key) {
        Object v = feature.getProperties().get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return null;
    }

    private static Double doubleProp(RawFeature feature, String key) {
        Object v = feature.getProperties().get(key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        return null;
    }

    private static String stringProp(RawFeature feature, String key) {
        Object v = feature.getProperties().get(key);
        return v == null ? null : String.valueOf(v);
    }
}
