package ru.heatnet.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingObjectType;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.calc.reference.RulesConfig;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.routing.RouteResult;
import ru.heatnet.routing.RoutingPipeline;

/**
 * Подбор врезки и маршрута: несколько кандидатов, учёт занятости камер в текущем плане.
 */
final class TieInRouter {

    private final ReferenceData reference;
    private final ProjectionService projection;
    private final RoutingPipeline routingPipeline;
    private final NetworkTreeBuilder treeBuilder;
    private final GeometryFactory gf;

    TieInRouter(ReferenceData reference,
                ProjectionService projection,
                RoutingPipeline routingPipeline,
                NetworkTreeBuilder treeBuilder) {
        this.reference = reference;
        this.projection = projection;
        this.routingPipeline = routingPipeline;
        this.treeBuilder = treeBuilder;
        this.gf = projection.utmFactory();
    }

    BuiltNetworkTree routeSingle(IngestResult ingest,
                                 ExistingNetwork existing,
                                 ExistingNetworkGeometry geometry,
                                 OksConnectionPoint oks,
                                 Map<String, Integer> extraChamberLoad) {
        return routeSingle(ingest, existing, geometry, oks, extraChamberLoad, null);
    }

    BuiltNetworkTree routeSingle(IngestResult ingest,
                                 ExistingNetwork existing,
                                 ExistingNetworkGeometry geometry,
                                 OksConnectionPoint oks,
                                 Map<String, Integer> extraChamberLoad,
                                 String skipObjectId) {
        Point oksUtm = oksPoint(ingest, oks.getId());
        int dnHint = reference.getDiameters().minFor(oks.getFlowTph())
                .orElse(reference.getDiameters().byIndex(0)).getDn();
        RulesConfig rules = reference.getRules();
        int maxAttempts = rules.getRoutingTieInAttempts();
        List<TieInCandidate> candidates = TieInCandidates.findNear(
                existing, geometry, rules, oksUtm.getCoordinate(), dnHint);
        List<TieInCandidate> toTry = pickAttempts(candidates, maxAttempts, skipObjectId);
        // AUDIT-24.09 (Claude): ближайшая точка трубы часто лежит в зоне отступа зданий (сеть идёт вдоль
        // корпусов) — из неё новая линия не может выйти, не нарушив отступ, и строгий маршрутизатор ввода
        // её отвергает. Раньше пробовались только 1–2 ближайшие точки, и запасной планировщик оставлял ОКС
        // без подключения. Теперь добавляются свободные точки вдоль ближайших труб.
        toTry = new ArrayList<>(toTry);
        toTry.addAll(freeAlongPipes(ingest, existing, geometry, candidates, oksUtm.getCoordinate(), dnHint,
                skipObjectId, toTry));
        for (TieInCandidate candidate : toTry) {
            if (!chamberAvailable(existing, geometry, candidate, extraChamberLoad, rules)) {
                continue;
            }
            if (!exitFree(ingest, candidate.getLocationUtm(), dnHint)) {
                continue;
            }
            Point tieUtm = gf.createPoint(candidate.getLocationUtm());
            RouteResult route = routingPipeline.findLeafRoute(ingest, tieUtm, oksUtm, oks.getFlowTph());
            if (route.isFound()) {
                try {
                    BuiltNetworkTree built = treeBuilder.buildSingle(candidate.getTieIn(), route, oks);
                    rememberChamber(candidate.getTieIn(), extraChamberLoad);
                    return built;
                } catch (RuntimeException ignored) {
                    // следующий кандидат
                }
            }
        }
        return null;
    }

    private static final double[] ALONG_OFFSETS_M = {20, -20, 40, -40, 60, -60, 90, -90, 130, -130};
    private static final int MAX_FREE_EXTRA = 3;

    /** Свободные точки вдоль труб ближайших кандидатов: из них есть выход без нарушения отступов. */
    private List<TieInCandidate> freeAlongPipes(IngestResult ingest, ExistingNetwork existing,
                                                ExistingNetworkGeometry geometry, List<TieInCandidate> candidates,
                                                Coordinate target, int dnHint, String skipObjectId,
                                                List<TieInCandidate> already) {
        List<TieInCandidate> out = new ArrayList<>();
        int pipes = 0;
        for (TieInCandidate c : candidates) {
            if (out.size() >= MAX_FREE_EXTRA || pipes >= 2) {
                break;
            }
            if (c.getKind() != TieInCandidate.Kind.EXISTING_PIPE || c.getTieIn().getDistanceFromUpstreamEndM() == null
                    || (skipObjectId != null && skipObjectId.equals(c.getTieIn().getExistingObjectId()))) {
                continue;
            }
            pipes++;
            String pipeId = c.getTieIn().getExistingObjectId();
            double base = c.getTieIn().getDistanceFromUpstreamEndM();
            double length;
            try {
                length = geometry.segmentLine(pipeId).getLength();
            } catch (RuntimeException ex) {
                continue;
            }
            List<TieInCandidate> local = new ArrayList<>();
            for (double off : ALONG_OFFSETS_M) {
                double d = base + off;
                if (d < 2.0 || d > length - 2.0) {
                    continue;
                }
                TieInCandidate alt = TieInCandidates.alongPipe(existing, geometry, reference.getRules(), pipeId, d,
                        target, dnHint);
                if (alt == null || tooClose(already, alt, 10.0) || tooClose(out, alt, 10.0)
                        || tooClose(local, alt, 10.0)) {
                    continue;
                }
                if (exitFree(ingest, alt.getLocationUtm(), dnHint)) {
                    local.add(alt);
                }
            }
            local.sort(java.util.Comparator.comparingDouble(t -> t.getLocationUtm().distance(target)));
            for (TieInCandidate t : local) {
                if (out.size() < MAX_FREE_EXTRA) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** Из точки есть хотя бы одно свободное направление (5 см) при полном наборе ограничений. */
    private boolean exitFree(IngestResult ingest, Coordinate at, int dn) {
        ru.heatnet.rules.SpatialConstraintEngine engine =
                routingPipelineEngine(ingest, dn);
        if (engine == null) {
            return true;
        }
        for (int k = 0; k < 8; k++) {
            double ang = k * Math.PI / 4.0;
            org.locationtech.jts.geom.LineString probe = gf.createLineString(new Coordinate[] {
                    new Coordinate(at), new Coordinate(at.x + Math.cos(ang) * 0.05, at.y + Math.sin(ang) * 0.05)});
            try {
                if (!engine.isSegmentBlocked(probe, dn)) {
                    return true;
                }
            } catch (RuntimeException ex) {
                return true;
            }
        }
        return false;
    }

    private ru.heatnet.rules.SpatialConstraintEngine routingPipelineEngine(IngestResult ingest, int dn) {
        try {
            return routingPipeline.bundle(ingest, dn).getEngine();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Сначала ближайшая врезка, затем ближайшая камера (если это другая точка), затем остальные.
     */
    static List<TieInCandidate> pickAttempts(List<TieInCandidate> candidates, int maxAttempts,
                                             String skipObjectId) {
        List<TieInCandidate> out = new ArrayList<>();
        if (candidates == null || candidates.isEmpty() || maxAttempts <= 0) {
            return out;
        }
        double minSepM = 15.0;
        for (TieInCandidate c : candidates) {
            if (skipObjectId != null && skipObjectId.equals(c.getTieIn().getExistingObjectId())) {
                continue;
            }
            out.add(c);
            break;
        }
        for (TieInCandidate c : candidates) {
            if (out.size() >= maxAttempts) {
                break;
            }
            if (c.getKind() != TieInCandidate.Kind.EXISTING_CHAMBER) {
                continue;
            }
            if (skipObjectId != null && skipObjectId.equals(c.getTieIn().getExistingObjectId())) {
                continue;
            }
            if (tooClose(out, c, minSepM)) {
                continue;
            }
            out.add(c);
            break;
        }
        for (TieInCandidate c : candidates) {
            if (out.size() >= maxAttempts) {
                break;
            }
            if (skipObjectId != null && skipObjectId.equals(c.getTieIn().getExistingObjectId())) {
                continue;
            }
            if (tooClose(out, c, minSepM)) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    private static boolean tooClose(List<TieInCandidate> already, TieInCandidate candidate, double minSepM) {
        Coordinate loc = candidate.getLocationUtm();
        for (TieInCandidate prev : already) {
            if (prev.getLocationUtm().distance(loc) < minSepM) {
                return true;
            }
        }
        return false;
    }

    /**
     * Общая врезка на группу: кто дошёл — в дерево, остальные возвращаются как leftover.
     */
    JointPartial routeJointPartial(IngestResult ingest,
                                   ExistingNetwork existing,
                                   ExistingNetworkGeometry geometry,
                                   List<OksConnectionPoint> group,
                                   Map<String, Integer> extraChamberLoad) {
        if (group.size() < 2) {
            return JointPartial.none(group);
        }
        Coordinate centroid = centroidOf(group, ingest);
        double totalFlow = sumFlow(group);
        int dnHint = reference.getDiameters().minFor(totalFlow)
                .orElse(reference.getDiameters().byIndex(0)).getDn();
        RulesConfig rules = reference.getRules();
        TieInCandidate shared = firstUsable(existing, geometry, extraChamberLoad, rules, centroid, dnHint);
        if (shared == null) {
            return JointPartial.none(group);
        }
        Point tieUtm = gf.createPoint(shared.getLocationUtm());
        List<NetworkTreeBuilder.OksRoute> found = new ArrayList<>();
        List<OksConnectionPoint> leftover = new ArrayList<>();
        for (OksConnectionPoint oks : group) {
            Point oksUtm = oksPoint(ingest, oks.getId());
            RouteResult route = routingPipeline.findLeafRoute(ingest, tieUtm, oksUtm, oks.getFlowTph());
            if (route.isFound()) {
                found.add(new NetworkTreeBuilder.OksRoute(oks, route));
            } else {
                leftover.add(oks);
            }
        }
        if (found.size() < 2) {
            List<OksConnectionPoint> all = new ArrayList<>(group);
            return JointPartial.none(all);
        }
        try {
            BuiltNetworkTree merged = treeBuilder.buildMerged(shared.getTieIn(), found);
            List<String> errors = TopologyValidator.validate(
                    java.util.Collections.singletonList(merged.getTree()),
                    rules.getMaxSegmentsPerChamber());
            if (!errors.isEmpty()) {
                return JointPartial.none(new ArrayList<>(group));
            }
            rememberChamber(shared.getTieIn(), extraChamberLoad);
            return new JointPartial(merged, leftover);
        } catch (RuntimeException ex) {
            return JointPartial.none(new ArrayList<>(group));
        }
    }

    private TieInCandidate firstUsable(ExistingNetwork existing,
                                       ExistingNetworkGeometry geometry,
                                       Map<String, Integer> extraChamberLoad,
                                       RulesConfig rules,
                                       Coordinate centroid,
                                       int dnHint) {
        for (TieInCandidate candidate : TieInCandidates.findNear(existing, geometry, rules, centroid, dnHint)) {
            if (chamberAvailable(existing, geometry, candidate, extraChamberLoad, rules)) {
                return candidate;
            }
        }
        return null;
    }

    Point oksPoint(IngestResult ingest, String oksId) {
        ru.heatnet.ingest.RawFeature f = ingest.oksFeature(oksId);
        if (f == null || f.getGeometryWgs84() == null) {
            throw new NetworkException("oks_connection_point " + oksId + " не найден");
        }
        Coordinate c = f.getGeometryWgs84().getCoordinate();
        return projection.pointToUtm(c.x, c.y);
    }

    Coordinate centroidOf(List<OksConnectionPoint> oksPoints, IngestResult ingest) {
        double sx = 0;
        double sy = 0;
        int n = 0;
        for (OksConnectionPoint oks : oksPoints) {
            Point p = oksPoint(ingest, oks.getId());
            sx += p.getX();
            sy += p.getY();
            n++;
        }
        return n == 0 ? null : new Coordinate(sx / n, sy / n);
    }

    static double sumFlow(List<OksConnectionPoint> oksPoints) {
        double sum = 0;
        for (OksConnectionPoint oks : oksPoints) {
            sum += oks.getFlowTph();
        }
        return sum;
    }

    static boolean chamberAvailable(ExistingNetwork existing,
                                    TieInCandidate candidate,
                                    Map<String, Integer> extraChamberLoad,
                                    RulesConfig rules) {
        return chamberAvailable(existing, null, candidate, extraChamberLoad, rules);
    }

    /**
     * AUDIT-24.09 (Claude): степень камеры — по геометрии (Разъяснение №12), как в {@link TieInCandidates};
     * раньше здесь бралась только топологическая оценка по upstream-цепочке.
     */
    static boolean chamberAvailable(ExistingNetwork existing,
                                    ExistingNetworkGeometry geometry,
                                    TieInCandidate candidate,
                                    Map<String, Integer> extraChamberLoad,
                                    RulesConfig rules) {
        if (candidate.getKind() != TieInCandidate.Kind.EXISTING_CHAMBER) {
            return true;
        }
        String id = candidate.getTieIn().getExistingObjectId();
        int extra = extraChamberLoad.getOrDefault(id, 0);
        return ChamberDegreeCalculator.degree(existing, geometry, id) + extra + 1
                <= rules.getMaxSegmentsPerChamber();
    }

    static void rememberChamber(TieInPoint tie, Map<String, Integer> extraChamberLoad) {
        if (tie.getExistingObjectType() == ExistingObjectType.HEAT_CHAMBER) {
            extraChamberLoad.merge(tie.getExistingObjectId(), 1, Integer::sum);
        }
    }

    static final class JointPartial {
        private final BuiltNetworkTree merged;
        private final List<OksConnectionPoint> leftover;

        private JointPartial(BuiltNetworkTree merged, List<OksConnectionPoint> leftover) {
            this.merged = merged;
            this.leftover = leftover;
        }

        static JointPartial none(List<OksConnectionPoint> all) {
            return new JointPartial(null, all);
        }

        BuiltNetworkTree getMerged() {
            return merged;
        }

        List<OksConnectionPoint> getLeftover() {
            return leftover;
        }
    }
}
