package ru.heatnet.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.routing.RouteFinderFactory;
import ru.heatnet.routing.RouteResult;
import ru.heatnet.routing.RoutingPipeline;

/**
 * M4. Фасад: кандидаты врезки → маршруты M3 → деревья → M5 verify → DN-узлы → CrossingResolver → TopologyValidator.
 */
@Service
public class NetworkPlanner {

    private static final Logger log = LoggerFactory.getLogger(NetworkPlanner.class);

    private final ReferenceData reference;
    private final ProjectionService projection;
    private final RoutingPipeline routingPipeline;
    private final RouteFinderFactory routeFinderFactory;
    private final JointPlanner jointPlanner;
    private final CrossingResolver crossingResolver;
    private final EngineeringCalculator engineeringCalculator;
    private final NetworkTreeBuilder treeBuilder;
    private final TieInRouter tieInRouter;
    private final SteinerPlanner steinerPlanner;

    public NetworkPlanner(ReferenceData reference,
                          ProjectionService projection,
                          RoutingPipeline routingPipeline,
                          RouteFinderFactory routeFinderFactory) {
        this.reference = reference;
        this.projection = projection;
        this.routingPipeline = routingPipeline;
        this.routeFinderFactory = routeFinderFactory;
        this.jointPlanner = new JointPlanner(reference, projection, routingPipeline);
        double tol = reference.getRules().getGeometryToleranceM();
        this.crossingResolver = new CrossingResolver(projection.utmFactory(), tol,
                reference.getRules().getRoutingCrossingBudgetMs());
        this.engineeringCalculator = new EngineeringCalculator(reference);
        this.treeBuilder = new NetworkTreeBuilder(projection.utmFactory(), tol,
                reference.getRules().getMaxSegmentsPerChamber(),
                reference.getRules().getRoutingClusterPrefixM());
        this.tieInRouter = new TieInRouter(reference, projection, routingPipeline, this.treeBuilder);
        this.steinerPlanner = new SteinerPlanner(reference, projection, routeFinderFactory);
    }

    /** План для всех oks_connection_point из ingest. */
    public NetworkPlan plan(IngestResult ingest) {
        ExistingNetwork existing = ingest.getExistingNetwork();
        ExistingNetworkGeometry geometry = ExistingNetworkGeometry.fromIngest(
                ingest.getAcceptedFeatures(), existing, projection);
        return plan(ingest, existing, geometry, ingest.getOksConnectionPoints());
    }

    public NetworkPlan plan(IngestResult ingest,
                            ExistingNetwork existing,
                            ExistingNetworkGeometry geometry,
                            List<OksConnectionPoint> oksPoints) {
        return plan(ingest, existing, geometry, oksPoints, PlanMode.JOINT, null);
    }

    /**
     * AUDIT-24.09 (Claude): план в заданном режиме M7 ({@link PlanMode}) одним сеансом дерева Штейнера.
     *
     * @param groupOf id ОКС → номер группы для {@link PlanMode#CLUSTERED}
     */
    public NetworkPlan plan(IngestResult ingest,
                            ExistingNetwork existing,
                            ExistingNetworkGeometry geometry,
                            List<OksConnectionPoint> oksPoints,
                            PlanMode mode,
                            java.util.Map<String, Integer> groupOf) {
        return plan(ingest, existing, geometry, oksPoints, mode, groupOf, 0);
    }

    /**
     * ДУ, по которому по умолчанию строятся буферы графа трассировки: ДУ магистрали по суммарному расходу ОКС.
     */
    public int magistralRoutingDn(List<OksConnectionPoint> oksPoints) {
        double flow = 0;
        if (oksPoints != null) {
            for (OksConnectionPoint oks : oksPoints) {
                flow += oks.getFlowTph();
            }
        }
        return routeFinderFactory.magistralDn(flow);
    }

    /**
     * AUDIT-12 (Claude, 24.09). То же с заданным ДУ маршрутизации {@code routingDn} (0 — ДУ магистрали). Отступы
     * табл. 2 и габарит зависят от ДУ, а ДУ участков известен только после расчёта M5; граф с ДУ магистрали
     * всех ОКС — заведомо «с запасом» (на синтетике с 30 ОКС: ДУ 500 и отступ от зданий 7 м при фактических
     * ДУ ≤ 300 и нормативных 5 м). M7 строит второй план с фактическим ДУ первого — см. VariantGenerator; план
     * второго прохода принимается, только если ни один участок не получил ДУ больше {@code routingDn} (буферы
     * графа не меньше нормативных для каждого участка).
     */
    public NetworkPlan plan(IngestResult ingest,
                            ExistingNetwork existing,
                            ExistingNetworkGeometry geometry,
                            List<OksConnectionPoint> oksPoints,
                            PlanMode mode,
                            java.util.Map<String, Integer> groupOf,
                            int routingDn) {
        ru.heatnet.rules.RestrictionEngineFactory.beginRequest();
        treeBuilder.resetCounters();
        crossingResolver.resetCounters();
        try {
            NetworkPlan raw = planNetwork(ingest, existing, geometry, oksPoints, mode, groupOf, routingDn);
            VerifyOutcome verified = verifyAfterM5(ingest, existing, geometry, raw);
            // QA-FIX P3 (H-1): шаг applyDnBoundaries убран. По §2.3 ДУ сохраняется на всём пути между
            // узлами смены расхода, то есть граница ДУ совпадает с камерой; по §2.1 technical_node у камеры
            // не ставится (ТУ — только там, где без разветвления меняется параметр участка).
            List<BuiltNetworkTree> built = straightenAtActualDn(ingest, existing,
                    crossingResolver.resolveAll(verified.trees));
            List<NewNetworkTree> trees = new ArrayList<>();
            List<NetworkTreeLayout> layouts = new ArrayList<>();
            for (BuiltNetworkTree b : built) {
                trees.add(b.getTree());
                layouts.add(b.getLayout());
            }
            List<ru.heatnet.cost.UnconnectedOks> unconnected = new ArrayList<>(raw.getUnconnectedOks());
            unconnected.addAll(verified.lost);
            // QA-FIX H-7 + C-3 + C-7: проверяем топологию И глобальные пересечения по всем деревьям,
            // но не бросаем исключение: по §2.5 неподключение допускается только когда маршрут не найден,
            // а падение всего расчёта из-за одной ветки обнуляет результат по всем ОКС.
            List<String> problems = TopologyValidator.problems(built,
                    reference.getRules().getMaxSegmentsPerChamber(),
                    reference.getRules().getGeometryToleranceM());
            if (!problems.isEmpty()) {
                log.warn("План построен с нарушениями ({}): {}", problems.size(), problems);
            }
            return new NetworkPlan(trees, layouts, unconnected, raw.isJointConnection(), problems);
        } finally {
            ru.heatnet.rules.RestrictionEngineFactory.endRequest();
        }
    }

    /**
     * AUDIT-24.09 (Claude). §2.1: «без необоснованных мелких изломов». Трасса ищется с буферами по ДУ
     * магистрали (наибольший расход — консервативно); после подбора фактического ДУ часть вершин оказывается
     * лишней: излом 0,5–2° и вершина в 0,1–0,3 м от прямой, которая при фактическом (меньшем) ДУ свободна.
     * Такие внутренние вершины убираются, если прямой отрезок допустим при фактическом ДУ участка: не
     * нарушает ограничений полного набора, не добавляет спецпроход, повороты у соседних вершин ≤ 90°, не
     * пересекает другие участки новой сети. Первое и последнее звено участка, а для участка к ОКС — и точка
     * начала финального прямого захода (§2.2) не трогаются, поэтому повороты в узлах не меняются.
     */
    private List<BuiltNetworkTree> straightenAtActualDn(IngestResult ingest, ExistingNetwork existing,
                                                        List<BuiltNetworkTree> built) {
        if (built == null || built.isEmpty()) {
            return built;
        }
        List<NewNetworkTree> trees = new ArrayList<>();
        for (BuiltNetworkTree b : built) {
            trees.add(b.getTree());
        }
        EngineeringResult eng;
        try {
            eng = engineeringCalculator.calculate(trees, existing);
        } catch (RuntimeException ex) {
            return built;
        }
        List<org.locationtech.jts.geom.LineString> all = new ArrayList<>();
        for (BuiltNetworkTree b : built) {
            for (ru.heatnet.calc.model.NewSegment segment : b.getTree().segmentsTopDown()) {
                org.locationtech.jts.geom.LineString line = b.getLayout().segmentLine(segment.getId());
                if (line != null) {
                    all.add(line);
                }
            }
        }
        List<BuiltNetworkTree> out = new ArrayList<>();
        int removed = 0;
        for (int i = 0; i < built.size(); i++) {
            BuiltNetworkTree b = built.get(i);
            NewNetworkTree tree = b.getTree();
            java.util.Map<String, Integer> dns = i < eng.getTrees().size()
                    ? eng.getTrees().get(i).getDiameters() : java.util.Collections.<String, Integer>emptyMap();
            List<ru.heatnet.calc.model.NewSegment> segments = new ArrayList<>();
            boolean changed = false;
            for (ru.heatnet.calc.model.NewSegment segment : tree.segmentsTopDown()) {
                org.locationtech.jts.geom.LineString line = b.getLayout().segmentLine(segment.getId());
                Integer dn = dns.get(segment.getId());
                if (line == null || dn == null || line.getNumPoints() < 3
                        || segment.getLayingMethod() != ru.heatnet.calc.model.LayingMethod.BASE) {
                    segments.add(segment);
                    continue;
                }
                ru.heatnet.calc.model.NewNode to = tree.node(segment.getToNodeId());
                boolean endsAtOks = to != null && to.getKind() == ru.heatnet.calc.model.NodeKind.OKS_CONNECTION;
                // AUDIT-12 (Claude, 24.09): первое звено можно спрямлять, если в начальном узле нет ограничения,
                // которое сдвиг направления может нарушить: у врезки (корень дерева) — без условий, у новой камеры —
                // с проверкой поворота ≤ 90° от питающего участка (Разъяснение №5). У technical_node первое звено
                // не трогается: излом лишь переехал бы в узел на границе спецпрохода.
                ru.heatnet.calc.model.NewNode from = tree.node(segment.getFromNodeId());
                boolean allowFirst = false;
                org.locationtech.jts.geom.Coordinate firstPrev = null;
                if (from != null && from.getKind() == ru.heatnet.calc.model.NodeKind.TIE_IN) {
                    allowFirst = true;
                } else if (from != null && from.getKind() == ru.heatnet.calc.model.NodeKind.NEW_CHAMBER) {
                    ru.heatnet.calc.model.NewSegment incoming = tree.incomingOf(from.getId());
                    org.locationtech.jts.geom.LineString parent = incoming == null ? null
                            : b.getLayout().segmentLine(incoming.getId());
                    if (parent != null && parent.getNumPoints() >= 2) {
                        allowFirst = true;
                        firstPrev = parent.getCoordinateN(parent.getNumPoints() - 2);
                    }
                }
                org.locationtech.jts.geom.LineString pulled;
                try {
                    pulled = straightenLine(ingest, line, dn, endsAtOks, all, allowFirst, firstPrev);
                } catch (RuntimeException ex) {
                    pulled = line;
                }
                if (pulled == line || pulled.getNumPoints() == line.getNumPoints()) {
                    segments.add(segment);
                    continue;
                }
                removed += line.getNumPoints() - pulled.getNumPoints();
                all.remove(line);
                all.add(pulled);
                b.getLayout().putSegment(segment.getId(), pulled);
                segments.add(ru.heatnet.calc.model.NewSegment.base(segment.getId(), segment.getFromNodeId(),
                        segment.getToNodeId(), pulled.getLength()));
                changed = true;
            }
            out.add(changed ? new BuiltNetworkTree(new NewNetworkTree(tree.getTieIn(),
                    new ArrayList<>(tree.getNodes().values()), segments), b.getLayout()) : b);
        }
        if (removed > 0) {
            log.info("Спрямление при фактическом ДУ: убрано вершин {}", removed);
        }
        return out;
    }

    private org.locationtech.jts.geom.LineString straightenLine(IngestResult ingest,
                                                                org.locationtech.jts.geom.LineString line, int dn,
                                                                boolean endsAtOks,
                                                                List<org.locationtech.jts.geom.LineString> all,
                                                                boolean allowFirst,
                                                                org.locationtech.jts.geom.Coordinate firstPrev) {
        ru.heatnet.rules.SpatialConstraintEngine engine = routeFinderFactory.bundle(ingest, dn).getEngine();
        List<org.locationtech.jts.geom.Coordinate> pts = new ArrayList<>(java.util.Arrays.asList(line.getCoordinates()));
        boolean any = false;
        // Вершина на прямой (излом < 0,01°) — лишняя в любом месте участка, включая точку начала финального
        // захода: геометрия линии при её удалении не меняется (смещение < 1 см на 60 м).
        for (int k = pts.size() - 2; k >= 1; k--) {
            if (TurnRepairAccess.deviation(pts.get(k - 1), pts.get(k), pts.get(k + 1)) < 0.01) {
                pts.remove(k);
                any = true;
            }
        }
        boolean progress = true;
        while (progress) {
            progress = false;
            int last = pts.size() - 1;
            // удаляемая вершина i: соседи i-1 >= 1 и i+1 <= last-1 (первое/последнее звено не меняются);
            // у участка к ОКС точка начала финального захода (last-1) не удаляется
            int maxI = endsAtOks ? last - 2 : last - 2;
            for (int k = allowFirst ? 1 : 2; k <= maxI; k++) {
                org.locationtech.jts.geom.Coordinate a = pts.get(k - 1);
                org.locationtech.jts.geom.Coordinate c = pts.get(k + 1);
                if (endsAtOks && k == last - 1) {
                    continue;
                }
                org.locationtech.jts.geom.Coordinate before = k >= 2 ? pts.get(k - 2) : firstPrev;
                if ((before != null && TurnRepairAccess.deviation(before, a, c) > 90.0 + 1e-6)
                        || TurnRepairAccess.deviation(a, c, pts.get(k + 2)) > 90.0 + 1e-6) {
                    continue;
                }
                if (k == 1 && TurnRepairAccess.deviation(a, pts.get(1), c) >= 2.0) {
                    continue; // первое звено — только для мелкого излома (< 2°), крупные повороты оставляет планировщик
                }
                org.locationtech.jts.geom.LineString shortcut = line.getFactory().createLineString(
                        new org.locationtech.jts.geom.Coordinate[] {new org.locationtech.jts.geom.Coordinate(a),
                                new org.locationtech.jts.geom.Coordinate(c)});
                if (engine.isSegmentBlocked(shortcut, dn) || !engine.extractSpecialSections(shortcut, dn).isEmpty()) {
                    continue;
                }
                boolean crosses = false;
                org.locationtech.jts.geom.Coordinate touch = k == 1 ? a : null;
                for (org.locationtech.jts.geom.LineString other : all) {
                    if (other == line) {
                        continue;
                    }
                    if (touch == null ? shortcut.intersects(other) : crossesAwayFrom(shortcut, other, touch)) {
                        crosses = true;
                        break;
                    }
                    if (ru.heatnet.routing.NewNetworkClearance.tooClose(shortcut, other, touch, null)) {
                        crosses = true;
                        break;
                    }
                }
                if (crosses) {
                    continue;
                }
                pts.remove(k);
                any = true;
                progress = true;
                break;
            }
        }
        return any ? line.getFactory().createLineString(pts.toArray(new org.locationtech.jts.geom.Coordinate[0])) : line;
    }

    /** Пересечение {@code a} и {@code b} где-либо, кроме окрестности общего узла {@code node} (1,05 м). */
    private static boolean crossesAwayFrom(org.locationtech.jts.geom.LineString a,
                                           org.locationtech.jts.geom.LineString b,
                                           org.locationtech.jts.geom.Coordinate node) {
        if (!a.intersects(b)) {
            return false;
        }
        org.locationtech.jts.geom.Geometry inter;
        try {
            inter = a.intersection(b);
        } catch (RuntimeException ex) {
            return true;
        }
        if (inter.getDimension() >= 1 && inter.getLength() > 1e-6) {
            return true;
        }
        for (org.locationtech.jts.geom.Coordinate c : inter.getCoordinates()) {
            if (c.distance(node) > ru.heatnet.routing.NewNetworkClearance.NODE_RADIUS_M) {
                return true;
            }
        }
        return false;
    }

    /** Отклонение направления в вершине (0° — прямо), как в TurnRepair. */
    private static final class TurnRepairAccess {
        static double deviation(org.locationtech.jts.geom.Coordinate prev, org.locationtech.jts.geom.Coordinate at,
                                org.locationtech.jts.geom.Coordinate next) {
            return ru.heatnet.routing.TurnRepair.deviationDeg(prev, at, next);
        }
    }

    private VerifyOutcome verifyAfterM5(IngestResult ingest,
                                       ExistingNetwork existing,
                                       ExistingNetworkGeometry geometry,
                                       NetworkPlan raw) {
        List<NewNetworkTree> trees = raw.getTrees();
        List<NetworkTreeLayout> layouts = raw.getLayouts();
        if (trees.isEmpty()) {
            return new VerifyOutcome(Collections.<BuiltNetworkTree>emptyList(),
                    Collections.<ru.heatnet.cost.UnconnectedOks>emptyList());
        }
        EngineeringResult eng = engineeringCalculator.calculate(trees, existing);
        List<BuiltNetworkTree> result = new ArrayList<>();
        List<ru.heatnet.cost.UnconnectedOks> lost = new ArrayList<>();
        java.util.Map<String, Integer> extraChamberLoad = new java.util.LinkedHashMap<>();
        for (int i = 0; i < trees.size(); i++) {
            NetworkTreeLayout layout = i < layouts.size() ? layouts.get(i) : new NetworkTreeLayout();
            NewNetworkTree tree = trees.get(i);
            BuiltNetworkTree verified = verifyTree(ingest, existing, geometry, tree, layout, eng, i);
            if (verified != null) {
                result.add(verified);
                TieInRouter.rememberChamber(verified.getTree().getTieIn(), extraChamberLoad);
                continue;
            }
            for (ru.heatnet.calc.model.NewNode oksNode : tree.oksNodes()) {
                BuiltNetworkTree split = tryIndependentInlet(ingest, existing, geometry, oksNode.getOksId(),
                        extraChamberLoad);
                if (split != null) {
                    result.add(split);
                    TieInRouter.rememberChamber(split.getTree().getTieIn(), extraChamberLoad);
                } else {
                    lost.add(new ru.heatnet.cost.UnconnectedOks(oksNode.getOksId(), oksNode.getOksFlowTph()));
                }
            }
        }
        return new VerifyOutcome(result, lost);
    }

    private BuiltNetworkTree tryIndependentInlet(IngestResult ingest,
                                                 ExistingNetwork existing,
                                                 ExistingNetworkGeometry geometry,
                                                 String oksId,
                                                 java.util.Map<String, Integer> extraChamberLoad) {
        OksConnectionPoint oks = findOks(ingest, oksId);
        return tieInRouter.routeSingle(ingest, existing, geometry, oks, extraChamberLoad);
    }

    private BuiltNetworkTree verifyTree(IngestResult ingest,
                                        ExistingNetwork existing,
                                        ExistingNetworkGeometry geometry,
                                        NewNetworkTree tree,
                                        NetworkTreeLayout layout,
                                        EngineeringResult eng,
                                        int treeIndex) {
        if (treeIndex >= eng.getTrees().size()) {
            return new BuiltNetworkTree(tree, layout);
        }
        String rootSegId = tree.rootSegment().getId();
        int actualDn = eng.getTrees().get(treeIndex).getDiameters().get(rootSegId);
        double totalFlow = 0;
        for (ru.heatnet.calc.model.NewNode oks : tree.oksNodes()) {
            totalFlow += oks.getOksFlowTph();
        }
        int routingDn = tree.oksNodes().size() <= 1
                ? routeFinderFactory.leafDn(tree.oksNodes().get(0).getOksFlowTph())
                : routeFinderFactory.magistralDn(totalFlow);

        if (actualDn <= routingDn || layoutLegalAtDn(ingest, tree, layout, actualDn)) {
            return new BuiltNetworkTree(tree, layout);
        }

        // QA-FIX P2 (C-1): стартуем от фактической точки врезки дерева, а не пересчитываем её
        org.locationtech.jts.geom.Coordinate tieC = layout.nodeCoordinate(tree.getTieIn().getId());
        Point tieUtm = tieC != null ? projection.utmFactory().createPoint(tieC) : gfPoint(geometry, tree);
        boolean changed = false;
        List<NetworkTreeBuilder.OksRoute> routes = new ArrayList<>();
        for (ru.heatnet.calc.model.NewNode oksNode : tree.oksNodes()) {
            OksConnectionPoint oks = findOks(ingest, oksNode.getOksId());
            Point oksUtm = oksPoint(ingest, oks.getId());
            RouteResult initial = tree.oksNodes().size() <= 1
                    ? routingPipeline.findLeafRoute(ingest, tieUtm, oksUtm, oks.getFlowTph())
                    : routingPipeline.findMagistralRoute(ingest, tieUtm, oksUtm, totalFlow);
            RouteResult verified = routingPipeline.verifyAfterM5(ingest, tieUtm, oksUtm, initial, routingDn, actualDn);
            if (!verified.isFound()) {
                return null;
            }
            if (verified.getPathUtm() != null && initial.getPathUtm() != null
                    && Math.abs(verified.getLengthM() - initial.getLengthM()) > 0.5) {
                changed = true;
            }
            routes.add(new NetworkTreeBuilder.OksRoute(oks, verified));
        }
        if (!changed) {
            return new BuiltNetworkTree(tree, layout);
        }
        if (routes.size() == 1) {
            return treeBuilder.buildSingle(tree.getTieIn(), routes.get(0).getRoute(), routes.get(0).getOks());
        }
        return treeBuilder.buildMerged(tree.getTieIn(), routes);
    }

    private Point gfPoint(ExistingNetworkGeometry geometry, NewNetworkTree tree) {
        ru.heatnet.calc.model.TieInPoint tie = tree.getTieIn();
        if (tie.getExistingObjectType() == ru.heatnet.calc.model.ExistingObjectType.HEAT_CHAMBER) {
            return projection.utmFactory().createPoint(geometry.chamberPoint(tie.getExistingObjectId()));
        }
        Double dist = tie.getDistanceFromUpstreamEndM();
        if (dist != null) {
            return projection.utmFactory().createPoint(
                    geometry.pointAtDistanceFromUpstream(tie.getExistingObjectId(), dist));
        }
        return projection.utmFactory().createPoint(geometry.upstreamEndpoint(tie.getExistingObjectId()));
    }

    private static OksConnectionPoint findOks(IngestResult ingest, String oksId) {
        for (OksConnectionPoint oks : ingest.getOksConnectionPoints()) {
            if (oks.getId().equals(oksId)) {
                return oks;
            }
        }
        throw new NetworkException("ОКС " + oksId + " не найден в ingest");
    }

    /** Допуск «та же ближайшая стена» для {@link #entryExcessM}: как у доводки захода (OwnEntryCandidates), м. */
    static final double ENTRY_EXCESS_TOLERANCE_M = ru.heatnet.routing.OwnEntryCandidates.RELAXED_EXCESS_M;

    /**
     * AUDIT-13 (Claude, 25.09). Мера отступления плана от §2.2 / Разъяснения №3 («один финальный прямой участок от
     * ближайшей к точке границы полигона»): сумма по ОКС, насколько финальный прямой участок проходит внутри своего
     * полигона дальше, чем расстояние от точки до ближайшей наружной стены (плюс допуск
     * {@value #ENTRY_EXCESS_TOLERANCE_M} м), м. 0 — у всех ОКС заход через ближайшую стену.
     *
     * <p>Более глубокий или косой заход сервис допускает, только если ближайшая стена недоступна (уровни захода
     * OwnEntryCandidates). Недоступность проверяется при ДУ графа плана; план с ДУ графа «с запасом» (магистраль) может
     * отступить от ближайшей стены там, где при фактическом ДУ она доступна. Уточняющий проход M7 (фактический ДУ)
     * сравнивается с первым планом сначала по этой мере, затем по S — см. VariantGenerator.</p>
     */
    public double entryExcessM(IngestResult ingest, NetworkPlan plan) {
        if (plan == null) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < plan.getTrees().size() && i < plan.getLayouts().size(); i++) {
            NewNetworkTree tree = plan.getTrees().get(i);
            NetworkTreeLayout layout = plan.getLayouts().get(i);
            for (ru.heatnet.calc.model.NewNode oks : tree.oksNodes()) {
                try {
                    ru.heatnet.calc.model.NewSegment in = tree.incomingOf(oks.getId());
                    org.locationtech.jts.geom.LineString line = in == null ? null : layout.segmentLine(in.getId());
                    if (line == null || line.getNumPoints() < 2) {
                        continue;
                    }
                    Point p = oksPoint(ingest, oks.getOksId());
                    org.locationtech.jts.geom.Geometry own = routeFinderFactory.ownOksGeometry(ingest, p);
                    if (own == null || own.isEmpty()) {
                        continue;
                    }
                    org.locationtech.jts.geom.Coordinate q = line.getCoordinateN(line.getNumPoints() - 2);
                    org.locationtech.jts.geom.LineString leg = line.getFactory().createLineString(
                            new org.locationtech.jts.geom.Coordinate[] {q, p.getCoordinate()});
                    double inside = leg.intersection(own).getLength();
                    double dmin = Double.POSITIVE_INFINITY;
                    for (int k = 0; k < own.getNumGeometries(); k++) {
                        org.locationtech.jts.geom.Geometry part = own.getGeometryN(k);
                        if (part instanceof org.locationtech.jts.geom.Polygon && part.distance(p) <= 0.05) {
                            dmin = Math.min(dmin,
                                    ((org.locationtech.jts.geom.Polygon) part).getExteriorRing().distance(p));
                        }
                    }
                    if (Double.isFinite(dmin)) {
                        sum += Math.max(0.0, inside - dmin - ENTRY_EXCESS_TOLERANCE_M);
                    }
                } catch (RuntimeException ex) {
                    // мера — только для сравнения планов; сбой геометрии на одном ОКС её не обнуляет
                }
            }
        }
        return sum;
    }

    private Point oksPoint(IngestResult ingest, String oksId) {
        ru.heatnet.ingest.RawFeature f = ingest.oksFeature(oksId);
        if (f == null || f.getGeometryWgs84() == null) {
            throw new NetworkException("oks_connection_point " + oksId + " не найден");
        }
        org.locationtech.jts.geom.Coordinate c = f.getGeometryWgs84().getCoordinate();
        return projection.pointToUtm(c.x, c.y);
    }

    /**
     * Сначала двор (Штейнер). Кто не вошёл в дерево — отдельный ввод прежним маршрутизатором.
     * Если граф дворов не собрался, остаётся совместный план.
     */
    private NetworkPlan planNetwork(IngestResult ingest,
                                    ExistingNetwork existing,
                                    ExistingNetworkGeometry geometry,
                                    List<OksConnectionPoint> oksPoints,
                                    PlanMode mode,
                                    java.util.Map<String, Integer> groupOf,
                                    int routingDn) {
        NetworkPlan steiner = null;
        // AUDIT-24.09 (Claude): дерево Штейнера и для одного ОКС. Раньше условие было «>= 2», и набор
        // с единственной точкой подключения (или стратегия «каждый ОКС отдельно») уходил в старый
        // JointPlanner, который на конкурсной застройке не находит маршрут: 0 подключённых из 1.
        if (oksPoints != null && !oksPoints.isEmpty()) {
            try {
                steiner = steinerPlanner.plan(ingest, existing, geometry, oksPoints, mode, groupOf, routingDn);
            } catch (RuntimeException ex) {
                log.warn("Планировщик Штейнера остановлен: {}", ex.toString(), ex);
            }
        }
        if (steiner == null) {
            return jointPlanner.plan(ingest, existing, geometry, oksPoints);
        }
        return steiner;
    }

    /**
     * Участки уже легальны для фактического ДУ — не перетрассировывать дерево в лучи от врезки.
     * Финальный прямой заход в свой полигон проверяется бандлом без этого полигона (§2.2).
     */
    private boolean layoutLegalAtDn(IngestResult ingest, NewNetworkTree tree, NetworkTreeLayout layout, int dn) {
        ru.heatnet.rules.SpatialConstraintBundle bundle = routeFinderFactory.bundle(ingest, dn);
        for (ru.heatnet.calc.model.NewSegment segment : tree.segmentsTopDown()) {
            org.locationtech.jts.geom.LineString line = layout.segmentLine(segment.getId());
            if (line == null) {
                return false;
            }
            try {
                if (!bundle.getEngine().isSegmentBlocked(line, dn)) {
                    continue;
                }
            } catch (RuntimeException ex) {
                return false;
            }
            if (!finalStraightLegal(ingest, tree, layout, segment, line, bundle, dn)) {
                return false;
            }
        }
        return true;
    }

    /**
     * AUDIT-24.09 (Claude). §2.2: от отступа к своему полигону освобождён только финальный прямой
     * заход Q→P. Он может быть разбит на несколько участков (границы спецпроходов на этой прямой),
     * а последний участок может содержать и подход к Q. Участок законен, если его часть до
     * финальной прямой свободна по полному движку, а часть на финальной прямой — по движку без
     * своего полигона. Раньше требовался ровно двухточечный последний участок, и любое другое
     * разбиение отправляло дерево на перетрассировку старым маршрутизатором.
     */
    private boolean finalStraightLegal(IngestResult ingest, NewNetworkTree tree, NetworkTreeLayout layout,
                                       ru.heatnet.calc.model.NewSegment segment,
                                       org.locationtech.jts.geom.LineString line,
                                       ru.heatnet.rules.SpatialConstraintBundle full, int dn) {
        // до ОКС по цепочке узлов без разветвления
        ru.heatnet.calc.model.NewSegment cur = segment;
        java.util.List<org.locationtech.jts.geom.LineString> chain = new ArrayList<>();
        chain.add(line);
        ru.heatnet.calc.model.NewNode to = tree.node(cur.getToNodeId());
        int guard = 0;
        while (to != null && to.getKind() != ru.heatnet.calc.model.NodeKind.OKS_CONNECTION && guard++ < 64) {
            List<ru.heatnet.calc.model.NewSegment> children = tree.childrenOf(to.getId());
            if (children.size() != 1) {
                return false;
            }
            cur = children.get(0);
            org.locationtech.jts.geom.LineString next = layout.segmentLine(cur.getId());
            if (next == null) {
                return false;
            }
            chain.add(next);
            to = tree.node(cur.getToNodeId());
        }
        if (to == null || to.getKind() != ru.heatnet.calc.model.NodeKind.OKS_CONNECTION) {
            return false;
        }
        org.locationtech.jts.geom.Coordinate oks = layout.nodeCoordinate(to.getId());
        if (oks == null) {
            return false;
        }
        // финальная прямая: последнее звено последнего участка цепочки
        org.locationtech.jts.geom.LineString lastLine = chain.get(chain.size() - 1);
        org.locationtech.jts.geom.Coordinate q = lastLine.getCoordinateN(lastLine.getNumPoints() - 2);
        if (chain.size() > 1 || lastLine.getNumPoints() == 2) {
            // Q — первая вершина прямой, на которой лежат все участки цепочки после поворота
            List<org.locationtech.jts.geom.Coordinate> all = new ArrayList<>();
            for (org.locationtech.jts.geom.LineString l : chain) {
                for (org.locationtech.jts.geom.Coordinate c : l.getCoordinates()) {
                    if (all.isEmpty() || all.get(all.size() - 1).distance(c) > 0.01) {
                        all.add(c);
                    }
                }
            }
            int k = all.size() - 2;
            while (k > 0 && ru.heatnet.routing.TurnRepair.deviationDeg(all.get(k - 1), all.get(k), oks) < 0.5) {
                k--;
            }
            q = all.get(k);
        }
        ru.heatnet.rules.SpatialConstraintBundle approach = routeFinderFactory.bundleForTarget(
                ingest, dn, projection.utmFactory().createPoint(oks));
        org.locationtech.jts.geom.GeometryFactory gf = projection.utmFactory();
        try {
            // часть этого участка на финальной прямой — по движку без своего полигона
            org.locationtech.jts.geom.LineString straight = gf.createLineString(
                    new org.locationtech.jts.geom.Coordinate[] {q, oks});
            org.locationtech.jts.geom.Geometry onStraight = line.intersection(straight.buffer(0.02));
            org.locationtech.jts.geom.Geometry offStraight = line.difference(straight.buffer(0.02));
            for (int i = 0; i < onStraight.getNumGeometries(); i++) {
                org.locationtech.jts.geom.Geometry g = onStraight.getGeometryN(i);
                if (g instanceof org.locationtech.jts.geom.LineString && g.getLength() > 0.01
                        && approach.getEngine().isSegmentBlocked((org.locationtech.jts.geom.LineString) g, dn)) {
                    return false;
                }
            }
            for (int i = 0; i < offStraight.getNumGeometries(); i++) {
                org.locationtech.jts.geom.Geometry g = offStraight.getGeometryN(i);
                if (g instanceof org.locationtech.jts.geom.LineString && g.getLength() > 0.05
                        && full.getEngine().isSegmentBlocked((org.locationtech.jts.geom.LineString) g, dn)) {
                    return false;
                }
            }
        } catch (RuntimeException ex) {
            return false;
        }
        return true;
    }

    private static final class VerifyOutcome {
        private final List<BuiltNetworkTree> trees;
        private final List<ru.heatnet.cost.UnconnectedOks> lost;

        private VerifyOutcome(List<BuiltNetworkTree> trees, List<ru.heatnet.cost.UnconnectedOks> lost) {
            this.trees = trees;
            this.lost = lost;
        }
    }
}
