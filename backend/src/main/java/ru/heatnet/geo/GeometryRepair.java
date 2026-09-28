package ru.heatnet.geo;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;

import ru.heatnet.ingest.IngestReport;

/**
 * Попытка исправить невалидную геометрию (geometrySnap / buffer(0) / GeometryFixer).
 */
public final class GeometryRepair {

    private GeometryRepair() {
    }

    public static Geometry repair(Geometry geometry, String featureId, IngestReport report) {
        if (geometry == null || geometry.isEmpty()) {
            return null;
        }
        if (isValid(geometry)) {
            return geometry;
        }
        Geometry fixed = tryFix(geometry);
        if (fixed != null && isValid(fixed)) {
            report.warn("GEOMETRY_REPAIRED", featureId,
                    "Геометрия исправлена (buffer(0)/GeometryFixer)");
            return fixed;
        }
        report.error("GEOMETRY_INVALID", featureId, describeInvalid(geometry));
        return null;
    }

    private static Geometry tryFix(Geometry geometry) {
        try {
            Geometry buffered = geometry.buffer(0);
            if (buffered != null && !buffered.isEmpty() && isValid(buffered)) {
                return buffered;
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        try {
            Geometry fixed = GeometryFixer.fix(geometry);
            if (fixed != null && !fixed.isEmpty()) {
                return fixed;
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return null;
    }

    private static boolean isValid(Geometry geometry) {
        return new IsValidOp(geometry).isValid();
    }

    private static String describeInvalid(Geometry geometry) {
        TopologyValidationError err = new IsValidOp(geometry).getValidationError();
        if (err == null) {
            return "Невалидная геометрия";
        }
        return "Невалидная геометрия: " + err.getMessage();
    }
}
