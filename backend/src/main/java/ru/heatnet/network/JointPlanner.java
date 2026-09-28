package ru.heatnet.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Point;

import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.cost.UnconnectedOks;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.routing.RoutingPipeline;

/**
 * Синтез совместного/раздельного подключения ОКС.
 * Совместно — группы ОКС с одной ближайшей точкой сети (не больше 6);
 * один NOT_FOUND не обнуляет остальных. Ненайденные пробуют до tie_in_attempts врезок.
 */
public final class JointPlanner {

    private final ReferenceData reference;
    private final NetworkTreeBuilder treeBuilder;
    private final TieInRouter tieInRouter;

    public JointPlanner(ReferenceData reference,
                        ProjectionService projection,
                        RoutingPipeline routingPipeline) {
        this.reference = reference;
        this.treeBuilder = new NetworkTreeBuilder(projection.utmFactory(),
                reference.getRules().getGeometryToleranceM(),
                reference.getRules().getMaxSegmentsPerChamber(),
                reference.getRules().getRoutingClusterPrefixM());
        this.tieInRouter = new TieInRouter(reference, projection, routingPipeline, treeBuilder);
    }

    public NetworkPlan plan(IngestResult ingest,
                            ExistingNetwork existing,
                            ExistingNetworkGeometry geometry,
                            List<OksConnectionPoint> oksPoints) {
        treeBuilder.resetCounters();
        if (oksPoints == null || oksPoints.isEmpty()) {
            return new NetworkPlan(Collections.<NewNetworkTree>emptyList(),
                    Collections.<UnconnectedOks>emptyList(), false);
        }
        if (oksPoints.size() == 1) {
            return planAllSeparate(ingest, existing, geometry, oksPoints);
        }
        return planClustered(ingest, existing, geometry, oksPoints);
    }

    private static final int MAX_JOINT_GROUP = 6;

    private NetworkPlan planClustered(IngestResult ingest,
                                      ExistingNetwork existing,
                                      ExistingNetworkGeometry geometry,
                                      List<OksConnectionPoint> oksPoints) {
        List<NewNetworkTree> trees = new ArrayList<>();
        List<NetworkTreeLayout> layouts = new ArrayList<>();
        List<UnconnectedOks> unconnected = new ArrayList<>();
        Map<String, Integer> extraChamberLoad = new LinkedHashMap<>();
        boolean anyJoint = false;

        Map<String, List<OksConnectionPoint>> byObject = new LinkedHashMap<>();
        Map<String, Point> utmById = new LinkedHashMap<>();
        List<OksConnectionPoint> noCandidate = new ArrayList<>();
        for (OksConnectionPoint oks : oksPoints) {
            Point oksUtm = tieInRouter.oksPoint(ingest, oks.getId());
            utmById.put(oks.getId(), oksUtm);
            int dnHint = reference.getDiameters().minFor(oks.getFlowTph())
                    .orElse(reference.getDiameters().byIndex(0)).getDn();
            TieInCandidate best = null;
            for (TieInCandidate c : TieInCandidates.findNear(existing, geometry, reference.getRules(),
                    oksUtm.getCoordinate(), dnHint)) {
                if (TieInRouter.chamberAvailable(existing, c, extraChamberLoad, reference.getRules())) {
                    best = c;
                    break;
                }
            }
            if (best == null) {
                noCandidate.add(oks);
            } else {
                String key = best.getTieIn().getExistingObjectId();
                List<OksConnectionPoint> g = byObject.get(key);
                if (g == null) {
                    g = new ArrayList<>();
                    byObject.put(key, g);
                }
                g.add(oks);
            }
        }

        double jointRadius = reference.getRules().getRoutingJointGroupM();
        for (Map.Entry<String, List<OksConnectionPoint>> entry : byObject.entrySet()) {
            for (List<OksConnectionPoint> nearby : clusterByDistance(entry.getValue(), utmById, jointRadius)) {
                for (List<OksConnectionPoint> group : splitGroup(nearby, MAX_JOINT_GROUP)) {
                    List<OksConnectionPoint> leftover = group;
                    if (group.size() >= 2) {
                        TieInRouter.JointPartial partial = tieInRouter.routeJointPartial(
                                ingest, existing, geometry, group, extraChamberLoad);
                        if (partial.getMerged() != null) {
                            trees.add(partial.getMerged().getTree());
                            layouts.add(partial.getMerged().getLayout());
                            anyJoint = true;
                            leftover = partial.getLeftover();
                        }
                    }
                    for (OksConnectionPoint oks : leftover) {
                        BuiltNetworkTree built = tieInRouter.routeSingle(
                                ingest, existing, geometry, oks, extraChamberLoad);
                        if (built == null) {
                            unconnected.add(new UnconnectedOks(oks.getId(), oks.getFlowTph()));
                        } else {
                            trees.add(built.getTree());
                            layouts.add(built.getLayout());
                        }
                    }
                }
            }
        }
        for (OksConnectionPoint oks : noCandidate) {
            BuiltNetworkTree built = tieInRouter.routeSingle(
                    ingest, existing, geometry, oks, extraChamberLoad);
            if (built == null) {
                unconnected.add(new UnconnectedOks(oks.getId(), oks.getFlowTph()));
            } else {
                trees.add(built.getTree());
                layouts.add(built.getLayout());
            }
        }
        return new NetworkPlan(trees, layouts, unconnected, anyJoint);
    }

    private NetworkPlan planAllSeparate(IngestResult ingest,
                                        ExistingNetwork existing,
                                        ExistingNetworkGeometry geometry,
                                        List<OksConnectionPoint> oksPoints) {
        List<NewNetworkTree> trees = new ArrayList<>();
        List<NetworkTreeLayout> layouts = new ArrayList<>();
        List<UnconnectedOks> unconnected = new ArrayList<>();
        Map<String, Integer> extraChamberLoad = new LinkedHashMap<>();
        for (OksConnectionPoint oks : oksPoints) {
            BuiltNetworkTree built = tieInRouter.routeSingle(
                    ingest, existing, geometry, oks, extraChamberLoad);
            if (built == null) {
                unconnected.add(new UnconnectedOks(oks.getId(), oks.getFlowTph()));
            } else {
                trees.add(built.getTree());
                layouts.add(built.getLayout());
            }
        }
        return new NetworkPlan(trees, layouts, unconnected, false);
    }

    static List<List<OksConnectionPoint>> splitGroup(List<OksConnectionPoint> group, int maxSize) {
        if (group == null || group.isEmpty()) {
            return Collections.emptyList();
        }
        if (maxSize < 1 || group.size() <= maxSize) {
            return Collections.singletonList(group);
        }
        List<List<OksConnectionPoint>> chunks = new ArrayList<>();
        for (int i = 0; i < group.size(); i += maxSize) {
            chunks.add(new ArrayList<>(group.subList(i, Math.min(i + maxSize, group.size()))));
        }
        return chunks;
    }

    /**
     * Union-find: ОКС в одном кластере, если попарно не дальше {@code radiusM}.
     */
    static List<List<OksConnectionPoint>> clusterByDistance(List<OksConnectionPoint> oksPoints,
                                                            Map<String, Point> utmById,
                                                            double radiusM) {
        int n = oksPoints.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        double r2 = radiusM * radiusM;
        for (int i = 0; i < n; i++) {
            Point a = utmById.get(oksPoints.get(i).getId());
            if (a == null) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                Point b = utmById.get(oksPoints.get(j).getId());
                if (b == null) {
                    continue;
                }
                double dx = a.getX() - b.getX();
                double dy = a.getY() - b.getY();
                if (dx * dx + dy * dy <= r2) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<OksConnectionPoint>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            List<OksConnectionPoint> g = groups.get(root);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(root, g);
            }
            g.add(oksPoints.get(i));
        }
        return new ArrayList<>(groups.values());
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }
}
