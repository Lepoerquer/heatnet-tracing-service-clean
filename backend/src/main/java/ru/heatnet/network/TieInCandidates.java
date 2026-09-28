package ru.heatnet.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;

import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.calc.reference.RulesConfig;
import ru.heatnet.cost.ChamberCostScale;
import ru.heatnet.geo.GeoUtils;

/**
 * Кандидаты врезки: существующие камеры (≤10 м от точки присоединения, degree≤4 → 5 млн)
 * и проекции на трубы (только стоимость новой камеры по шкале — §3.2, без доплаты 5 млн).
 * §2.4 / Разъяснения №11: 10 м измеряется от выбранной точки на сети, не от ОКС.
 */
public final class TieInCandidates {

    private static final double PIPE_ENDPOINT_MARGIN_M = 2.0;

    private TieInCandidates() {
    }

    public static List<TieInCandidate> findNear(ExistingNetwork network,
                                              ExistingNetworkGeometry geometry,
                                              RulesConfig rules,
                                              Coordinate referenceUtm,
                                              int minChamberDnHint) {
        if (referenceUtm == null) {
            return Collections.emptyList();
        }
        ChamberCostScale scale = new ChamberCostScale(rules);
        long tieInRub = scale.tieInCost();
        long minNewChamberRub = scale.costFor(Math.max(50, minChamberDnHint));
        long pipeEntryCost = minNewChamberRub;

        List<TieInCandidate> result = new ArrayList<>();
        Set<String> chamberIds = new LinkedHashSet<>();
        double chamberRadius = rules.getTieInChamberRadiusM();
        int maxDegree = rules.getMaxSegmentsPerChamber();

        for (String chamberId : geometry.getChamberPoints().keySet()) {
            if (!network.isChamber(chamberId)) {
                continue;
            }
            Coordinate ch = geometry.chamberPoint(chamberId);
            double dist = GeoUtils.distanceMeters(referenceUtm, ch);
            if (!ChamberDegreeCalculator.canAddTieIn(network, geometry, chamberId, maxDegree, 1)) {
                continue;
            }
            boolean withinRadius = dist <= chamberRadius + rules.getGeometryToleranceM();
            if (!withinRadius) {
                continue;
            }
            chamberIds.add(chamberId);
            String tieId = "tie_c_" + chamberId + "_" + coordSuffix(ch) + "_" + coordSuffix(referenceUtm);
            TieInPoint tie = TieInPoint.intoChamber(tieId, chamberId);
            result.add(new TieInCandidate(tieId, TieInCandidate.Kind.EXISTING_CHAMBER, tie, ch, dist,
                    tieInRub, true));
        }

        for (String segmentId : geometry.getSegmentLines().keySet()) {
            if (!network.isSegment(segmentId)) {
                continue;
            }
            ExistingNetworkGeometry.PipeProjection proj =
                    geometry.projectOnSegment(segmentId, referenceUtm, PIPE_ENDPOINT_MARGIN_M);
            if (proj == null) {
                continue;
            }
            String promoted = chamberWithinRadius(network, geometry, rules, proj.getPoint(), maxDegree);
            if (promoted != null) {
                if (chamberIds.add(promoted)) {
                    Coordinate ch = geometry.chamberPoint(promoted);
                    String tieId = "tie_c_" + promoted + "_" + coordSuffix(ch) + "_" + coordSuffix(referenceUtm);
                    TieInPoint tie = TieInPoint.intoChamber(tieId, promoted);
                    result.add(new TieInCandidate(tieId, TieInCandidate.Kind.EXISTING_CHAMBER, tie, ch,
                            GeoUtils.distanceMeters(referenceUtm, ch), tieInRub, true));
                }
                continue;
            }
            String tieId = "tie_p_" + segmentId + "_" + Math.round(proj.getDistanceFromUpstreamEndM() * 100.0)
                    + "_" + coordSuffix(proj.getPoint()) + "_" + coordSuffix(referenceUtm);
            String newChamberId = "nch_" + tieId;
            TieInPoint tie = TieInPoint.intoPipe(tieId, segmentId, proj.getDistanceFromUpstreamEndM(), newChamberId);
            result.add(new TieInCandidate(tieId, TieInCandidate.Kind.EXISTING_PIPE, tie, proj.getPoint(),
                    proj.getOffsetFromAxisM(), pipeEntryCost, false));
        }

        result.sort(ranking());
        return result;
    }

    /**
     * AUDIT-24.09 (Claude): кандидат врезки в заданной точке трубы (по расстоянию от upstream-конца) с тем же
     * правилом 10 м, что и в {@link #findNear}: если рядом камера, к которой можно примкнуть, — врезка в неё.
     */
    public static TieInCandidate alongPipe(ExistingNetwork network, ExistingNetworkGeometry geometry, RulesConfig rules,
                                           String segmentId, double distanceFromUpstreamM, Coordinate referenceUtm,
                                           int minChamberDnHint) {
        Coordinate at = geometry.pointAtDistanceFromUpstream(segmentId, distanceFromUpstreamM);
        if (at == null) {
            return null;
        }
        ChamberCostScale scale = new ChamberCostScale(rules);
        String promoted = chamberWithinRadius(network, geometry, rules, at, rules.getMaxSegmentsPerChamber());
        if (promoted != null) {
            Coordinate ch = geometry.chamberPoint(promoted);
            String tieId = "tie_c_" + promoted + "_" + coordSuffix(ch) + "_" + coordSuffix(referenceUtm);
            return new TieInCandidate(tieId, TieInCandidate.Kind.EXISTING_CHAMBER,
                    TieInPoint.intoChamber(tieId, promoted), ch, GeoUtils.distanceMeters(referenceUtm, ch),
                    scale.tieInCost(), true);
        }
        String tieId = "tie_p_" + segmentId + "_" + Math.round(distanceFromUpstreamM * 100.0) + "_"
                + coordSuffix(at) + "_" + coordSuffix(referenceUtm);
        TieInPoint tie = TieInPoint.intoPipe(tieId, segmentId, distanceFromUpstreamM, "nch_" + tieId);
        return new TieInCandidate(tieId, TieInCandidate.Kind.EXISTING_PIPE, tie, at,
                GeoUtils.distanceMeters(referenceUtm, at), scale.costFor(Math.max(50, minChamberDnHint)), false);
    }

    static Comparator<TieInCandidate> ranking() {
        return Comparator.comparingDouble(TieInCandidate::getReferenceDistanceM)
                .thenComparing(Comparator.naturalOrder());
    }

    /** Лучший кандидат или null. */
    public static TieInCandidate bestNear(ExistingNetwork network,
                                        ExistingNetworkGeometry geometry,
                                        RulesConfig rules,
                                        Coordinate referenceUtm,
                                        int minChamberDnHint) {
        List<TieInCandidate> list = findNear(network, geometry, rules, referenceUtm, minChamberDnHint);
        return list.isEmpty() ? null : list.get(0);
    }

    private static String chamberWithinRadius(ExistingNetwork network,
                                              ExistingNetworkGeometry geometry,
                                              RulesConfig rules,
                                              Coordinate tiePoint,
                                              int maxDegree) {
        String bestId = null;
        double bestDist = Double.MAX_VALUE;
        double radius = rules.getTieInChamberRadiusM() + rules.getGeometryToleranceM();
        for (String chamberId : geometry.getChamberPoints().keySet()) {
            if (!network.isChamber(chamberId)) {
                continue;
            }
            if (!ChamberDegreeCalculator.canAddTieIn(network, geometry, chamberId, maxDegree, 1)) {
                continue;
            }
            Coordinate ch = geometry.chamberPoint(chamberId);
            double dist = GeoUtils.distanceMeters(tiePoint, ch);
            if (dist <= radius && dist < bestDist) {
                bestDist = dist;
                bestId = chamberId;
            }
        }
        return bestId;
    }

    private static String coordSuffix(Coordinate c) {
        return Math.round(c.x * 100.0) + "_" + Math.round(c.y * 100.0);
    }

    /** Альтернативная врезка — второй по рангу кандидат (для M7). */
    public static TieInCandidate secondBest(ExistingNetwork network,
                                          ExistingNetworkGeometry geometry,
                                          RulesConfig rules,
                                          Coordinate referenceUtm,
                                          int minChamberDnHint,
                                          String excludeTieInExistingObjectId) {
        for (TieInCandidate c : findNear(network, geometry, rules, referenceUtm, minChamberDnHint)) {
            if (!c.getTieIn().getExistingObjectId().equals(excludeTieInExistingObjectId)) {
                return c;
            }
        }
        return null;
    }
}
