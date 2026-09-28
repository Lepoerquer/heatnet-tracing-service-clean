package ru.heatnet.ingest;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Проверка обязательных атрибутов по типам (табл. 2.2 ТП).
 * Ошибки схемы попадают в отчёт; объект может быть исключён из модели расчёта.
 */
@Component
public class SchemaValidator {

    public ValidationOutcome validate(RawFeature feature, IngestReport report) {
        switch (feature.getKind()) {
            case OKS_CONNECTION_POINT:
                return validateOksConnectionPoint(feature, report);
            case OKS_FUTURE:
                report.warn("OKS_FUTURE_REFERENCE", feature.getId(),
                        "oks_future: heat_load справочный, в расчёт не передаётся");
                return validateOksFuture(feature, report);
            case RESTRICTION:
                return validateRestriction(feature, report);
            case HEAT_NETWORK:
                return validateHeatNetwork(feature, report);
            case HEAT_CHAMBER:
                return validateHeatChamber(feature, report);
            case SOURCE:
                return ValidationOutcome.accept(feature);
            case UNKNOWN:
                report.warn("UNKNOWN_OBJECT_TYPE", feature.getId(),
                        "Неизвестный object_type: " + feature.objectTypeProperty());
                return ValidationOutcome.skip();
            default:
                return ValidationOutcome.skip();
        }
    }

    private ValidationOutcome validateOksFuture(RawFeature feature, IngestReport report) {
        if (numericProp(feature, "flow_tph") == null) {
            report.warn("OKS_FUTURE_FLOW", feature.getId(),
                    "oks_future: flow_tph отсутствует — только справочное хранение");
        }
        if (feature.getGeometryWgs84() == null) {
            report.error("OKS_FUTURE_GEOMETRY", feature.getId(), "oks_future: отсутствует геометрия");
            return ValidationOutcome.reject();
        }
        return ValidationOutcome.accept(feature);
    }

    private ValidationOutcome validateOksConnectionPoint(RawFeature feature, IngestReport report) {
        Double flow = numericProp(feature, "flow_tph");
        if (flow == null || !(flow > 0)) {
            report.error("OKS_FLOW_REQUIRED", feature.getId(),
                    "oks_connection_point: обязателен положительный flow_tph");
            return ValidationOutcome.reject();
        }
        if (feature.getGeometryWgs84() == null || !(feature.getGeometryWgs84() instanceof org.locationtech.jts.geom.Point)) {
            report.error("OKS_GEOMETRY", feature.getId(), "oks_connection_point: ожидается Point");
            return ValidationOutcome.reject();
        }
        return ValidationOutcome.accept(feature);
    }

    private ValidationOutcome validateRestriction(RawFeature feature, IngestReport report) {
        String restrictionType = feature.restrictionType();
        if (restrictionType == null || restrictionType.isBlank()) {
            report.error("RESTRICTION_TYPE", feature.getId(), "restriction: обязателен restriction_type");
            return ValidationOutcome.reject();
        }
        if (feature.getGeometryWgs84() == null) {
            report.error("RESTRICTION_GEOMETRY", feature.getId(), "restriction: отсутствует геометрия");
            return ValidationOutcome.reject();
        }
        return ValidationOutcome.accept(feature);
    }

    private ValidationOutcome validateHeatNetwork(RawFeature feature, IngestReport report) {
        Integer diameter = intProp(feature, "diameter");
        if (diameter == null || diameter <= 0) {
            report.error("NET_DIAMETER", feature.getId(), "heat_network: обязателен diameter > 0");
            return ValidationOutcome.reject();
        }
        if (numericProp(feature, "flow_tph") == null) {
            // AUDIT-24.09 (Claude): §1.1/§2.4 приложения — flow_tph существующей сети не входит в обязательные
            // атрибуты, «текущий расход ... существующей сети в обязательном расчёте не определяется».
            // Было предупреждение на каждый участок (29 на конкурсном наборе) — теперь справка.
            report.info("NET_FLOW_MISSING", feature.getId(),
                    "heat_network: flow_tph не задан (не обязателен по §1.1 приложения; в расчёте не используется)");
        }
        if (stringProp(feature, "upstream_object_id") == null) {
            report.info("NET_UPSTREAM_MISSING", feature.getId(),
                    "heat_network: upstream_object_id отсутствует — дерево будет восстановлено по геометрии");
        }
        if (feature.getLineCoordinatesWgs84() == null || feature.getLineCoordinatesWgs84().length < 2) {
            report.error("NET_GEOMETRY", feature.getId(), "heat_network: ожидается LineString ≥ 2 точек");
            return ValidationOutcome.reject();
        }
        return ValidationOutcome.accept(feature);
    }

    private ValidationOutcome validateHeatChamber(RawFeature feature, IngestReport report) {
        if (intProp(feature, "diameter") == null) {
            // AUDIT-24.09 (Claude): для heat_chamber обязательны только id и object_type (§1.1 приложения).
            report.info("CHAMBER_DIAMETER_MISSING", feature.getId(),
                    "heat_chamber: diameter не задан (не обязателен по §1.1) — выводится из примыкающих труб");
        }
        if (stringProp(feature, "upstream_object_id") == null) {
            report.info("CHAMBER_UPSTREAM_MISSING", feature.getId(),
                    "heat_chamber: upstream_object_id отсутствует — будет восстановлен по геометрии");
        }
        if (feature.getGeometryWgs84() == null || !(feature.getGeometryWgs84() instanceof org.locationtech.jts.geom.Point)) {
            report.error("CHAMBER_GEOMETRY", feature.getId(), "heat_chamber: ожидается Point");
            return ValidationOutcome.reject();
        }
        return ValidationOutcome.accept(feature);
    }

    private static String stringProp(RawFeature feature, String key) {
        Object v = feature.getProperties().get(key);
        if ( v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intProp(RawFeature feature, String key) {
        Object v = feature.getProperties().get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return ru.heatnet.ingest.RawFeature.parseIntLenient(v); // AUDIT-12 (Claude, 24.09): "500.0", " 500 "
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double numericProp(RawFeature feature, String key) {
        Object v = feature.getProperties().get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return ru.heatnet.ingest.RawFeature.parseDoubleLenient(v); // AUDIT-12 (Claude, 24.09): "12,5"
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static final class ValidationOutcome {
        private final boolean accepted;
        private final RawFeature feature;

        private ValidationOutcome(boolean accepted, RawFeature feature) {
            this.accepted = accepted;
            this.feature = feature;
        }

        static ValidationOutcome accept(RawFeature feature) {
            return new ValidationOutcome(true, feature);
        }

        static ValidationOutcome reject() {
            return new ValidationOutcome(false, null);
        }

        static ValidationOutcome skip() {
            return new ValidationOutcome(false, null);
        }

        public boolean isAccepted() {
            return accepted;
        }

        public RawFeature getFeature() {
            return feature;
        }
    }
}
