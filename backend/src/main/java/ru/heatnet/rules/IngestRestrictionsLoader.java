package ru.heatnet.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Component;

import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.calc.reference.RestrictionRule;
import ru.heatnet.calc.reference.RulesConfig;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.RawFeature;
import ru.heatnet.rules.model.RestrictionFeature;

/** Загрузка ограничений из результата M1 в метрическую модель M2. */
@Component
public class IngestRestrictionsLoader {

    private final ProjectionService projectionService;

    public IngestRestrictionsLoader(ProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    public List<RestrictionFeature> load(IngestResult ingest, ReferenceData referenceData) {
        RulesConfig rules = referenceData.getRules();
        Map<String, Geometry> geometries = ingest.getRestrictionsWgs84();
        List<RestrictionFeature> result = new ArrayList<>();

        for (RawFeature feature : ingest.getAcceptedFeatures()) {
            if (feature.getKind() != RawFeature.Kind.RESTRICTION) {
                continue;
            }
            String restrictionType = feature.restrictionType();
            String rulesKey;
            try {
                rulesKey = RestrictionTypeMapper.toRulesKey(restrictionType);
            } catch (RulesException ex) {
                if (ingest.getReport() != null) {
                    ingest.getReport().warn("UNKNOWN_RESTRICTION_TYPE", feature.getId(),
                            ex.getMessage() + " — объект пропущен");
                }
                continue;
            }
            RestrictionRule rule;
            if (rules.hasRestriction(rulesKey)) {
                rule = rules.restriction(rulesKey);
            } else {
                if (ingest.getReport() != null) {
                    ingest.getReport().warn("UNKNOWN_RESTRICTION_TYPE", feature.getId(),
                            "Неизвестный restriction_type='" + restrictionType
                                    + "' — консервативный запрет 1,0 м, расчёт продолжается");
                }
                rule = new RestrictionRule(rulesKey, RestrictionRule.Kind.PROHIBITED, 1.0,
                        null, null, null, RestrictionRule.ZoneKind.NONE, 0.0, true);
            }
            Geometry wgs = geometries.get(feature.getId());
            if (wgs == null) {
                wgs = feature.getGeometryWgs84();
            }
            Geometry utm = UtmGeometryMapper.toUtm(wgs, projectionService, projectionService.utmFactory());
            result.add(new RestrictionFeature(feature.getId(), rulesKey, rule, utm));
        }
        appendExistingHeatNetwork(ingest, rules, result);
        return result;
    }

    /** Полуширина «полосы», которой заменяется линейная дорога/трамвай, м. */
    static final double LINEAR_BARRIER_STRIP_HALF_WIDTH_M = 0.05;

    /**
     * AUDIT-12 (Claude, 24.09). §1.1 приложения: restriction может быть LineString/MultiLineString. Для
     * дороги и трамвайных путей (спецпроход «полигон + 3 м», угол ≥ 45°, отступ 1,5 м) весь движок M2
     * работает с полигоном: угол к границе, зона спецпрохода, кольцо отступа. Линия раньше шла в движок
     * как есть: пересечение считалось по полигонам геометрии (у линии их нет) — любое звено в полосе
     * отступа признавалось нарушением, и линейная дорога становилась НЕПРОХОДИМОЙ стеной (ОКС за ней —
     * без подключения со штрафом). Линия заменяется узкой полосой ±5 см с плоскими торцами: стороны полосы
     * параллельны линии, поэтому угол пересечения «относительно линии ограничения» (Разъяснение №6)
     * сохраняется, зона спецпрохода — линия ± 3 м, отступ — от линии. Применяется при сборке индекса
     * ({@link RestrictionIndexBuilder}), то есть для любого источника ограничений.
     */
    public static Geometry linearBarrierAsStrip(RestrictionRule rule, Geometry utm) {
        if (rule == null || utm == null || utm.isEmpty() || rule.isProhibited()
                || rule.getZoneKind() != RestrictionRule.ZoneKind.POLYGON_MARGIN || utm.getDimension() != 1) {
            return utm;
        }
        org.locationtech.jts.operation.buffer.BufferParameters params =
                new org.locationtech.jts.operation.buffer.BufferParameters();
        params.setEndCapStyle(org.locationtech.jts.operation.buffer.BufferParameters.CAP_FLAT);
        params.setJoinStyle(org.locationtech.jts.operation.buffer.BufferParameters.JOIN_MITRE);
        params.setMitreLimit(2.0);
        try {
            Geometry strip = org.locationtech.jts.operation.buffer.BufferOp.bufferOp(utm,
                    LINEAR_BARRIER_STRIP_HALF_WIDTH_M, params);
            return strip == null || strip.isEmpty() ? utm : strip;
        } catch (RuntimeException ex) {
            return utm;
        }
    }

    /**
     * Табл. 2 и Разъяснения №10: существующая тепловая сеть — линейное ограничение
     * «пересечение без врезки = спецпроход K=1,05, отступ 1,0 м между габаритами»;
     * габарит самой сети — по её ДУ из табл. 1.
     */
    private void appendExistingHeatNetwork(IngestResult ingest, RulesConfig rules, List<RestrictionFeature> result) {
        if (!rules.hasRestriction(EXISTING_HEAT_NETWORK_KEY)) {
            return;
        }
        RestrictionRule rule = rules.restriction(EXISTING_HEAT_NETWORK_KEY);
        for (RawFeature feature : ingest.getAcceptedFeatures()) {
            if (feature.getKind() != RawFeature.Kind.HEAT_NETWORK || feature.getGeometryWgs84() == null) {
                continue;
            }
            Geometry utm = UtmGeometryMapper.toUtm(feature.getGeometryWgs84(), projectionService,
                    projectionService.utmFactory());
            if (utm == null || utm.isEmpty() || utm.getDimension() != 1) {
                continue;
            }
            result.add(new RestrictionFeature(EXISTING_HEAT_NETWORK_ID_PREFIX + feature.getId(),
                    EXISTING_HEAT_NETWORK_KEY, rule, utm, existingDiameter(ingest, feature)));
        }
    }

    private static Integer existingDiameter(IngestResult ingest, RawFeature feature) {
        if (ingest.getExistingNetwork() != null && ingest.getExistingNetwork().isSegment(feature.getId())) {
            int dn = ingest.getExistingNetwork().segment(feature.getId()).getDiameter();
            if (dn > 0) {
                return dn;
            }
        }
        Object raw = feature.getProperties().get("diameter");
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        try {
            return raw == null ? null : Integer.valueOf(Double.valueOf(String.valueOf(raw)).intValue());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static final String EXISTING_HEAT_NETWORK_KEY = "heat_network";
    public static final String EXISTING_HEAT_NETWORK_ID_PREFIX = "existing_hn:";
}
