package ru.heatnet.ingest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;

import ru.heatnet.calc.model.ExistingChamber;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingSegment;
import ru.heatnet.geo.GeoUtils;
import ru.heatnet.geo.ProjectionService;

/**
 * Степень камеры по upstream-индексу и сверка геометрии концов труб с точкой камеры.
 */
@Component
public class ChamberIncidence {

    private final ProjectionService projectionService;

    public ChamberIncidence(ProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    /**
     * AUDIT-24.09 (Claude): радиус, в котором конец трубы вообще относится к камере при сверке. Раньше
     * КАЖДЫЙ конец трубы приписывался ближайшей камере на любом расстоянии, и стыки «труба–труба»
     * в 15–300 м от камер давали ложные CHAMBER_SNAP_GAP (40 предупреждений на конкурсном наборе при
     * фактически точной геометрии). Теперь сверяются только концы в пределах этого радиуса.
     */
    static final double NEAR_CHAMBER_FACTOR = 5.0;

    public void analyze(ExistingNetwork network,
                        List<RawFeature> networkFeatures,
                        double chamberSnapReportToleranceM,
                        IngestReport report) {
        analyze(network, networkFeatures, chamberSnapReportToleranceM, 1.0, report);
    }

    /**
     * @param snapToleranceM допуск snap при построении дерева сети: конец дальше него камерой не подхватывается
     */
    public void analyze(ExistingNetwork network,
                        List<RawFeature> networkFeatures,
                        double chamberSnapReportToleranceM,
                        double snapToleranceM,
                        IngestReport report) {
        double nearRadius = NEAR_CHAMBER_FACTOR * Math.max(snapToleranceM, chamberSnapReportToleranceM);
        Map<String, Coordinate> chamberPoints = new HashMap<>();
        Map<String, List<EndpointHit>> endpointsByChamber = new HashMap<>();

        for (RawFeature feature : networkFeatures) {
            if (feature.getKind() == RawFeature.Kind.HEAT_CHAMBER) {
                chamberPoints.put(feature.getId(), pointUtm(feature));
            }
        }

        // Концы всех труб: стык «труба–труба» (конец совпадает с концом другой трубы) — не зазор у камеры.
        List<Coordinate[]> ends = new ArrayList<>();
        List<String> endIds = new ArrayList<>();
        for (RawFeature feature : networkFeatures) {
            if (feature.getKind() != RawFeature.Kind.HEAT_NETWORK) {
                continue;
            }
            Coordinate[] wgs = feature.getLineCoordinatesWgs84();
            if (wgs == null || wgs.length < 2) {
                continue;
            }
            ends.add(new Coordinate[] {projectionService.toUtm(wgs[0].x, wgs[0].y),
                    projectionService.toUtm(wgs[wgs.length - 1].x, wgs[wgs.length - 1].y)});
            endIds.add(feature.getId());
        }
        for (int i = 0; i < ends.size(); i++) {
            for (int e = 0; e < 2; e++) {
                Coordinate point = ends.get(i)[e];
                // стык двух труб в стороне от камеры (дальше допуска snap) — не зазор у камеры
                double limit = isPipeJoint(point, i, ends, snapToleranceM) ? snapToleranceM : nearRadius;
                attachEndpoint(endpointsByChamber, chamberPoints, endIds.get(i), point, e == 0, limit);
            }
        }

        Map<String, Set<String>> downstreamByChamber = new HashMap<>();
        for (ExistingSegment segment : network.getSegments().values()) {
            String upstream = segment.getUpstreamObjectId();
            if (upstream != null && network.isChamber(upstream)) {
                downstreamByChamber.computeIfAbsent(upstream, k -> new HashSet<>()).add(segment.getId());
            }
        }
        for (ExistingChamber child : network.getChambers().values()) {
            String upstream = child.getUpstreamObjectId();
            if (upstream != null && network.isChamber(upstream) && !upstream.equals(child.getId())) {
                downstreamByChamber.computeIfAbsent(upstream, k -> new HashSet<>()).add(child.getId());
            }
        }

        for (ExistingChamber chamber : network.getChambers().values()) {
            Set<String> downstream = downstreamByChamber.getOrDefault(chamber.getId(), Set.of());
            int degree = downstream.size() + (chamber.getUpstreamObjectId() == null ? 0 : 1);
            report.info("CHAMBER_DEGREE", chamber.getId(),
                    "Степень камеры: " + degree + " (downstream=" + downstream.size()
                            + ", upstream=" + (chamber.getUpstreamObjectId() == null ? "null" : "1") + ")");

            if (downstream.size() >= 3) {
                report.warn("CHAMBER_HUB", chamber.getId(),
                        "Камера с " + downstream.size() + " downstream-участками — проверьте snap-допуск");
            }

            Coordinate chamberUtm = chamberPoints.get(chamber.getId());
            if (chamberUtm == null) {
                continue;
            }
            for (EndpointHit hit : endpointsByChamber.getOrDefault(chamber.getId(), List.of())) {
                double dist = GeoUtils.distanceMeters(chamberUtm, hit.coordinate);
                if (dist > chamberSnapReportToleranceM) {
                    report.warn("CHAMBER_SNAP_GAP", chamber.getId(),
                            "Конец участка " + hit.segmentId + " (" + (hit.start ? "start" : "end")
                                    + ") на расстоянии " + String.format(java.util.Locale.ROOT, "%.2f", dist)
                                    + " м от точки камеры (допуск отчёта "
                                    + chamberSnapReportToleranceM + " м"
                                    + (dist > snapToleranceM ? "; дальше допуска snap " + snapToleranceM
                                    + " м — к камере не присоединён" : "") + ")");
                }
            }
        }
    }

    /** Конец трубы совпадает (в пределах snap) с концом другой трубы — это стык, а не подход к камере. */
    private static boolean isPipeJoint(Coordinate point, int self, List<Coordinate[]> ends, double tol) {
        for (int j = 0; j < ends.size(); j++) {
            if (j == self) {
                continue;
            }
            for (Coordinate other : ends.get(j)) {
                if (GeoUtils.distanceMeters(point, other) <= tol) {
                    return true;
                }
            }
        }
        return false;
    }

    private void attachEndpoint(Map<String, List<EndpointHit>> endpointsByChamber,
                                Map<String, Coordinate> chamberPoints,
                                String segmentId,
                                Coordinate endpointUtm,
                                boolean start,
                                double nearRadiusM) {
        String nearestChamber = null;
        double nearestDist = Double.MAX_VALUE;
        for (Map.Entry<String, Coordinate> e : chamberPoints.entrySet()) {
            double d = GeoUtils.distanceMeters(endpointUtm, e.getValue());
            if (d < nearestDist) {
                nearestDist = d;
                nearestChamber = e.getKey();
            }
        }
        if (nearestChamber == null || nearestDist > nearRadiusM) {
            return;
        }
        endpointsByChamber.computeIfAbsent(nearestChamber, k -> new ArrayList<>())
                .add(new EndpointHit(segmentId, start, endpointUtm));
    }

    private Coordinate pointUtm(RawFeature feature) {
        org.locationtech.jts.geom.Point point = (org.locationtech.jts.geom.Point) feature.getGeometryWgs84();
        return projectionService.toUtm(point.getX(), point.getY());
    }

    private static final class EndpointHit {
        final String segmentId;
        final boolean start;
        final Coordinate coordinate;

        EndpointHit(String segmentId, boolean start, Coordinate coordinate) {
            this.segmentId = segmentId;
            this.start = start;
            this.coordinate = coordinate;
        }
    }
}
