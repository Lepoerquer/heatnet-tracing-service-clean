package ru.heatnet.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.heatnet.calc.model.LayingMethod;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.NodeKind;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.calc.reference.RestrictionRule;
import ru.heatnet.cost.ChamberCostScale;
import ru.heatnet.cost.UnconnectedOks;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.routing.OwnEntryCandidates;
import ru.heatnet.routing.RouteFinderFactory;
import ru.heatnet.routing.RouteResult;
import ru.heatnet.routing.SharedVisibilityRouter;
import ru.heatnet.routing.TurnRepair;
import ru.heatnet.rules.SpatialConstraintBundle;
import ru.heatnet.rules.SpatialConstraintEngine;
import ru.heatnet.rules.model.SpecialSection;

/**
 * Дерево Штейнера: каждый следующий ОКС присоединяется к уже построенной трассе
 * или к существующей сети — тем путём, который дешевле. Так магистраль идёт двором,
 * а не отдельным лучом по улице до каждой точки.
 *
 * <p>AUDIT-24.09 (Claude) — переработка по актуальному приложению и Разъяснениям:</p>
 * <ul>
 *   <li>§2.2 / Разъяснение №3: заход в свой полигон — один прямой отрезок от ближайшей
 *       (доступной) границы до точки ({@link OwnEntryCandidates}); раньше допускался заход
 *       через любую стену с запасом «до стены + 18 м» (фактически до 24 м сквозь корпус);</li>
 *   <li>§2.1 / Разъяснение №5: поворот ≤ 90° и в камере ответвления — для семян на уже
 *       построенной сети задаётся направление питающего участка;</li>
 *   <li>§2.4 / Разъяснение №11: точка на трубе ближе 10 м к существующей камере, к которой можно
 *       примкнуть, не используется — врезка идёт в саму камеру;</li>
 *   <li>неудачная попытка ответвления больше не оставляет в дереве «пустую» камеру
 *       без разветвления (откат изменений);</li>
 *   <li>работает и для одного ОКС (раньше — только от двух: при одном ОКС расчёт уходил
 *       в старый планировщик и не подключал ничего);</li>
 *   <li>режимы {@link PlanMode} для M7 в одном графе и с общей проверкой пересечений.</li>
 * </ul>
 */
final class SteinerPlanner {

    private static final Logger log = LoggerFactory.getLogger(SteinerPlanner.class);
    private static final double PIPE_SAMPLE_M = 40.0;
    private static final double TREE_SAMPLE_M = 22.0;
    private static final double TIE_CLEARANCE_M = 1.05;
    private static final double PIPE_END_MARGIN_M = 2.0;
    private static final double ANCHOR_DEDUPE_M = 5.0;
    private static final double HEADING_BACK_M = 1.0;
    /**
     * Штраф (в метрах трассы) за ответвление в режиме SEPARATE: отдельный ввод выбирается, пока он длиннее
     * ответвления не больше чем на эту величину. Параметр стратегии M7, а не правило приложения.
     */
    static final double SEPARATE_BRANCH_PENALTY_M = 250.0;

    private final ReferenceData reference;
    private final ProjectionService projection;
    private final RouteFinderFactory routeFinderFactory;
    private final GeometryFactory gf;
    private final RouteSegmentSplitter splitter;
    private int seq;

    SteinerPlanner(ReferenceData reference, ProjectionService projection, RouteFinderFactory routeFinderFactory) {
        this.reference = reference;
        this.projection = projection;
        this.routeFinderFactory = routeFinderFactory;
        this.gf = projection.utmFactory();
        this.splitter = new RouteSegmentSplitter(gf, reference.getRules().getGeometryToleranceM());
    }

    NetworkPlan plan(IngestResult ingest,
                     ru.heatnet.calc.model.ExistingNetwork existing,
                     ExistingNetworkGeometry geometry,
                     List<OksConnectionPoint> oksPoints) {
        return plan(ingest, existing, geometry, oksPoints, PlanMode.JOINT, null);
    }

    /**
     * @param groupOf для {@link PlanMode#CLUSTERED}: id ОКС → номер группы; для остальных режимов не нужен
     */
    NetworkPlan plan(IngestResult ingest,
                     ru.heatnet.calc.model.ExistingNetwork existing,
                     ExistingNetworkGeometry geometry,
                     List<OksConnectionPoint> oksPoints,
                     PlanMode mode,
                     Map<String, Integer> groupOf) {
        return plan(ingest, existing, geometry, oksPoints, mode, groupOf, 0);
    }

    /**
     * @param routingDnOverride ДУ, по которому строятся буферы/отступы графа (0 — ДУ магистрали по суммарному
     *                          расходу всех ОКС). AUDIT-12 (Claude, 24.09): M7 делает второй проход с ДУ, равным
     *                          наибольшему фактическому ДУ первого плана (см. VariantGenerator); значение больше ДУ
     *                          магистрали игнорируется.
     */
    NetworkPlan plan(IngestResult ingest,
                     ru.heatnet.calc.model.ExistingNetwork existing,
                     ExistingNetworkGeometry geometry,
                     List<OksConnectionPoint> oksPoints,
                     PlanMode mode,
                     Map<String, Integer> groupOf,
                     int routingDnOverride) {
        if (oksPoints == null || oksPoints.isEmpty()) {
            return null;
        }
        PlanMode planMode = mode == null ? PlanMode.JOINT : mode;
        double flow = 0;
        for (OksConnectionPoint oks : oksPoints) {
            flow += oks.getFlowTph();
        }
        int dn = routeFinderFactory.magistralDn(flow);
        if (routingDnOverride > 0 && routingDnOverride < dn) {
            dn = routingDnOverride;
        }
        SpatialConstraintBundle bundle = routeFinderFactory.bundle(ingest, dn);
        if (bundle == null || bundle.getEngine() == null) {
            // AUDIT-13 (Claude, 25.09): без набора ограничений граф не строится — прежний планировщик (раньше —
            // NullPointerException, перехватывался в NetworkPlanner и печатался стек в лог).
            log.info("Набор ограничений для ДУ {} не собран — граф дворов не строится", dn);
            return null;
        }
        SpatialConstraintEngine fullEngine = bundle.getEngine();
        Map<String, Coordinate> oksUtm = new LinkedHashMap<>();
        for (OksConnectionPoint oks : oksPoints) {
            oksUtm.put(oks.getId(), oksCoordinate(ingest, oks));
        }
        List<Coordinate> focus = new ArrayList<>(oksUtm.values());
        List<Coordinate> nearestPipePoints = nearestPipePoints(geometry, oksUtm.values());
        focus.addAll(nearestPipePoints);
        Envelope area = areaOf(focus, geometry);
        SharedVisibilityRouter router = cachedRouter(ingest, dn, area);
        if (router == null) {
            router = new SharedVisibilityRouter(fullEngine, bundle.getBlockedOutlines(), reference.getRules(), dn);
            router.setFocus(focus);
            if (!router.build(area)) {
                log.info("Граф дворов не собран целиком, остаётся прежний планировщик");
                return null;
            }
            rememberRouter(ingest, dn, area, router);
        }

        List<Anchor> network = networkAnchors(existing, geometry, dn, area, nearestPipePoints);
        if (network.isEmpty()) {
            return null;
        }
        Map<String, OksTarget> targets = new LinkedHashMap<>();
        for (OksConnectionPoint oks : oksPoints) {
            targets.put(oks.getId(), prepareTarget(ingest, oks, oksUtm.get(oks.getId()), dn, fullEngine,
                    groupOf == null || planMode != PlanMode.CLUSTERED ? 0
                            : groupOf.getOrDefault(oks.getId(), 0)));
        }

        List<WorkingTree> trees = new ArrayList<>();
        weightedMemo.clear();
        refineGeometry = geometry;
        refineTrees = trees;
        List<OksConnectionPoint> waiting = new ArrayList<>(oksPoints);
        int maxRounds = oksPoints.size() * (OwnEntryCandidates.LEVEL_WINDOWS.length + 2) + 2;
        int guard = 0;
        Map<Integer, String> failedSignature = new LinkedHashMap<>();
        // AUDIT-24.09 (Claude): режим SEPARATE — «каждый ОКС своей врезкой, где это разумно». Ответвление от
        // уже построенной части допускается, но со штрафом SEPARATE_BRANCH_PENALTY_M к стоимости попытки.
        // Раньше ответвления в этом режиме были запрещены совсем: отдельные вводы не могут пересекать друг
        // друга, и поздние ОКС обходили весь квартал (на конкурсном наборе — ветки по 1–1,5 км вдоль реки
        // при 300–400 м до сети; §2.1 «трасса должна оставаться пространственно обоснованной»), а при полной
        // блокировке оставались без подключения (§2.5, Разъяснение №15).
        double branchPenalty = planMode == PlanMode.SEPARATE ? SEPARATE_BRANCH_PENALTY_M : 0.0;
        while (!waiting.isEmpty() && guard++ < maxRounds) {
            List<Anchor> netFree = dropOccupiedTies(network, trees);
            List<Attempt> attempts = new ArrayList<>();
            for (Map.Entry<Integer, List<OksConnectionPoint>> group : groups(waiting, targets).entrySet()) {
                Integer groupKey = planMode == PlanMode.CLUSTERED ? group.getKey() : null;
                // Группа, у которой с прошлого безуспешного шага не появилось своих ветвей и не вырос
                // уровень захода, заново не пересчитывается: чужие ветви только добавляют запретов.
                String signature = signature(trees, groupKey, group.getValue(), targets);
                if (signature.equals(failedSignature.get(group.getKey()))) {
                    continue;
                }
                List<Anchor> anchors = new ArrayList<>(netFree);
                anchors.addAll(treeAnchors(trees, groupKey));
                List<Attempt> groupAttempts = collectAttempts(router, fullEngine, dn, anchors, trees,
                        group.getValue(), targets, reservations(waiting, targets), branchPenalty);
                groupAttempts.addAll(rayHitAttempts(trees, groupKey, group.getValue(), targets, dn,
                        reservations(waiting, targets), branchPenalty));
                if (groupAttempts.isEmpty()) {
                    failedSignature.put(group.getKey(), signature);
                } else {
                    failedSignature.remove(group.getKey());
                }
                attempts.addAll(groupAttempts);
            }
            attempts.sort(Comparator.comparingInt((Attempt a) -> a.level).thenComparingDouble(a -> a.cost));
            boolean committed = false;
            for (Attempt attempt : attempts) {
                WorkingTree tree = attach(geometry, dn, trees, attempt);
                if (tree != null) {
                    waiting.remove(attempt.target.oks);
                    attempt.target.attachedLevel = attempt.level;
                    committed = true;
                    log.debug("STEINER + ОКС {} уровень захода {} стоимость {} ({})", attempt.target.oks.getId(),
                            attempt.level, Math.round(attempt.cost),
                            attempt.anchor.treeIndex < 0 ? "новая врезка" : "ответвление");
                    break;
                }
            }
            if (committed) {
                continue;
            }
            boolean escalated = false;
            StringBuilder esc = new StringBuilder();
            for (OksConnectionPoint oks : waiting) {
                OksTarget t = targets.get(oks.getId());
                if (t.level < t.maxLevel) {
                    t.level++;
                    escalated = true;
                    esc.append(oks.getId()).append("→").append(t.level).append(' ');
                }
            }
            log.debug("STEINER нет попыток на текущих уровнях, повышение: {}", esc);
            if (!escalated) {
                break;
            }
            failedSignature.clear();
        }

        if (trees.isEmpty()) {
            return null;
        }
        // AUDIT-13 (Claude, 25.09): доводка каждой ветки к ОКС при готовом дереве (см. polishLeaves). Повторный круг —
        // если ветка, мешавшая соседней, сама сдвинулась; круги только уменьшают стоимость, их не больше трёх.
        int polished = 0;
        LeafDnPolish leafDn = new LeafDnPolish(ingest, existing, dn, area, focus);
        for (int round = 0; round < POLISH_MAX_ROUNDS; round++) {
            int improved = polishLeaves(ingest, router, fullEngine, dn, trees, targets, leafDn);
            polished += improved;
            if (improved == 0) {
                break;
            }
        }
        if (polished > 0) {
            log.info("STEINER доводка веток: улучшено {} (из них при фактическом ДУ ветки {})", polished,
                    leafDn.improved);
        }
        List<NewNetworkTree> builtTrees = new ArrayList<>();
        List<NetworkTreeLayout> layouts = new ArrayList<>();
        boolean joint = false;
        for (WorkingTree tree : trees) {
            builtTrees.add(tree.tree);
            layouts.add(tree.layout);
            if (tree.tree.oksNodes().size() > 1) {
                joint = true;
            }
        }
        List<UnconnectedOks> lost = new ArrayList<>();
        for (OksConnectionPoint oks : waiting) {
            lost.add(new UnconnectedOks(oks.getId(), oks.getFlowTph()));
        }
        log.info("STEINER mode={} connected={} lost={} trees={} ids={}", planMode,
                oksPoints.size() - waiting.size(), waiting.size(), trees.size(),
                waiting.stream().map(OksConnectionPoint::getId).reduce((a, b) -> a + "," + b).orElse(""));
        return new NetworkPlan(builtTrees, layouts, lost, joint);
    }

    // ------------------------------------------------------------------ кэш графа

    /*
     * AUDIT-24.09 (Claude): граф видимости района одинаков для всех стратегий M7 одного расчёта
     * (тот же набор, тот же ДУ буферов, тот же охват). Раньше он строился заново на каждую
     * стратегию (~20 с на конкурсном наборе). Кэш — на один последний набор; ссылка на ingest слабая.
     */
    // AUDIT-12 (Claude, 24.09): несколько графов на один набор — по одному на ДУ маршрутизации (проход с ДУ
    // магистрали и уточняющий проход с фактическим ДУ, см. VariantGenerator). С одним слотом стратегии A/B/C,
    // чередуя ДУ, перестраивали бы граф на каждом проходе.
    // AUDIT-13 (Claude, 25.09): плюс графы фактических ДУ веток (LeafDnPolish) — обычно 2–4 ДУ; было 3 слота.
    private static final int ROUTER_CACHE_SLOTS = 8;
    private java.lang.ref.WeakReference<IngestResult> routerIngest = new java.lang.ref.WeakReference<>(null);
    private final java.util.LinkedHashMap<String, SharedVisibilityRouter> routers =
            new java.util.LinkedHashMap<String, SharedVisibilityRouter>(8, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SharedVisibilityRouter> eldest) {
                    return size() > ROUTER_CACHE_SLOTS;
                }
            };

    private static String routerKey(int dn, Envelope area) {
        return dn + "|" + area.getMinX() + "|" + area.getMinY() + "|" + area.getMaxX() + "|" + area.getMaxY();
    }

    private synchronized SharedVisibilityRouter cachedRouter(IngestResult ingest, int dn, Envelope area) {
        if (routerIngest.get() != ingest) {
            return null;
        }
        return routers.get(routerKey(dn, area));
    }

    private synchronized void rememberRouter(IngestResult ingest, int dn, Envelope area, SharedVisibilityRouter r) {
        if (routerIngest.get() != ingest) {
            routers.clear();
            this.routerIngest = new java.lang.ref.WeakReference<>(ingest);
        }
        routers.put(routerKey(dn, area), r);
    }

    private java.lang.ref.WeakReference<IngestResult> entriesIngest = new java.lang.ref.WeakReference<>(null);
    private final Map<String, List<OwnEntryCandidates.Entry>> entriesCache = new java.util.HashMap<>();

    /** Кандидаты захода зависят только от набора, ОКС и ДУ буферов — одинаковы для всех стратегий M7. */
    private synchronized List<OwnEntryCandidates.Entry> cachedEntries(
            IngestResult ingest, String oksId, int dn, java.util.function.Supplier<List<OwnEntryCandidates.Entry>> f) {
        if (entriesIngest.get() != ingest) {
            entriesCache.clear();
            entriesIngest = new java.lang.ref.WeakReference<>(ingest);
        }
        return entriesCache.computeIfAbsent(oksId + "|" + dn, k -> f.get());
    }

    // ------------------------------------------------------------------ цели (§2.2)

    private OksTarget prepareTarget(IngestResult ingest, OksConnectionPoint oks, Coordinate p, int dn,
                                    SpatialConstraintEngine fullEngine, int group) {
        org.locationtech.jts.geom.Point pp = gf.createPoint(p);
        SpatialConstraintBundle approach = routeFinderFactory.bundleForTarget(ingest, dn, pp);
        Geometry own = routeFinderFactory.ownOksGeometry(ingest, pp);
        OksTarget target = new OksTarget(oks, p, approach.getEngine(), own, group);
        if (own == null || own.isEmpty()) {
            target.entries.add(new OwnEntryCandidates.Entry(new Coordinate(p), new Coordinate(p), 0.0, 0));
            target.maxLevel = 0;
            return target;
        }
        RestrictionRule rule = reference.getRules().getRestrictions().get("oks_existing");
        double clearance = (rule == null ? 5.0 : rule.minOffsetM(dn))
                + reference.getGabarits().spec(dn).getWidthM() / 2.0;
        target.entries.addAll(cachedEntries(ingest, oks.getId(), dn,
                () -> OwnEntryCandidates.compute(p, own, clearance, dn, fullEngine, approach.getEngine(), gf)));
        int max = 0;
        for (OwnEntryCandidates.Entry e : target.entries) {
            max = Math.max(max, e.level);
        }
        target.maxLevel = max;
        if (!target.entries.isEmpty()) {
            OwnEntryCandidates.Entry best = target.entries.get(0);
            double len = best.q.distance(best.boundary);
            if (len > 1e-6) {
                double ux = (best.q.x - best.boundary.x) / len;
                double uy = (best.q.y - best.boundary.y) / len;
                target.reserved = gf.createLineString(new Coordinate[] {new Coordinate(p),
                        new Coordinate(best.q.x + ux * 3.0, best.q.y + uy * 3.0)});
            }
        }
        if (target.entries.isEmpty()) {
            log.info("STEINER ОКС {}: нет допустимого прямого захода в свой полигон (§2.2)", oks.getId());
        }
        return target;
    }

    private static Map<Integer, List<OksConnectionPoint>> groups(List<OksConnectionPoint> waiting,
                                                                  Map<String, OksTarget> targets) {
        Map<Integer, List<OksConnectionPoint>> out = new LinkedHashMap<>();
        for (OksConnectionPoint oks : waiting) {
            out.computeIfAbsent(targets.get(oks.getId()).group, k -> new ArrayList<>()).add(oks);
        }
        return out;
    }

    /**
     * AUDIT-24.09 (Claude): резерв финальных прямых ожидающих ОКС (уровень 0). Жадное дерево раньше
     * могло провести ветку к одному ОКС прямо перед ближайшей стеной другого — и тому оставался только
     * заход через дальнюю стену (нарушение §2.2 «от ближайшей границы»).
     */
    private static Map<String, LineString> reservations(List<OksConnectionPoint> waiting,
                                                        Map<String, OksTarget> targets) {
        Map<String, LineString> out = new LinkedHashMap<>();
        for (OksConnectionPoint oks : waiting) {
            OksTarget t = targets.get(oks.getId());
            if (t.reserved != null && t.level == 0) {
                out.put(oks.getId(), t.reserved);
            }
        }
        return out;
    }

    private static boolean crossesReserved(LineString path, String ownerId, Map<String, LineString> reserved) {
        for (Map.Entry<String, LineString> e : reserved.entrySet()) {
            if (e.getKey().equals(ownerId)) {
                continue;
            }
            try {
                if (path.intersects(e.getValue())) {
                    return true;
                }
            } catch (RuntimeException ex) {
                return true;
            }
        }
        return false;
    }

    private List<Attempt> collectAttempts(SharedVisibilityRouter router,
                                          SpatialConstraintEngine fullEngine,
                                          int dn,
                                          List<Anchor> anchors,
                                          List<WorkingTree> trees,
                                          List<OksConnectionPoint> waiting,
                                          Map<String, OksTarget> targets,
                                          Map<String, LineString> reserved,
                                          double branchPenalty) {
        List<SharedVisibilityRouter.Seed> seeds = new ArrayList<>();
        for (int i = 0; i < anchors.size(); i++) {
            Anchor a = anchors.get(i);
            double extra = a.extraCost + (a.treeIndex >= 0 ? branchPenalty : 0.0);
            seeds.add(new SharedVisibilityRouter.Seed(a.point, extra, i, a.heading));
        }
        List<SharedVisibilityRouter.Target> specs = new ArrayList<>();
        List<OksTarget> owners = new ArrayList<>();
        List<OwnEntryCandidates.Entry> entries = new ArrayList<>();
        for (OksConnectionPoint oks : waiting) {
            OksTarget t = targets.get(oks.getId());
            for (OwnEntryCandidates.Entry e : t.entries) {
                if (e.level > t.level) {
                    continue;
                }
                specs.add(new SharedVisibilityRouter.Target(e.q, t.own == null ? null : t.p));
                owners.add(t);
                entries.add(e);
            }
        }
        List<Attempt> attempts = new ArrayList<>();
        if (specs.isEmpty() || seeds.isEmpty()) {
            return attempts;
        }
        List<LineString> built = new ArrayList<>();
        for (WorkingTree tree : trees) {
            for (NewSegment segment : tree.segments) {
                LineString line = tree.layout.segmentLine(segment.getId());
                if (line != null) {
                    built.add(line);
                }
            }
        }
        List<LineString> forbidden = new ArrayList<>(built);
        forbidden.addAll(reserved.values());
        router.setForbidden(forbidden);
        List<SharedVisibilityRouter.RouteHit> hits = router.cheapestTo(seeds, specs);
        // [0] форма, [1] запрет, [2] пересечение, [3] уточнено точек ответвления
        int[] stats = new int[4];
        for (int i = 0; i < hits.size(); i++) {
            SharedVisibilityRouter.RouteHit hit = hits.get(i);
            if (hit == null || hit.getSeedTag() < 0 || hit.getSeedTag() >= anchors.size()) {
                continue;
            }
            Attempt a = attemptFromHit(hit, anchors.get(hit.getSeedTag()), owners.get(i), entries.get(i), fullEngine, dn,
                    trees, reserved, branchPenalty, stats);
            if (a != null) {
                attempts.add(a);
            }
        }
        if (stats[3] > 0) {
            log.debug("STEINER уточнено точек ответвления/врезки: {}", stats[3]);
        }
        if (attempts.isEmpty()) {
            log.info("STEINER skip targets={} hits={} shape={} block={} cross={}", specs.size(),
                    hits.stream().filter(h -> h != null).count(), stats[0], stats[1], stats[2]);
        }
        return attempts;
    }

    private Attempt attemptFromHit(SharedVisibilityRouter.RouteHit hit, Anchor anchor, OksTarget t,
                                   OwnEntryCandidates.Entry entry, SpatialConstraintEngine fullEngine, int dn,
                                   List<WorkingTree> trees, Map<String, LineString> reserved, double branchPenalty) {
        return attemptFromHit(hit, anchor, t, entry, fullEngine, dn, trees, reserved, branchPenalty, new int[4]);
    }

    /**
     * Попытка присоединения по найденному пути графа: починка поворотов, спрямление, уточнение точки ответвления,
     * проверки финального захода §2.2, пересечений/сближений с построенной сетью и резервов; {@code null} — отказ.
     * AUDIT-13 (Claude, 25.09): вынесено из {@link #collectAttempts} без изменения логики — тем же путём проверяется
     * доводка веток ({@link #polishLeaves}).
     */
    private Attempt attemptFromHit(SharedVisibilityRouter.RouteHit hit, Anchor anchorIn, OksTarget t,
                                   OwnEntryCandidates.Entry entry, SpatialConstraintEngine fullEngine, int dn,
                                   List<WorkingTree> trees, Map<String, LineString> reserved, double branchPenalty,
                                   int[] stats) {
        Anchor anchor = anchorIn;
        LineString exterior = lineOf(hit.getPath());
        if (exterior == null) {
            return null;
        }
        LineString repaired = straighten(TurnRepair.repair(exterior, fullEngine, dn, t.own), anchor, t,
                fullEngine, dn, trees, reserved);
        if (!turnsOk(repaired) || !headingOk(anchor, repaired)) {
            stats[0]++;
            return null;
        }
        // AUDIT-12 (Claude, 24.09): точка ответвления/врезки уточняется вдоль несущего участка (см. refineBranch).
        Refined refined = refineBranch(anchor, repaired, t, fullEngine, dn, trees, reserved);
        if (refined != null) {
            anchor = refined.anchor;
            repaired = refined.line;
            stats[3]++;
        }
        List<Coordinate> full = coordinatesOf(repaired);
        if (t.own != null) {
            // AUDIT-13 (Claude, 25.09): вершина Q с изломом меньше MICRO_KINK_DEG перед финальным прямым заходом —
            // «необоснованный мелкий излом» (§2.1): финальный прямой участок §2.2 начинается на предыдущей вершине
            // (вплоть до самой точки ответвления/врезки), если он допустим (набор без своего полигона, не глубже в
            // здании, поворот ≤ 90°, в точке ответвления — от питающего участка). Пересечения со строящейся сетью
            // проверяются ниже для всей трассы.
            while (full.size() >= 2) {
                int n = full.size();
                Coordinate qv = full.get(n - 1);
                Coordinate pv = full.get(n - 2);
                if (TurnRepair.deviationDeg(pv, qv, t.p) >= MICRO_KINK_DEG) {
                    break;
                }
                Coordinate before = n >= 3 ? full.get(n - 3) : anchor.heading;
                if (before != null
                        && TurnRepair.deviationDeg(before, pv, t.p) > TurnRepair.MAX_TURN_DEG + 1e-6) {
                    break;
                }
                double[] leg2 = finalLeg(pv, t, dn);
                if (leg2[0] > 0.5 || leg2[1] > entry.insideM + 0.3) {
                    break;
                }
                full.remove(n - 1);
            }
        }
        // repaired — часть трассы до точки Q (null, если финальный прямой заход начинается в самой точке ответвления)
        repaired = full.size() >= 2 ? lineOf(full) : null;
        Coordinate q = full.get(full.size() - 1);
        double legLength = 0.0;
        if (t.own != null) {
            if (repaired != null && insideLength(repaired, t) > 0.05) {
                stats[0]++;
                return null;
            }
            Coordinate prev = full.size() >= 2 ? full.get(full.size() - 2) : anchor.heading;
            if (prev != null && TurnRepair.deviationDeg(prev, q, t.p) > TurnRepair.MAX_TURN_DEG + 1e-6) {
                stats[0]++;
                return null;
            }
            double[] leg = finalLeg(q, t, dn);
            if (leg[0] > 0.5) {
                stats[1]++;
                return null;
            }
            if (leg[1] > entry.insideM + 0.3) {
                stats[0]++;
                return null;
            }
            legLength = leg[2];
            if (q.distance(t.p) > 0.05) {
                full.add(new Coordinate(t.p));
            }
        } else if (repaired == null) {
            stats[0]++;
            return null;
        }
        LineString fullLine = lineOf(full);
        if (fullLine == null || crosses(fullLine, trees, anchor.point)
                || crossesReserved(fullLine, t.oks.getId(), reserved)) {
            stats[2]++;
            if (log.isDebugEnabled() && fullLine != null) {
                log.debug("STEINER отказ ОКС {}: путь {} пересекает {}; якорь {} дерево {}",
                        t.oks.getId(), fullLine, crossWhy(fullLine, trees, anchor.point), anchor.point,
                        anchor.treeIndex);
            }
            return null;
        }
        // AUDIT-12 (Claude, 24.09): стоимость попытки — по фактической (спрямлённой, уточнённой) трассе:
        // доплата якоря + длина × Kспец + финальный заход. Раньше бралась стоимость пути графа до
        // спрямления, и жадный выбор сравнивал попытки по уже неактуальной длине.
        double pathCost = repaired == null ? 0.0 : pathCost(repaired, fullEngine, dn);
        double extra = anchor.extraCost + (anchor.treeIndex >= 0 ? branchPenalty : 0.0);
        double cost = Double.isNaN(pathCost) ? hit.getCost() + legLength : extra + pathCost + legLength;
        return new Attempt(t, anchor, full, cost, entry.level);
    }

    /**
     * AUDIT-24.09 (Claude). §2.1 приложения: «трасса не должна содержать необоснованных мелких изломов,
     * зигзагов и ступенчатых фрагментов». Путь по графу видимости проходит через вершины-«юбки» в 2 м от
     * зон и через точки выхода у семени — отсюда короткие звенья 1–2 м с поворотом и почти прямые
     * (0–5°) изломы. Жадное спрямление: из текущей вершины берётся самая дальняя вершина пути, прямой
     * отрезок до которой допустим по всем тем же правилам (полный набор ограничений, повороты ≤ 90° с
     * соседями и направлением питающего участка, не заходит в свой полигон до финального участка, не
     * пересекает построенную сеть и резервы захода других ОКС, стоимость с Kспец не растёт). Концы пути
     * (якорь и точка Q финального захода) не меняются.
     */
    private LineString straighten(LineString path, Anchor anchor, OksTarget t, SpatialConstraintEngine engine,
                                  int dn, List<WorkingTree> trees, Map<String, LineString> reserved) {
        if (path == null || path.getNumPoints() < 3) {
            return path;
        }
        List<Coordinate> pts = coordinatesOf(path);
        int n = pts.size();
        double[] prefixCost = new double[n];
        for (int k = 1; k < n; k++) {
            double c;
            try {
                c = weightedLength(pts.get(k - 1), pts.get(k), engine, dn);
            } catch (RuntimeException ex) {
                return path;
            }
            // звено выхода из зоны у семени движок «блокирует», но маршрутизатор его допустил — берём длину
            prefixCost[k] = prefixCost[k - 1] + (Double.isNaN(c) ? pts.get(k - 1).distance(pts.get(k)) : c);
        }
        List<Coordinate> out = new ArrayList<>();
        out.add(pts.get(0));
        int i = 0;
        while (i < n - 1) {
            int next = i + 1;
            Coordinate prev = out.size() >= 2 ? out.get(out.size() - 2) : anchor.heading;
            for (int j = n - 1; j > i + 1; j--) {
                if (shortcutOk(pts, i, j, prev, prefixCost, anchor, t, engine, dn, trees, reserved)) {
                    next = j;
                    break;
                }
            }
            out.add(pts.get(next));
            i = next;
        }
        if (out.size() == n) {
            return path;
        }
        LineString pulled = lineOf(out);
        return pulled == null || !turnsOk(pulled) || !headingOk(anchor, pulled) ? path : pulled;
    }

    private boolean shortcutOk(List<Coordinate> pts, int i, int j, Coordinate prev, double[] prefixCost,
                               Anchor anchor, OksTarget t, SpatialConstraintEngine engine, int dn,
                               List<WorkingTree> trees, Map<String, LineString> reserved) {
        Coordinate a = pts.get(i);
        Coordinate b = pts.get(j);
        if (a.distance(b) < 0.05) {
            return false;
        }
        if (prev != null && TurnRepair.deviationDeg(prev, a, b) > TurnRepair.MAX_TURN_DEG + 1e-6) {
            return false;
        }
        Coordinate after = j < pts.size() - 1 ? pts.get(j + 1) : (t.own == null ? null : t.p);
        if (after != null && after.distance(b) > 0.05
                && TurnRepair.deviationDeg(a, b, after) > TurnRepair.MAX_TURN_DEG + 1e-6) {
            return false;
        }
        double cost;
        try {
            cost = weightedLength(a, b, engine, dn);
        } catch (RuntimeException ex) {
            return false;
        }
        if (Double.isNaN(cost) || cost > prefixCost[j] - prefixCost[i] + 1e-6) {
            return false;
        }
        LineString seg = gf.createLineString(new Coordinate[] {new Coordinate(a), new Coordinate(b)});
        if (t.own != null && insideLength(seg, t) > 0.05) {
            return false;
        }
        return !crosses(seg, trees, anchor.point) && !crossesReserved(seg, t.oks.getId(), reserved);
    }

    // ------------------------------------------------------------------ уточнение точки ответвления

    /** Контекст текущего плана для уточнения врезок в трубу: геометрия сети и свободные камеры (правило 10 м). */
    private ExistingNetworkGeometry refineGeometry;
    private List<Coordinate> refineEligibleChambers = Collections.emptyList();
    private double refineTieRadius = 10.0;
    private List<WorkingTree> refineTrees = Collections.emptyList();

    private static final double[] SLIDE_OFFSETS_M = {-15.0, -10.0, -6.0, -3.0, -1.5, 1.5, 3.0, 6.0, 10.0, 15.0};
    private static final double[] FOOT_OFFSETS_M = {0.0, -2.0, 2.0, -5.0, 5.0};
    private static final double REFINE_MIN_GAIN_M = 0.5;
    /** Излом меньше этого угла в вершине сразу за точкой ответвления считается необоснованным, °. */
    private static final double MICRO_KINK_DEG = 2.0;
    private static final double TREE_END_MARGIN_M = 1.5;

    private static final class Refined {
        private final Anchor anchor;
        private final LineString line;

        private Refined(Anchor anchor, LineString line) {
            this.anchor = anchor;
            this.line = line;
        }
    }

    /**
     * AUDIT-12 (Claude, 24.09). Качество трассы (ТЗ разд. 8: «разумность маршрутов, количество поворотов,
     * отсутствие ... неоправданных обходов»; §2.1 — без необоснованных изломов). Якоря на построенной сети
     * берутся с шагом {@value #TREE_SAMPLE_M} м, на трубах — {@value #PIPE_SAMPLE_M} м, поэтому камера
     * ответвления (и новая камера врезки) оказывалась «на сетке» до 11–20 м от удачного места: ветка шла
     * назад крюком (разворот перед ОКС 14 на конкурсном наборе — 51 м пути при 16 м до точки), а поворот в
     * камере ответвления упирался в предел 90°. Здесь точка ответвления сдвигается вдоль несущего участка
     * (или трубы): кандидаты — проекция первых вершин пути на несущую линию и сдвиги ±1,5…15 м. Новая
     * прямая «точка ответвления → вершина пути» проверяется по тем же правилам, что и весь путь: полный
     * набор ограничений (отступы, спецпроходы, Kспец), поворот в камере ≤ 90° от питающего участка
     * (Разъяснение №5), поворот в вершине ≤ 90°, не заходит в свой полигон до финального участка (§2.2),
     * не пересекает построенную сеть и резервы захода других ОКС (§2.1), для трубы — правило 10 м к
     * свободной камере (§2.4) и концы трубы. Сдвиг принимается, только если трасса короче (с Kспец) не
     * меньше чем на {@value #REFINE_MIN_GAIN_M} м.
     */
    private Refined refineBranch(Anchor anchor, LineString path, OksTarget t, SpatialConstraintEngine engine, int dn,
                                 List<WorkingTree> trees, Map<String, LineString> reserved) {
        if (anchor == null || path == null || path.getNumPoints() < 2 || anchor.atNodeId != null) {
            return null;
        }
        LineString host;
        boolean onPipe;
        String pipeId = null;
        if (anchor.treeIndex >= 0 && anchor.alongSegmentId != null) {
            if (anchor.treeIndex >= trees.size()) {
                return null;
            }
            WorkingTree tree = trees.get(anchor.treeIndex);
            NewSegment hostSeg = null;
            for (NewSegment s : tree.segments) {
                if (s.getId().equals(anchor.alongSegmentId)) {
                    hostSeg = s;
                    break;
                }
            }
            if (hostSeg == null || hostSeg.getLayingMethod() == LayingMethod.SPECIAL) {
                return null;
            }
            host = tree.layout.segmentLine(hostSeg.getId());
            onPipe = false;
        } else if (anchor.treeIndex < 0 && anchor.tie != null
                && anchor.tie.getExistingObjectType() == ru.heatnet.calc.model.ExistingObjectType.HEAT_NETWORK
                && refineGeometry != null) {
            pipeId = anchor.tie.getExistingObjectId();
            host = refineGeometry.getSegmentLines().get(pipeId);
            onPipe = true;
        } else {
            return null;
        }
        if (host == null || host.getNumPoints() < 2) {
            return null;
        }
        List<Coordinate> pts = coordinatesOf(path);
        int n = pts.size();
        double[] prefix = new double[n];
        for (int k = 1; k < n; k++) {
            double c;
            try {
                c = weightedLength(pts.get(k - 1), pts.get(k), engine, dn);
            } catch (RuntimeException ex) {
                return null;
            }
            prefix[k] = prefix[k - 1] + (Double.isNaN(c) ? pts.get(k - 1).distance(pts.get(k)) : c);
        }
        double hostLen = RouteGeometryUtils.accumulatedLength(host);
        double margin = onPipe ? PIPE_END_MARGIN_M + 0.1 : TREE_END_MARGIN_M;
        if (hostLen <= 2 * margin) {
            return null;
        }
        double anchorPos = RouteGeometryUtils.positionAlong(host, anchor.point);
        double bestTotal = prefix[n - 1] - REFINE_MIN_GAIN_M;
        Coordinate bestB = null;
        double bestPos = 0.0;
        int bestK = -1;
        for (int k = 1; k <= Math.min(3, n - 1); k++) {
            Coordinate v = pts.get(k);
            double rest = prefix[n - 1] - prefix[k];
            Coordinate next = k + 1 < n ? pts.get(k + 1) : (t.own == null ? null : t.p);
            List<Double> positions = new ArrayList<>();
            double foot = RouteGeometryUtils.positionAlong(host, v);
            for (double d : FOOT_OFFSETS_M) {
                positions.add(foot + d);
            }
            for (double d : SLIDE_OFFSETS_M) {
                positions.add(anchorPos + d);
            }
            for (double pos : positions) {
                if (pos < margin || pos > hostLen - margin) {
                    continue;
                }
                Coordinate b = RouteGeometryUtils.pointAtDistance(host, pos);
                if (b.distance(v) < 0.5 || b.distance(anchor.point) < 0.05 && k == 1) {
                    continue;
                }
                double lower = b.distance(v) + rest;
                if (lower >= bestTotal) {
                    continue; // даже без Kспец не лучше
                }
                if (!onPipe) {
                    Coordinate heading = headingAt(host, pos, b);
                    if (TurnRepair.deviationDeg(heading, b, v) > TurnRepair.MAX_TURN_DEG + 1e-6) {
                        continue;
                    }
                }
                if (next != null && next.distance(v) > 0.05
                        && TurnRepair.deviationDeg(b, v, next) > TurnRepair.MAX_TURN_DEG + 1e-6) {
                    continue;
                }
                if (onPipe) {
                    if (nearAny(b, refineEligibleChambers, refineTieRadius) || tieOccupied(b)) {
                        continue;
                    }
                }
                double link;
                try {
                    link = weightedLength(b, v, engine, dn);
                } catch (RuntimeException ex) {
                    continue;
                }
                if (Double.isNaN(link) || link + rest >= bestTotal) {
                    continue;
                }
                LineString seg = gf.createLineString(new Coordinate[] {new Coordinate(b), new Coordinate(v)});
                if (t.own != null && insideLength(seg, t) > 0.05) {
                    continue;
                }
                if (crosses(seg, trees, b) || crossesReserved(seg, t.oks.getId(), reserved)) {
                    continue;
                }
                bestTotal = link + rest;
                bestB = b;
                bestPos = pos;
                bestK = k;
            }
        }
        if (bestB == null) {
            return null;
        }
        List<Coordinate> out = new ArrayList<>();
        out.add(new Coordinate(bestB));
        for (int k = bestK; k < n; k++) {
            out.add(new Coordinate(pts.get(k)));
        }
        // AUDIT-12 (Claude, 24.09): новая точка ответвления может оказаться почти на одной прямой с двумя
        // следующими вершинами — вершина с изломом < 2° «необоснованная» (§2.1: без мелких изломов). Она
        // убирается, если прямая b→w допустима по тем же правилам и не дороже (с Kспец) пути через вершину.
        Coordinate heading0 = onPipe ? null : headingAt(host, bestPos, bestB);
        while (out.size() >= 3) {
            Coordinate b0 = out.get(0);
            Coordinate v = out.get(1);
            Coordinate w = out.get(2);
            if (TurnRepair.deviationDeg(b0, v, w) >= MICRO_KINK_DEG) {
                break;
            }
            Coordinate wNext = out.size() > 3 ? out.get(3) : (t.own == null ? null : t.p);
            if (heading0 != null && TurnRepair.deviationDeg(heading0, b0, w) > TurnRepair.MAX_TURN_DEG + 1e-6) {
                break;
            }
            if (wNext != null && wNext.distance(w) > 0.05
                    && TurnRepair.deviationDeg(b0, w, wNext) > TurnRepair.MAX_TURN_DEG + 1e-6) {
                break;
            }
            double direct;
            double via;
            try {
                direct = weightedLength(b0, w, engine, dn);
                via = weightedLength(b0, v, engine, dn) + weightedLength(v, w, engine, dn);
            } catch (RuntimeException ex) {
                break;
            }
            if (Double.isNaN(direct) || Double.isNaN(via) || direct > via + 1e-6) {
                break;
            }
            LineString seg = gf.createLineString(new Coordinate[] {new Coordinate(b0), new Coordinate(w)});
            if (t.own != null && insideLength(seg, t) > 0.05) {
                break;
            }
            if (crosses(seg, trees, b0) || crossesReserved(seg, t.oks.getId(), reserved)) {
                break;
            }
            out.remove(1);
        }
        LineString line = lineOf(out);
        if (line == null || !turnsOk(line)) {
            return null;
        }
        Anchor moved;
        if (onPipe) {
            ExistingNetworkGeometry.PipeProjection proj =
                    refineGeometry.projectOnSegment(pipeId, bestB, PIPE_END_MARGIN_M);
            if (proj == null) {
                return null;
            }
            String tieId = "tie_p_" + pipeId + "_" + Math.round(proj.getDistanceFromUpstreamEndM()) + "_" + (++seq);
            TieInPoint tie = TieInPoint.intoPipe(tieId, pipeId, proj.getDistanceFromUpstreamEndM(), "nch_" + tieId);
            moved = Anchor.network(proj.getPoint(), anchor.extraCost, tie);
            List<Coordinate> snapped = new ArrayList<>(out);
            snapped.set(0, new Coordinate(proj.getPoint()));
            line = lineOf(snapped);
            if (line == null) {
                return null;
            }
        } else {
            moved = Anchor.onSegment(bestB, anchor.extraCost, anchor.treeIndex, anchor.alongSegmentId,
                    headingAt(host, bestPos, bestB));
            if (!headingOk(moved, line)) {
                return null;
            }
        }
        return new Refined(moved, line);
    }

    private boolean tieOccupied(Coordinate b) {
        for (WorkingTree tree : refineTrees) {
            Coordinate placed = tree.layout.nodeCoordinate(tree.tie.getId());
            if (placed != null && placed.distance(b) <= TIE_CLEARANCE_M) {
                return true;
            }
        }
        return false;
    }

    /** Длина × Kспец пути; NaN, если какое-либо звено нарушает ограничения (кроме выхода из зоны у семени). */
    private double pathCost(LineString path, SpatialConstraintEngine engine, int dn) {
        if (path == null) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (int i = 1; i < path.getNumPoints(); i++) {
            Coordinate a = path.getCoordinateN(i - 1);
            Coordinate b = path.getCoordinateN(i);
            double c;
            try {
                c = weightedLength(a, b, engine, dn);
            } catch (RuntimeException ex) {
                return Double.NaN;
            }
            sum += Double.isNaN(c) ? a.distance(b) : c;
        }
        return sum;
    }

    /** Длина с Kспец на спецучастках звена (max на наложении); NaN, если звено нарушает ограничения. */
    private double weightedLength(Coordinate a, Coordinate b, SpatialConstraintEngine engine, int dn) {
        // AUDIT-12 (Claude, 24.09): мемо на время одного плана. Спрямление и уточнение ответвлений на каждом
        // шаге дерева заново проверяют одни и те же отрезки (пути к ожидающим ОКС пересчитываются каждый
        // раунд) — проверка движком (оверлеи JTS с полигонами дорог и буферами) была основным расходом.
        SegKey key = new SegKey(a, b, engine, dn);
        Double memo = weightedMemo.get(key);
        if (memo != null) {
            return memo;
        }
        LineString seg = gf.createLineString(new Coordinate[] {new Coordinate(a), new Coordinate(b)});
        double value;
        if (engine.isSegmentBlocked(seg, dn)) {
            value = Double.NaN;
        } else {
            // AUDIT-13 (Claude, 25.09): Kспец — только на спецучастке звена (как в смете M6). Раньше длина всего
            // звена умножалась на наибольший Kспец, и прямая через дорогу/газ/теплосеть «дорожала» — спрямление и
            // уточнение ответвления отвергали её в пользу ломаной (см. SpecialCost).
            value = ru.heatnet.routing.SpecialCost.weightedLength(seg, engine.extractSpecialSections(seg, dn));
        }
        if (weightedMemo.size() > 400_000) {
            weightedMemo.clear();
        }
        weightedMemo.put(key, value);
        return value;
    }

    private final Map<SegKey, Double> weightedMemo = new java.util.HashMap<>();

    /** Неупорядоченная пара точек + движок + ДУ. */
    private static final class SegKey {
        private final double x1;
        private final double y1;
        private final double x2;
        private final double y2;
        private final Object engine;
        private final int dn;

        SegKey(Coordinate a, Coordinate b, Object engine, int dn) {
            boolean swap = a.x > b.x || (a.x == b.x && a.y > b.y);
            this.x1 = swap ? b.x : a.x;
            this.y1 = swap ? b.y : a.y;
            this.x2 = swap ? a.x : b.x;
            this.y2 = swap ? a.y : b.y;
            this.engine = engine;
            this.dn = dn;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof SegKey)) {
                return false;
            }
            SegKey k = (SegKey) o;
            return x1 == k.x1 && y1 == k.y1 && x2 == k.x2 && y2 == k.y2 && engine == k.engine && dn == k.dn;
        }

        @Override
        public int hashCode() {
            long h = Double.doubleToLongBits(x1);
            h = h * 31 + Double.doubleToLongBits(y1);
            h = h * 31 + Double.doubleToLongBits(x2);
            h = h * 31 + Double.doubleToLongBits(y2);
            h = h * 31 + System.identityHashCode(engine);
            h = h * 31 + dn;
            return (int) (h ^ (h >>> 32));
        }
    }

    private static String signature(List<WorkingTree> trees, Integer group, List<OksConnectionPoint> waiting,
                                    Map<String, OksTarget> targets) {
        int own = 0;
        for (WorkingTree t : trees) {
            if (group == null || t.group == group) {
                own += t.segments.size();
            }
        }
        StringBuilder sb = new StringBuilder().append(own).append('|');
        for (OksConnectionPoint oks : waiting) {
            sb.append(oks.getId()).append(':').append(targets.get(oks.getId()).level).append(',');
        }
        return sb.toString();
    }

    /**
     * Ветвь, совпадающая с финальной прямой захода: если уже построенный участок сети пересекает луч
     * «точка ОКС → ближайшая стена → наружу» за пределами зоны отступа, от точки пересечения ставится
     * камера и прямой участок до ОКС. Без этого соседняя ветвь, прошедшая перед ближайшей стеной,
     * отрезала заход с неё, и ОКС получал заход через дальнюю стену.
     */
    private List<Attempt> rayHitAttempts(List<WorkingTree> trees, Integer group, List<OksConnectionPoint> waiting,
                                         Map<String, OksTarget> targets, int dn, Map<String, LineString> reserved,
                                         double branchPenalty) {
        List<Attempt> out = new ArrayList<>();
        if (trees.isEmpty()) {
            return out;
        }
        double rubPerM = Math.max(1.0, reference.getDiameters().spec(100).getNewCostRubPerM());
        double branchExtra = new ChamberCostScale(reference.getRules()).costFor(100) / rubPerM + branchPenalty;
        for (OksConnectionPoint oks : waiting) {
            OksTarget t = targets.get(oks.getId());
            if (t.own == null) {
                continue;
            }
            for (OwnEntryCandidates.Entry e : t.entries) {
                if (e.level > t.level) {
                    continue;
                }
                double len = e.q.distance(e.boundary);
                if (len < 1e-6) {
                    continue;
                }
                double ux = (e.q.x - e.boundary.x) / len;
                double uy = (e.q.y - e.boundary.y) / len;
                LineString ray = gf.createLineString(new Coordinate[] {new Coordinate(e.q),
                        new Coordinate(e.q.x + ux * 40.0, e.q.y + uy * 40.0)});
                for (int ti = 0; ti < trees.size(); ti++) {
                    WorkingTree tree = trees.get(ti);
                    if (group != null && tree.group != group) {
                        continue;
                    }
                    for (NewSegment segment : tree.segments) {
                        if (segment.getLayingMethod() == LayingMethod.SPECIAL) {
                            continue;
                        }
                        LineString host = tree.layout.segmentLine(segment.getId());
                        if (host == null || !host.intersects(ray)) {
                            continue;
                        }
                        Geometry hit = host.intersection(ray);
                        for (Coordinate x : hit.getCoordinates()) {
                            Attempt a = rayAttempt(t, e, tree, ti, segment, host, x, trees, dn, branchExtra);
                            if (a != null && !crossesReserved(lineOf(a.path), t.oks.getId(), reserved)) {
                                out.add(a);
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private Attempt rayAttempt(OksTarget t, OwnEntryCandidates.Entry e, WorkingTree tree, int treeIndex,
                               NewSegment segment, LineString host, Coordinate x, List<WorkingTree> trees,
                               int dn, double branchExtra) {
        double pos = RouteGeometryUtils.positionAlong(host, x);
        double len = RouteGeometryUtils.accumulatedLength(host);
        if (pos <= 1.0 || len - pos <= 1.0) {
            return null;
        }
        Coordinate heading = headingAt(host, pos, x);
        if (TurnRepair.deviationDeg(heading, x, t.p) > TurnRepair.MAX_TURN_DEG + 1e-6) {
            return null;
        }
        LineString leg = gf.createLineString(new Coordinate[] {new Coordinate(x), new Coordinate(t.p)});
        try {
            if (t.approach.isSegmentBlocked(leg, dn)) {
                return null;
            }
        } catch (RuntimeException ex) {
            return null;
        }
        if (insideLength(leg, t) > e.insideM + 0.3 || crosses(leg, trees, x)) {
            return null;
        }
        List<Coordinate> path = new ArrayList<>();
        path.add(new Coordinate(x));
        path.add(new Coordinate(t.p));
        Anchor anchor = Anchor.onSegment(new Coordinate(x), branchExtra, treeIndex, segment.getId(), heading);
        return new Attempt(t, anchor, path, branchExtra + leg.getLength(), e.level);
    }

    /**
     * Точка «позади» {@code at} по направлению звена питающего участка, на котором лежит {@code at}.
     * Раньше бралась точка за 1 м вдоль линии — если в пределах этого метра была вершина, направление
     * бралось с предыдущего звена и поворот в камере ответвления мог выйти > 90°.
     */
    private static Coordinate headingAt(LineString line, double pos, Coordinate at) {
        double acc = 0.0;
        for (int i = 0; i + 1 < line.getNumPoints(); i++) {
            Coordinate a = line.getCoordinateN(i);
            Coordinate b = line.getCoordinateN(i + 1);
            double len = a.distance(b);
            if (acc + len >= pos - 1e-9 || i + 2 == line.getNumPoints()) {
                if (len < 1e-9) {
                    return a;
                }
                double ux = (b.x - a.x) / len;
                double uy = (b.y - a.y) / len;
                return new Coordinate(at.x - ux * HEADING_BACK_M, at.y - uy * HEADING_BACK_M);
            }
            acc += len;
        }
        return line.getCoordinateN(0);
    }

    /** Первое звено новой ветки не отклоняется от питающего участка больше чем на 90° (§2.1). */
    private static boolean headingOk(Anchor anchor, LineString path) {
        if (anchor.heading == null || path == null || path.getNumPoints() < 2) {
            return true;
        }
        return TurnRepair.deviationDeg(anchor.heading, path.getCoordinateN(0), path.getCoordinateN(1))
                <= TurnRepair.MAX_TURN_DEG + 1e-6;
    }

    // ------------------------------------------------------------------ доводка веток (AUDIT-13)

    /** Доводка принимается, если ветка дешевле (м × Kспец) хотя бы на столько, м. */
    private static final double POLISH_MIN_GAIN_M = 0.5;
    /** Кругов доводки не больше. */
    private static final int POLISH_MAX_ROUNDS = 3;

    /**
     * AUDIT-13 (Claude, 25.09). Доводка веток к ОКС при готовом дереве («кривая труба дешевле прямой?»).
     *
     * <p>Жадное дерево присоединяет ОКС по одному; в момент присоединения ветка обходит резервы захода ещё не
     * подключённых ОКС и выбирает заход строго с окном +0,2 м к ближайшей доступной стене (уровень 0). Когда дерево
     * готово, каждая ветка «точка ответвления → ОКС» (цепочка участков через технические узлы) заново трассируется из
     * ТОЙ ЖЕ точки ответвления (камеры или врезки) — без резервов, с заходами через ту же ближайшую стену
     * ({@link OwnEntryCandidates#computeRelaxed}), по тем же правилам, что и основной поиск: полный набор ограничений,
     * поворот ≤ 90° (в том числе в камере ответвления от питающего участка), финальный прямой заход §2.2, без
     * пересечения и сближения с остальной сетью. Новая ветка принимается, только если она дешевле старой (длина ×
     * Kспец) не меньше чем на {@value #POLISH_MIN_GAIN_M} м при том же или лучшем уровне захода; иначе дерево
     * возвращается к снимку. Доводка не меняет ни топологию дерева, ни точки ответвления — только геометрию ветки,
     * поэтому расходы, ДУ и камеры (M5/M6) сохраняют смысл, а стоимость варианта может только уменьшиться.</p>
     *
     * <p>Пример — ОКС 2 конкурсного набора: ближайшая стена смотрит во двор Г-образного корпуса; жадный шаг вёл
     * ветку петлёй-«пятиугольником» 78 м через единственный луч, касающийся угла ниши, хотя через ту же стену под
     * углом 6,5° заход длиннее всего на 0,25 м, а ветка короче на 15 м.</p>
     *
     * @return число улучшенных веток
     */
    private int polishLeaves(IngestResult ingest, SharedVisibilityRouter router, SpatialConstraintEngine fullEngine,
                             int dn, List<WorkingTree> trees, Map<String, OksTarget> targets, LeafDnPolish leafDn) {
        int improved = 0;
        for (int ti = 0; ti < trees.size(); ti++) {
            WorkingTree tree = trees.get(ti);
            List<NewNode> oksNodes = new ArrayList<>();
            for (NewNode node : tree.nodes) {
                if (node.getKind() == NodeKind.OKS_CONNECTION) {
                    oksNodes.add(node);
                }
            }
            for (NewNode oksNode : oksNodes) {
                OksTarget t = targets.get(oksNode.getOksId());
                if (t == null || t.attachedLevel < 0) {
                    continue;
                }
                try {
                    // Сначала — как раньше, в графе плана. AUDIT-13 (Claude, 25.09): затем, если фактический ДУ ветки
                    // (M5) меньше ДУ графа, — в графе ДУ ветки (см. LeafDnPolish), от уже доведённой ветки. Графы разных
                    // ДУ имеют разные вершины, и ни один из двух поисков не гарантирует путь не хуже другого; каждая
                    // замена принимается только если ветка дешевле, так что остаётся лучшая.
                    boolean ok = polishLeaf(ingest, router, fullEngine, dn, trees, ti, oksNode.getId(), t, null);
                    List<NewSegment> chain = leafChain(tree, oksNode.getId());
                    Map<String, Integer> dns = chain == null ? null : leafDn.diameters(tree);
                    int chainDn = dns == null ? 0 : leafDn.chainDn(chain, dns);
                    LeafDnPolish.Context ctx = chainDn > 0 && chainDn < dn ? leafDn.context(chainDn) : null;
                    OksTarget lt = ctx == null ? null : leafDn.target(t, chainDn, ctx);
                    if (lt != null) {
                        // Уровень захода прежней ветки — относительно заходов ДУ ветки: при ДУ графа «с запасом»
                        // ближайшая стена могла быть недоступна, и заход «уровня 0» того ДУ при ДУ ветки глубже
                        // ближайшего (§2.2). Тогда ветка к ближайшей стене принимается, даже если она длиннее.
                        int levelAtLeaf = leafDn.levelOfCurrent(tree, oksNode.getId(), lt);
                        if (levelAtLeaf >= 0) {
                            lt.attachedLevel = levelAtLeaf;
                        }
                        final Map<String, Integer> before = dns;
                        final int limit = chainDn;
                        boolean leafOk = polishLeaf(ingest, ctx.router, ctx.engine, chainDn, trees, ti,
                                oksNode.getId(), lt, w -> leafDn.noDnIncrease(w, before, limit));
                        t.attachedLevel = Math.min(t.attachedLevel, lt.attachedLevel);
                        if (leafOk) {
                            leafDn.improved++;
                            ok = true;
                        }
                    }
                    if (ok) {
                        improved++;
                    }
                } catch (RuntimeException ex) {
                    log.debug("Доводка ветки к ОКС {} пропущена: {}", t.oks.getId(), ex.toString());
                }
            }
        }
        return improved;
    }

    /** Цепочка участков от ОКС вверх до камеры или врезки (через технические узлы); {@code null} — нет цепочки. */
    private static List<NewSegment> leafChain(WorkingTree tree, String oksNodeId) {
        List<NewSegment> chain = new ArrayList<>();
        String nodeId = oksNodeId;
        int guard = 0;
        while (guard++ < 256) {
            NewSegment in = null;
            for (NewSegment s : tree.segments) {
                if (s.getToNodeId().equals(nodeId)) {
                    in = s;
                    break;
                }
            }
            if (in == null) {
                return null;
            }
            chain.add(0, in);
            NewNode from = tree.node(in.getFromNodeId());
            if (from == null) {
                return null;
            }
            if (from.getKind() != NodeKind.TECHNICAL_NODE) {
                break;
            }
            nodeId = from.getId();
        }
        return chain.isEmpty() ? null : chain;
    }

    /**
     * @param dnGuard проверка дерева после замены ветки (AUDIT-13, доводка при фактическом ДУ ветки: ни один участок не
     *                получает ДУ больше прежнего); {@code null} — без проверки
     */
    private boolean polishLeaf(IngestResult ingest, SharedVisibilityRouter router, SpatialConstraintEngine fullEngine,
                               int dn, List<WorkingTree> trees, int treeIndex, String oksNodeId, OksTarget t,
                               java.util.function.Predicate<WorkingTree> dnGuard) {
        WorkingTree tree = trees.get(treeIndex);
        List<NewSegment> chain = leafChain(tree, oksNodeId);
        if (chain == null) {
            return false;
        }
        String branchId = chain.get(0).getFromNodeId();
        NewNode branch = tree.node(branchId);
        Coordinate branchPoint = tree.layout.nodeCoordinate(branchId);
        if (branch == null || branchPoint == null
                || (branch.getKind() != NodeKind.NEW_CHAMBER && branch.getKind() != NodeKind.TIE_IN)) {
            return false;
        }
        Coordinate heading = null;
        if (branch.getKind() == NodeKind.NEW_CHAMBER) {
            for (NewSegment s : tree.segments) {
                if (s.getToNodeId().equals(branchId)) {
                    LineString feed = tree.layout.segmentLine(s.getId());
                    if (feed == null || feed.getNumPoints() < 2) {
                        return false;
                    }
                    heading = feed.getCoordinateN(feed.getNumPoints() - 2);
                    break;
                }
            }
            if (heading == null) {
                return false;
            }
        }
        double oldCost = 0.0;
        for (NewSegment s : chain) {
            oldCost += s.getLengthM() * (s.getLayingMethod() == LayingMethod.SPECIAL ? s.getKSpec() : 1.0);
        }

        // кандидаты захода: та же ближайшая стена (уровень 0) + заходы уровня, с которым ОКС присоединён
        List<OwnEntryCandidates.Entry> entries = new ArrayList<>(relaxedEntries(ingest, t, dn, fullEngine));
        for (OwnEntryCandidates.Entry e : t.entries) {
            if (e.level <= t.attachedLevel) {
                entries.add(e);
            }
        }
        if (entries.isEmpty()) {
            return false;
        }

        WorkingTree.Snapshot snapshot = tree.snapshot();
        // убрать ветку из дерева
        for (NewSegment s : chain) {
            tree.segments.remove(s);
            tree.layout.removeSegment(s.getId());
            tree.addDegree(s.getFromNodeId(), -1);
            tree.addDegree(s.getToNodeId(), -1);
            if (!s.getToNodeId().equals(branchId)) {
                NewNode n = tree.node(s.getToNodeId());
                if (n != null) {
                    tree.nodes.remove(n);
                }
                tree.layout.removeNode(s.getToNodeId());
            }
        }
        Anchor anchor = branch.getKind() == NodeKind.NEW_CHAMBER
                ? Anchor.onNode(branchPoint, 0.0, treeIndex, branchId, heading)
                : Anchor.onNode(branchPoint, 0.0, treeIndex, branchId, null);
        Attempt best = bestAttempt(router, fullEngine, dn, anchor, trees, t, entries);
        boolean accepted = false;
        double newCost = best == null ? Double.NaN : chainCost(best.path, t, dn);
        if (best != null && !Double.isNaN(newCost)) {
            int oldLevel = t.attachedLevel;
            boolean betterLevel = best.level < oldLevel;
            boolean cheaper = best.level <= oldLevel && newCost < oldCost - POLISH_MIN_GAIN_M;
            if (betterLevel || cheaper) {
                try {
                    accepted = appendPath(tree, branchId, t, best.path, dn);
                    if (accepted) {
                        tree.freeze();
                        accepted = dnGuard == null || dnGuard.test(tree);
                    }
                    if (accepted) {
                        log.debug("STEINER доводка ОКС {}: {} → {} (уровень {} → {})", t.oks.getId(),
                                Math.round(oldCost * 10) / 10.0, Math.round(newCost * 10) / 10.0, oldLevel,
                                best.level);
                        t.attachedLevel = Math.min(oldLevel, best.level);
                    }
                } catch (RuntimeException ex) {
                    accepted = false;
                }
            }
        }
        if (!accepted) {
            tree.restore(snapshot);
        }
        return accepted;
    }

    /**
     * AUDIT-13 (Claude, 25.09). Доводка ветки к ОКС в графе ФАКТИЧЕСКОГО ДУ ветки.
     *
     * <p>Граф плана строится с буферами (отступ табл. 2 + ½ ширины пары, табл. 4.2) по одному ДУ — магистрали всех ОКС
     * или наибольшему фактическому ДУ первого прохода (VariantGenerator). Ветка к отдельному ОКС несёт только его
     * расход, и её ДУ по M5 обычно меньше: отступ «с запасом» законен, но уводит трассу в обход проходов, свободных
     * при нормативном отступе ДУ ветки. На синтетике s4 (граф ДУ 500 → отступ от ОКС 7 м; ветка ДУ 250 → 5 м) ветка
     * к ОКС 4 обходила квартал: 201 м вместо 131 м; на s1 — +11…29 м.</p>
     *
     * <p>Здесь ДУ каждой ветки берётся из расчёта M5 по текущему дереву ({@link EngineeringCalculator}: расход и
     * предельная длина табл. 1, Разъяснения №1–2); если он меньше ДУ графа, ветка доводится в графе и наборе
     * ограничений этого ДУ (те же проверки, что у обычной доводки, заходы §2.2 — по отступу этого ДУ). Новая ветка
     * принимается, только если после неё ни один участок дерева не получил ДУ больше прежнего, а участки самой ветки —
     * больше ДУ, с которым она проложена: иначе отступы, проверенные при трассировке, могли бы оказаться меньше
     * нормативных. Графы по ДУ кэшируются на весь расчёт (как граф плана).</p>
     */
    private final class LeafDnPolish {
        private final IngestResult ingest;
        private final ru.heatnet.calc.model.ExistingNetwork existing;
        private final int planDn;
        private final Envelope area;
        private final List<Coordinate> focus;
        private final ru.heatnet.calc.EngineeringCalculator calculator;
        private final Map<Integer, Context> contexts = new LinkedHashMap<>();
        private final Map<String, OksTarget> leafTargets = new LinkedHashMap<>();
        private int improved;

        private LeafDnPolish(IngestResult ingest, ru.heatnet.calc.model.ExistingNetwork existing, int planDn,
                             Envelope area, List<Coordinate> focus) {
            this.ingest = ingest;
            this.existing = existing;
            this.planDn = planDn;
            this.area = area;
            this.focus = focus;
            this.calculator = existing == null ? null : new ru.heatnet.calc.EngineeringCalculator(reference);
        }

        private final class Context {
            private final SharedVisibilityRouter router;
            private final SpatialConstraintEngine engine;

            private Context(SharedVisibilityRouter router, SpatialConstraintEngine engine) {
                this.router = router;
                this.engine = engine;
            }
        }

        /** ДУ участков дерева по M5 (id участка → ДУ); {@code null}, если расчёт невозможен. */
        private Map<String, Integer> diameters(WorkingTree w) {
            if (calculator == null || w.tree == null) {
                return null;
            }
            try {
                ru.heatnet.calc.EngineeringResult eng =
                        calculator.calculate(Collections.singletonList(w.tree), existing);
                return eng.getTrees().isEmpty() ? null : eng.getTrees().get(0).getDiameters();
            } catch (RuntimeException ex) {
                return null;
            }
        }

        private int chainDn(List<NewSegment> chain, Map<String, Integer> dns) {
            int max = 0;
            for (NewSegment s : chain) {
                Integer d = dns.get(s.getId());
                if (d == null) {
                    return 0;
                }
                max = Math.max(max, d.intValue());
            }
            return max;
        }

        /** Ни один участок не получил ДУ больше прежнего; новые участки ветки — не больше {@code chainDn}. */
        private boolean noDnIncrease(WorkingTree w, Map<String, Integer> before, int chainDn) {
            Map<String, Integer> after = diameters(w);
            if (after == null) {
                return false;
            }
            for (Map.Entry<String, Integer> e : after.entrySet()) {
                Integer old = before.get(e.getKey());
                int limit = old != null ? old.intValue() : chainDn;
                if (e.getValue() == null || e.getValue().intValue() > limit) {
                    return false;
                }
            }
            return true;
        }

        /** Граф и полный набор ограничений ДУ {@code leafDn}; {@code null}, если их не собрать. */
        private Context context(int leafDn) {
            if (contexts.containsKey(leafDn)) {
                return contexts.get(leafDn);
            }
            Context ctx = null;
            SpatialConstraintBundle bundle = routeFinderFactory.bundle(ingest, leafDn);
            if (bundle != null && bundle.getEngine() != null && area != null) {
                SharedVisibilityRouter r = cachedRouter(ingest, leafDn, area);
                if (r == null) {
                    r = new SharedVisibilityRouter(bundle.getEngine(), bundle.getBlockedOutlines(),
                            reference.getRules(), leafDn);
                    r.setFocus(focus);
                    if (r.build(area)) {
                        rememberRouter(ingest, leafDn, area, r);
                    } else {
                        r = null;
                    }
                }
                if (r != null) {
                    ctx = new Context(r, bundle.getEngine());
                }
            }
            if (ctx == null) {
                log.info("STEINER граф для ДУ ветки {} не собран — ветки доводятся в графе ДУ {}", leafDn, planDn);
            }
            contexts.put(leafDn, ctx);
            return ctx;
        }

        /**
         * Уровень ({@link OwnEntryCandidates#levelOf}) финального прямого участка текущей ветки к ОКС относительно
         * наименьшей достижимой при ДУ ветки глубины захода; −1, если не определить.
         */
        private int levelOfCurrent(WorkingTree tree, String oksNodeId, OksTarget lt) {
            if (lt.own == null || lt.entries.isEmpty()) {
                return -1;
            }
            NewSegment last = null;
            for (NewSegment s : tree.segments) {
                if (s.getToNodeId().equals(oksNodeId)) {
                    last = s;
                    break;
                }
            }
            LineString line = last == null ? null : tree.layout.segmentLine(last.getId());
            if (line == null || line.getNumPoints() < 2) {
                return -1;
            }
            LineString leg = gf.createLineString(new Coordinate[] {
                    new Coordinate(line.getCoordinateN(line.getNumPoints() - 2)), new Coordinate(lt.p)});
            double inside = insideLength(leg, lt);
            double best = Double.POSITIVE_INFINITY;
            for (OwnEntryCandidates.Entry e : lt.entries) {
                best = Math.min(best, e.insideM);
            }
            if (!Double.isFinite(inside) || !Double.isFinite(best)) {
                return -1;
            }
            // тот же критерий «уровня 0», что у заходов доводки (computeRelaxed): не глубже
            // max(лучший достижимый + 0,2 м; расстояние до границы + 1 м) — такой заход уже соответствует §2.2
            double dmin;
            try {
                dmin = gf.createPoint(lt.p).distance(lt.own.getBoundary());
            } catch (RuntimeException ex) {
                return -1;
            }
            double relaxedLimit = Math.max(best + OwnEntryCandidates.LEVEL_WINDOWS[0],
                    dmin + OwnEntryCandidates.RELAXED_EXCESS_M);
            if (inside <= relaxedLimit + 1e-6) {
                return 0;
            }
            return OwnEntryCandidates.levelOf(Math.max(0.0, inside - best));
        }

        /** Цель §2.2 с заходами по отступу ДУ ветки; {@code null}, если прямого захода при этом ДУ нет. */
        private OksTarget target(OksTarget t, int leafDn, Context ctx) {
            String key = t.oks.getId() + "|" + leafDn;
            OksTarget lt = leafTargets.get(key);
            if (lt == null && !leafTargets.containsKey(key)) {
                lt = prepareTarget(ingest, t.oks, t.p, leafDn, ctx.engine, t.group);
                if (lt.entries.isEmpty()) {
                    lt = null;
                }
                leafTargets.put(key, lt);
            }
            if (lt != null) {
                lt.attachedLevel = t.attachedLevel;
                lt.level = t.level;
            }
            return lt;
        }
    }

    /** Длина × Kспец ветки так же, как её разобьёт {@link #appendPath} (спецучастки — набором без своего полигона). */
    private double chainCost(List<Coordinate> path, OksTarget t, int dn) {
        LineString line = lineOf(path);
        if (line == null) {
            return Double.NaN;
        }
        List<RouteSegmentSplitter.RoutePiece> pieces =
                splitter.split(RouteResult.found(line, t.approach.extractSpecialSections(line, dn)));
        if (pieces.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (RouteSegmentSplitter.RoutePiece piece : pieces) {
            sum += piece.getLengthM() * (piece.getLayingMethod() == LayingMethod.SPECIAL ? piece.getKSpec() : 1.0);
        }
        return sum;
    }

    private List<OwnEntryCandidates.Entry> relaxedEntries(IngestResult ingest, OksTarget t, int dn,
                                                          SpatialConstraintEngine fullEngine) {
        if (t.relaxed != null) {
            return t.relaxed;
        }
        if (t.own == null || t.own.isEmpty()) {
            t.relaxed = java.util.Collections.emptyList();
            return t.relaxed;
        }
        RestrictionRule rule = reference.getRules().getRestrictions().get("oks_existing");
        double clearance = (rule == null ? 5.0 : rule.minOffsetM(dn))
                + reference.getGabarits().spec(dn).getWidthM() / 2.0;
        final Coordinate p = t.p;
        final Geometry own = t.own;
        final SpatialConstraintEngine approach = t.approach;
        t.relaxed = cachedEntries(ingest, t.oks.getId() + "|relaxed", dn,
                () -> OwnEntryCandidates.computeRelaxed(p, own, clearance, dn, fullEngine, approach, gf));
        return t.relaxed;
    }

    /**
     * Лучшая по (уровень захода, стоимость) допустимая ветка из {@code anchor} к одному из заходов {@code entries}:
     * те же проверки, что у попыток жадного шага ({@link #collectAttempts}), без резервов других ОКС.
     */
    private Attempt bestAttempt(SharedVisibilityRouter router, SpatialConstraintEngine fullEngine, int dn,
                                Anchor anchor, List<WorkingTree> trees, OksTarget t,
                                List<OwnEntryCandidates.Entry> entries) {
        List<LineString> built = new ArrayList<>();
        for (WorkingTree w : trees) {
            for (NewSegment segment : w.segments) {
                LineString line = w.layout.segmentLine(segment.getId());
                if (line != null) {
                    built.add(line);
                }
            }
        }
        router.setForbidden(built);
        List<SharedVisibilityRouter.Seed> seeds = java.util.Collections.singletonList(
                new SharedVisibilityRouter.Seed(anchor.point, 0.0, 0, anchor.heading));
        List<SharedVisibilityRouter.Target> specs = new ArrayList<>();
        for (OwnEntryCandidates.Entry e : entries) {
            specs.add(new SharedVisibilityRouter.Target(e.q, t.own == null ? null : t.p));
        }
        List<SharedVisibilityRouter.RouteHit> hits = router.cheapestTo(seeds, specs);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i) != null) {
                order.add(i);
            }
        }
        order.sort(Comparator.comparingInt((Integer i) -> entries.get(i).level)
                .thenComparingDouble(i -> hits.get(i).getCost() + entries.get(i).q.distance(t.p)));
        Map<String, LineString> noReserve = java.util.Collections.emptyMap();
        Attempt best = null;
        for (int i : order) {
            OwnEntryCandidates.Entry entry = entries.get(i);
            SharedVisibilityRouter.RouteHit hit = hits.get(i);
            if (best != null && (entry.level > best.level
                    || hit.getCost() + entry.q.distance(t.p) > best.cost * 1.15 + 5.0)) {
                break; // спрямление удешевляет ветку немного — дальше только заведомо дороже
            }
            Attempt a = attemptFromHit(hit, anchor, t, entry, fullEngine, dn, trees, noReserve, 0.0);
            if (a != null && (best == null || a.level < best.level
                    || a.level == best.level && a.cost < best.cost - 1e-9)) {
                best = a;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ сборка деревьев

    private WorkingTree attach(ExistingNetworkGeometry geometry, int dn, List<WorkingTree> trees, Attempt attempt) {
        try {
            if (attempt.anchor.treeIndex < 0) {
                WorkingTree created = newTree(geometry, dn, attempt);
                if (created != null) {
                    trees.add(created);
                }
                return created;
            }
            WorkingTree host = trees.get(attempt.anchor.treeIndex);
            WorkingTree.Snapshot snapshot = host.snapshot();
            WorkingTree grafted = graft(dn, host, attempt);
            if (grafted == null) {
                host.restore(snapshot);
            }
            return grafted;
        } catch (RuntimeException ex) {
            log.debug("Ветка к ОКС {} не собралась: {}", attempt.target.oks.getId(), ex.toString());
            return null;
        }
    }

    private WorkingTree newTree(ExistingNetworkGeometry geometry, int dn, Attempt attempt) {
        Anchor anchor = attempt.anchor;
        TieInPoint tie = anchor.tie;
        if (tie == null) {
            tie = tieAt(geometry, anchor.point);
        }
        if (tie == null) {
            return null;
        }
        WorkingTree tree = new WorkingTree(tie, attempt.target.group);
        tree.layout.putNode(tie.getId(), anchor.point);
        tree.nodes.add(NewNode.tieIn(tie.getId()));
        if (!appendPath(tree, tie.getId(), attempt.target, attempt.path, dn)) {
            return null;
        }
        tree.freeze();
        return tree;
    }

    private WorkingTree graft(int dn, WorkingTree tree, Attempt attempt) {
        Anchor anchor = attempt.anchor;
        String fromId = anchor.atNodeId;
        if (fromId == null) {
            fromId = splitSegment(tree, anchor);
        }
        if (fromId == null) {
            return null;
        }
        NewNode from = tree.node(fromId);
        if (from == null || from.getKind() != NodeKind.NEW_CHAMBER) {
            // Ответвление — только в тепловой камере (§2.1); у точки ОКС/врезки/тех. узла — нельзя.
            return null;
        }
        if (tree.degree(fromId) >= reference.getRules().getMaxSegmentsPerChamber()) {
            return null;
        }
        if (!appendPath(tree, fromId, attempt.target, attempt.path, dn)) {
            return null;
        }
        tree.freeze();
        return tree;
    }

    private boolean appendPath(WorkingTree tree, String fromId, OksTarget target, List<Coordinate> path, int dn) {
        LineString line = lineOf(path);
        if (line == null) {
            return false;
        }
        Coordinate start = tree.layout.nodeCoordinate(fromId);
        if (start != null && line.getCoordinateN(0).distance(start) > 0.05) {
            List<Coordinate> snapped = new ArrayList<>();
            snapped.add(new Coordinate(start));
            for (int i = 0; i < line.getNumPoints(); i++) {
                Coordinate c = line.getCoordinateN(i);
                if (snapped.get(snapped.size() - 1).distance(c) > 0.05) {
                    snapped.add(c);
                }
            }
            line = lineOf(snapped);
            if (line == null) {
                return false;
            }
        }
        List<SpecialSection> sections = target.approach.extractSpecialSections(line, dn);
        List<RouteSegmentSplitter.RoutePiece> pieces = splitter.split(RouteResult.found(line, sections));
        if (pieces.isEmpty()) {
            return false;
        }
        String prev = fromId;
        for (int i = 0; i < pieces.size(); i++) {
            RouteSegmentSplitter.RoutePiece piece = pieces.get(i);
            if (!(piece.getLengthM() > 0)) {
                continue;
            }
            boolean last = i == pieces.size() - 1;
            String next;
            Coordinate end = piece.getGeometry().getCoordinateN(piece.getGeometry().getNumPoints() - 1);
            if (last) {
                next = "oks_" + target.oks.getId();
                tree.nodes.add(NewNode.oks(next, target.oks.getId(), target.oks.getFlowTph()));
            } else {
                next = "stn_" + (++seq);
                tree.nodes.add(NewNode.technical(next));
            }
            tree.layout.putNode(next, end);
            String segId = "stseg_" + (++seq);
            NewSegment segment = piece.getLayingMethod() == LayingMethod.SPECIAL
                    ? NewSegment.special(segId, prev, next, piece.getLengthM(), piece.getKSpec())
                    : NewSegment.base(segId, prev, next, piece.getLengthM());
            tree.segments.add(segment);
            tree.layout.putSegment(segId, piece.getGeometry());
            tree.addDegree(prev);
            tree.addDegree(next);
            prev = next;
        }
        return true;
    }

    private String splitSegment(WorkingTree tree, Anchor anchor) {
        NewSegment host = null;
        for (NewSegment segment : tree.segments) {
            if (segment.getId().equals(anchor.alongSegmentId)) {
                host = segment;
                break;
            }
        }
        if (host == null || host.getLayingMethod() == LayingMethod.SPECIAL) {
            return null;
        }
        LineString line = tree.layout.segmentLine(host.getId());
        if (line == null) {
            return null;
        }
        double pos = RouteGeometryUtils.positionAlong(line, anchor.point);
        double len = RouteGeometryUtils.accumulatedLength(line);
        if (pos <= 1.0 || len - pos <= 1.0) {
            // У самого конца участка ответвление = ответвление в существующем узле; такие случаи
            // покрывают якоря onNode (только камеры). Техузел/ОКС камерой не становятся.
            return null;
        }
        String chamberId = "stch_" + (++seq);
        tree.nodes.add(NewNode.chamber(chamberId));
        Coordinate at = RouteGeometryUtils.pointAtDistance(line, pos);
        tree.layout.putNode(chamberId, at);
        LineString left = RouteGeometryUtils.extractSubLine(gf, line, 0, pos, 0.05);
        LineString right = RouteGeometryUtils.extractSubLine(gf, line, pos, len, 0.05);
        tree.segments.remove(host);
        tree.layout.removeSegment(host.getId());
        tree.addDegree(host.getFromNodeId(), -1);
        tree.addDegree(host.getToNodeId(), -1);
        addPiece(tree, host, host.getFromNodeId(), chamberId, left);
        addPiece(tree, host, chamberId, host.getToNodeId(), right);
        return chamberId;
    }

    private void addPiece(WorkingTree tree, NewSegment prototype, String fromId, String toId, LineString line) {
        if (line == null || line.getNumPoints() < 2 || line.getLength() <= 0.05) {
            return;
        }
        String id = "stseg_" + (++seq);
        NewSegment segment = prototype.getLayingMethod() == LayingMethod.SPECIAL
                ? NewSegment.special(id, fromId, toId, line.getLength(), prototype.getKSpec())
                : NewSegment.base(id, fromId, toId, line.getLength());
        tree.segments.add(segment);
        tree.layout.putSegment(id, line);
        tree.addDegree(fromId);
        tree.addDegree(toId);
    }

    // ------------------------------------------------------------------ якоря

    private List<Anchor> networkAnchors(ru.heatnet.calc.model.ExistingNetwork existing,
                                        ExistingNetworkGeometry geometry, int dn, Envelope area,
                                        List<Coordinate> preferredPipePoints) {
        List<Anchor> anchors = new ArrayList<>();
        ChamberCostScale scale = new ChamberCostScale(reference.getRules());
        double rubPerM = Math.max(1.0, reference.getDiameters().spec(dn).getNewCostRubPerM());
        double chamberExtra = scale.tieInCost() / rubPerM;
        int maxDegree = reference.getRules().getMaxSegmentsPerChamber();
        double tieRadius = reference.getRules().getTieInChamberRadiusM() + reference.getRules().getGeometryToleranceM();
        List<Coordinate> eligibleChambers = new ArrayList<>();
        for (Map.Entry<String, Coordinate> chamber : geometry.getChamberPoints().entrySet()) {
            if (!existing.isChamber(chamber.getKey())) {
                continue;
            }
            if (!ChamberDegreeCalculator.canAddTieIn(existing, geometry, chamber.getKey(), maxDegree, 1)) {
                continue;
            }
            eligibleChambers.add(chamber.getValue());
            if (area != null && !area.contains(chamber.getValue())) {
                continue;
            }
            String tieId = "tie_c_" + chamber.getKey() + "_" + (++seq);
            TieInPoint tie = TieInPoint.intoChamber(tieId, chamber.getKey());
            anchors.add(Anchor.network(chamber.getValue(), chamberExtra, tie));
        }
        refineEligibleChambers = eligibleChambers;
        refineTieRadius = tieRadius;
        for (Map.Entry<String, LineString> entry : geometry.getSegmentLines().entrySet()) {
            if (!existing.isSegment(entry.getKey())) {
                continue;
            }
            LineString line = entry.getValue();
            if (area != null && !area.intersects(line.getEnvelopeInternal())) {
                continue;
            }
            int pipeDn = existing.segment(entry.getKey()).getDiameter();
            double pipeExtra = scale.costFor(chamberDn(Math.max(pipeDn, dn))) / rubPerM;
            double length = RouteGeometryUtils.accumulatedLength(line);
            List<Coordinate> samples = new ArrayList<>();
            for (Coordinate preferred : preferredPipePoints) {
                if (line.distance(gf.createPoint(preferred)) <= 0.01) {
                    samples.add(preferred);
                }
            }
            for (double at = PIPE_END_MARGIN_M; at < length - PIPE_END_MARGIN_M; at += PIPE_SAMPLE_M) {
                samples.add(RouteGeometryUtils.pointAtDistance(line, at));
            }
            List<Coordinate> placed = new ArrayList<>();
            for (Coordinate point : samples) {
                if (area != null && !area.contains(point)) {
                    continue;
                }
                if (nearAny(point, eligibleChambers, tieRadius)) {
                    continue; // §2.4: в 10 м есть камера, к которой можно примкнуть, — врезка в неё
                }
                if (nearAny(point, placed, ANCHOR_DEDUPE_M)) {
                    continue;
                }
                ExistingNetworkGeometry.PipeProjection proj =
                        geometry.projectOnSegment(entry.getKey(), point, PIPE_END_MARGIN_M);
                if (proj == null) {
                    continue;
                }
                placed.add(proj.getPoint());
                String tieId = "tie_p_" + entry.getKey() + "_" + Math.round(proj.getDistanceFromUpstreamEndM())
                        + "_" + (++seq);
                TieInPoint tie = TieInPoint.intoPipe(tieId, entry.getKey(),
                        proj.getDistanceFromUpstreamEndM(), "nch_" + tieId);
                anchors.add(Anchor.network(proj.getPoint(), pipeExtra, tie));
            }
        }
        return anchors;
    }

    private int chamberDn(int dn) {
        return dn;
    }

    private static boolean nearAny(Coordinate p, List<Coordinate> points, double radius) {
        for (Coordinate c : points) {
            if (c.distance(p) <= radius) {
                return true;
            }
        }
        return false;
    }

    /** Ближайшая к каждому ОКС точка каждой трубы — естественные кандидаты врезки. */
    private List<Coordinate> nearestPipePoints(ExistingNetworkGeometry geometry, java.util.Collection<Coordinate> oks) {
        List<Coordinate> out = new ArrayList<>();
        for (Coordinate p : oks) {
            Coordinate best = null;
            double bestD = Double.POSITIVE_INFINITY;
            for (LineString line : geometry.getSegmentLines().values()) {
                Coordinate[] near = org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(
                        line, gf.createPoint(p));
                double d = near[0].distance(p);
                if (d < bestD) {
                    bestD = d;
                    best = near[0];
                }
            }
            if (best != null) {
                out.add(best);
            }
        }
        return out;
    }

    private List<Anchor> treeAnchors(List<WorkingTree> trees, Integer group) {
        List<Anchor> anchors = new ArrayList<>();
        int maxDegree = reference.getRules().getMaxSegmentsPerChamber();
        double rubPerM = Math.max(1.0, reference.getDiameters().spec(100).getNewCostRubPerM());
        double branchExtra = new ChamberCostScale(reference.getRules()).costFor(100) / rubPerM;
        for (int t = 0; t < trees.size(); t++) {
            WorkingTree tree = trees.get(t);
            if (group != null && tree.group != group) {
                continue;
            }
            for (NewSegment segment : tree.segments) {
                LineString line = tree.layout.segmentLine(segment.getId());
                if (line == null) {
                    continue;
                }
                NewNode to = tree.node(segment.getToNodeId());
                if (segment.getLayingMethod() != LayingMethod.SPECIAL) {
                    double length = RouteGeometryUtils.accumulatedLength(line);
                    for (double at = TREE_SAMPLE_M; at < length - 1.0; at += TREE_SAMPLE_M) {
                        Coordinate point = RouteGeometryUtils.pointAtDistance(line, at);
                        Coordinate heading = headingAt(line, at, point);
                        anchors.add(Anchor.onSegment(point, branchExtra, t, segment.getId(), heading));
                    }
                }
                if (to != null && to.getKind() == NodeKind.NEW_CHAMBER && tree.degree(to.getId()) < maxDegree) {
                    Coordinate at = tree.layout.nodeCoordinate(to.getId());
                    if (at != null && line.getNumPoints() >= 2) {
                        Coordinate heading = line.getCoordinateN(line.getNumPoints() - 2);
                        anchors.add(Anchor.onNode(at, 0.0, t, to.getId(), heading));
                    }
                }
            }
        }
        return anchors;
    }

    private List<Anchor> dropOccupiedTies(List<Anchor> anchors, List<WorkingTree> trees) {
        if (trees.isEmpty()) {
            return anchors;
        }
        List<Anchor> kept = new ArrayList<>();
        for (Anchor anchor : anchors) {
            boolean busy = false;
            for (WorkingTree tree : trees) {
                Coordinate placed = tree.layout.nodeCoordinate(tree.tie.getId());
                if (placed != null && placed.distance(anchor.point) <= TIE_CLEARANCE_M) {
                    busy = true;
                    break;
                }
            }
            if (!busy) {
                kept.add(anchor);
            }
        }
        return kept;
    }

    // ------------------------------------------------------------------ геометрия

    /**
     * AUDIT-13 (Claude, 25.09). Проверки финального прямого захода Q→P (§2.2): {1 — отрезок недопустим по набору без
     * своего полигона (или проверка упала), длина внутри своего полигона, длина отрезка}. Один и тот же Q (точка
     * захода цели) проверяется сотни раз — для каждого якоря, каждого раунда жадного шага и доводки; отрезок и набор
     * ограничений цели при этом те же, поэтому результат запоминается в цели (при другом ДУ мемо сбрасывается).
     */
    private double[] finalLeg(Coordinate q, OksTarget t, int dn) {
        if (t.legMemoDn != dn) {
            t.legMemo.clear();
            t.legMemoDn = dn;
        }
        Coordinate key = new Coordinate(q.x, q.y);
        double[] memo = t.legMemo.get(key);
        if (memo != null) {
            return memo;
        }
        LineString leg = gf.createLineString(new Coordinate[] {new Coordinate(q), new Coordinate(t.p)});
        boolean blocked;
        try {
            blocked = t.approach.isSegmentBlocked(leg, dn);
        } catch (RuntimeException ex) {
            blocked = true;
        }
        double[] value = {blocked ? 1.0 : 0.0, blocked ? Double.NaN : insideLength(leg, t), leg.getLength()};
        t.legMemo.put(key, value);
        return value;
    }

    /**
     * AUDIT-13 (Claude, 25.09): то же, что {@link #insideLength(LineString, Geometry)}, но сначала — быстрая проверка
     * «звено вообще не касается своего полигона» по подготовленной геометрии (результат тот же: пересечение пусто ⇔
     * нет {@code intersects}). Оверлей JTS {@code intersection} для каждого звена спрямления и каждой попытки
     * присоединения был самым затратным местом плана (~26 % времени конкурсного расчёта), хотя почти все звенья
     * лежат вне здания.
     */
    private static double insideLength(LineString leg, OksTarget t) {
        if (leg == null || t == null || t.own == null || t.own.isEmpty()) {
            return 0.0;
        }
        try {
            if (!leg.getEnvelopeInternal().intersects(t.own.getEnvelopeInternal())) {
                return 0.0;
            }
            if (t.ownPrepared == null) {
                t.ownPrepared = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(t.own);
            }
            if (!t.ownPrepared.intersects(leg)) {
                return 0.0;
            }
        } catch (RuntimeException ex) {
            // как раньше — решает полный оверлей ниже
        }
        return insideLength(leg, t.own);
    }

    private static double insideLength(LineString leg, Geometry own) {
        if (leg == null || own == null || own.isEmpty()) {
            return 0.0;
        }
        try {
            Geometry inter = leg.intersection(own);
            if (inter == null || inter.isEmpty()) {
                return 0.0;
            }
            return inter.getLength();
        } catch (RuntimeException ex) {
            return Double.POSITIVE_INFINITY;
        }
    }

    /** Охват района, где строится граф: как раньше — все ОКС и вся сеть; для городской сети — вокруг ОКС. */
    private static final double MAX_FULL_AREA_DIAGONAL_M = 3000.0;
    private static final double FOCUS_EXTRA_MARGIN_M = 300.0;

    private Envelope areaOf(List<Coordinate> focus, ExistingNetworkGeometry geometry) {
        Envelope focusEnv = new Envelope();
        for (Coordinate c : focus) {
            focusEnv.expandToInclude(c);
        }
        Envelope full = new Envelope(focusEnv);
        for (LineString line : geometry.getSegmentLines().values()) {
            full.expandToInclude(line.getEnvelopeInternal());
        }
        Envelope chosen;
        if (Math.hypot(full.getWidth(), full.getHeight()) <= MAX_FULL_AREA_DIAGONAL_M) {
            chosen = full;
        } else {
            chosen = new Envelope(focusEnv);
            chosen.expandBy(FOCUS_EXTRA_MARGIN_M);
            log.info("Сеть охватывает {} м — граф строится вокруг ОКС (+{} м)",
                    Math.round(Math.hypot(full.getWidth(), full.getHeight())), FOCUS_EXTRA_MARGIN_M);
        }
        chosen.expandBy(reference.getRules().getRoutingWorkspaceMarginM());
        return chosen;
    }

    private Coordinate oksCoordinate(IngestResult ingest, OksConnectionPoint oks) {
        ru.heatnet.ingest.RawFeature feature = ingest.oksFeature(oks.getId());
        if (feature == null || feature.getGeometryWgs84() == null) {
            throw new NetworkException("oks_connection_point " + oks.getId() + " не найден");
        }
        Coordinate wgs = feature.getGeometryWgs84().getCoordinate();
        return projection.pointToUtm(wgs.x, wgs.y).getCoordinate();
    }

    private TieInPoint tieAt(ExistingNetworkGeometry geometry, Coordinate point) {
        ExistingNetworkGeometry.PipeProjection best = null;
        String bestId = null;
        for (Map.Entry<String, LineString> entry : geometry.getSegmentLines().entrySet()) {
            ExistingNetworkGeometry.PipeProjection proj = geometry.projectOnSegment(entry.getKey(), point, 2.0);
            if (proj == null) {
                continue;
            }
            if (best == null || proj.getOffsetFromAxisM() < best.getOffsetFromAxisM()) {
                best = proj;
                bestId = entry.getKey();
            }
        }
        if (best == null || bestId == null) {
            return null;
        }
        String tieId = "tie_p_" + bestId + "_" + Math.round(best.getDistanceFromUpstreamEndM()) + "_" + (++seq);
        return TieInPoint.intoPipe(tieId, bestId, best.getDistanceFromUpstreamEndM(), "nch_" + tieId);
    }

    private LineString lineOf(List<Coordinate> path) {
        if (path == null || path.size() < 2) {
            return null;
        }
        List<Coordinate> clean = new ArrayList<>();
        for (Coordinate c : path) {
            if (clean.isEmpty() || clean.get(clean.size() - 1).distance(c) > 0.02) {
                clean.add(new Coordinate(c));
            }
        }
        if (clean.size() < 2) {
            return null;
        }
        return gf.createLineString(clean.toArray(new Coordinate[0]));
    }

    private static List<Coordinate> coordinatesOf(LineString line) {
        List<Coordinate> coords = new ArrayList<>();
        for (Coordinate c : line.getCoordinates()) {
            coords.add(new Coordinate(c));
        }
        return coords;
    }

    private static boolean turnsOk(LineString line) {
        if (line == null || line.getNumPoints() < 2) {
            return false;
        }
        for (int i = 1; i < line.getNumPoints() - 1; i++) {
            double deviation = TurnRepair.deviationDeg(
                    line.getCoordinateN(i - 1), line.getCoordinateN(i), line.getCoordinateN(i + 1));
            if (deviation > TurnRepair.MAX_TURN_DEG + 1e-6) {
                return false;
            }
        }
        return true;
    }

    private String crossWhy(LineString line, List<WorkingTree> trees, Coordinate attach) {
        for (int ti = 0; ti < trees.size(); ti++) {
            WorkingTree tree = trees.get(ti);
            for (NewSegment segment : tree.segments) {
                LineString other = tree.layout.segmentLine(segment.getId());
                if (other == null || !line.intersects(other)) {
                    continue;
                }
                Geometry inter = line.intersection(other);
                return "дерево " + ti + " участок " + segment.getId() + " в " + inter;
            }
        }
        return "резерв";
    }

    private boolean crosses(LineString line, List<WorkingTree> trees, Coordinate attach) {
        Envelope env = new Envelope(line.getEnvelopeInternal());
        env.expandBy(ru.heatnet.routing.NewNetworkClearance.MIN_GAP_M);
        for (WorkingTree tree : trees) {
            for (NewSegment segment : tree.segments) {
                LineString other = tree.layout.segmentLine(segment.getId());
                if (other == null || !env.intersects(other.getEnvelopeInternal())) {
                    continue; // далеко — ни пересечения, ни сближения
                }
                Geometry inter;
                try {
                    inter = line.intersection(other);
                } catch (RuntimeException ex) {
                    return true;
                }
                if (inter == null || inter.isEmpty()) {
                    // AUDIT-12 (Claude, 24.09): почти наложение без топологического пересечения (§2.1)
                    if (ru.heatnet.routing.NewNetworkClearance.tooClose(line, other, attach, null)) {
                        return true;
                    }
                    continue;
                }
                if (inter.getDimension() >= 1) {
                    return true;
                }
                for (Coordinate c : inter.getCoordinates()) {
                    if (attach == null || c.distance(attach) > TIE_CLEARANCE_M) {
                        return true;
                    }
                }
                if (ru.heatnet.routing.NewNetworkClearance.tooClose(line, other, attach, null)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ структуры

    private static final class OksTarget {
        private final OksConnectionPoint oks;
        private final Coordinate p;
        private final SpatialConstraintEngine approach;
        private final Geometry own;
        private final int group;
        private final List<OwnEntryCandidates.Entry> entries = new ArrayList<>();
        private int level;
        private int maxLevel;
        /** Уровень захода, с которым ОКС присоединён (−1 — не присоединён). */
        private int attachedLevel = -1;
        /** AUDIT-13: заходы через ту же ближайшую стену для доводки ({@link OwnEntryCandidates#computeRelaxed}). */
        private List<OwnEntryCandidates.Entry> relaxed;
        /** Финальная прямая лучшего захода (+3 м наружу) — чужие трассы её не пересекают, пока ОКС ждёт. */
        private LineString reserved;
        /** AUDIT-13: подготовленный свой полигон для быстрой проверки «звено не заходит в здание». */
        private org.locationtech.jts.geom.prep.PreparedGeometry ownPrepared;
        /** AUDIT-13: мемо проверок финального прямого Q→P ({@link SteinerPlanner#finalLeg}) при ДУ {@link #legMemoDn}. */
        private final Map<Coordinate, double[]> legMemo = new java.util.HashMap<>();
        private int legMemoDn = Integer.MIN_VALUE;

        private OksTarget(OksConnectionPoint oks, Coordinate p, SpatialConstraintEngine approach, Geometry own,
                          int group) {
            this.oks = oks;
            this.p = p;
            this.approach = approach;
            this.own = own;
            this.group = group;
        }
    }

    private static final class Attempt {
        private final OksTarget target;
        private final Anchor anchor;
        private final List<Coordinate> path;
        private final double cost;
        private final int level;

        private Attempt(OksTarget target, Anchor anchor, List<Coordinate> path, double cost, int level) {
            this.target = target;
            this.anchor = anchor;
            this.path = path;
            this.cost = cost;
            this.level = level;
        }
    }

    private static final class Anchor {
        private final Coordinate point;
        private final double extraCost;
        private final TieInPoint tie;
        private final int treeIndex;
        private final String atNodeId;
        private final String alongSegmentId;
        private final Coordinate heading;

        private Anchor(Coordinate point, double extraCost, TieInPoint tie, int treeIndex,
                       String atNodeId, String alongSegmentId, Coordinate heading) {
            this.point = point;
            this.extraCost = extraCost;
            this.tie = tie;
            this.treeIndex = treeIndex;
            this.atNodeId = atNodeId;
            this.alongSegmentId = alongSegmentId;
            this.heading = heading;
        }

        private static Anchor network(Coordinate point, double extraCost, TieInPoint tie) {
            return new Anchor(point, extraCost, tie, -1, null, null, null);
        }

        private static Anchor onSegment(Coordinate point, double extraCost, int treeIndex, String segmentId,
                                        Coordinate heading) {
            return new Anchor(point, extraCost, null, treeIndex, null, segmentId, heading);
        }

        private static Anchor onNode(Coordinate point, double extraCost, int treeIndex, String nodeId,
                                     Coordinate heading) {
            return new Anchor(point, extraCost, null, treeIndex, nodeId, null, heading);
        }
    }

    private static final class WorkingTree {
        private final TieInPoint tie;
        private final int group;
        private List<NewNode> nodes = new ArrayList<>();
        private List<NewSegment> segments = new ArrayList<>();
        private NetworkTreeLayout layout = new NetworkTreeLayout();
        private Map<String, Integer> degrees = new LinkedHashMap<>();
        private NewNetworkTree tree;

        private WorkingTree(TieInPoint tie, int group) {
            this.tie = tie;
            this.group = group;
        }

        private NewNode node(String id) {
            for (NewNode node : nodes) {
                if (node.getId().equals(id)) {
                    return node;
                }
            }
            return null;
        }

        private int degree(String id) {
            Integer value = degrees.get(id);
            return value == null ? 0 : value.intValue();
        }

        private void addDegree(String id) {
            addDegree(id, 1);
        }

        private void addDegree(String id, int delta) {
            degrees.put(id, Integer.valueOf(degree(id) + delta));
        }

        private void freeze() {
            this.tree = new NewNetworkTree(tie, nodes, segments);
        }

        private Snapshot snapshot() {
            return new Snapshot(new ArrayList<>(nodes), new ArrayList<>(segments), layout.copy(),
                    new LinkedHashMap<>(degrees), tree);
        }

        private void restore(Snapshot s) {
            this.nodes = s.nodes;
            this.segments = s.segments;
            this.layout = s.layout;
            this.degrees = s.degrees;
            this.tree = s.tree;
        }

        private static final class Snapshot {
            private final List<NewNode> nodes;
            private final List<NewSegment> segments;
            private final NetworkTreeLayout layout;
            private final Map<String, Integer> degrees;
            private final NewNetworkTree tree;

            private Snapshot(List<NewNode> nodes, List<NewSegment> segments, NetworkTreeLayout layout,
                             Map<String, Integer> degrees, NewNetworkTree tree) {
                this.nodes = nodes;
                this.segments = segments;
                this.layout = layout;
                this.degrees = degrees;
                this.tree = tree;
            }
        }
    }

    @SuppressWarnings("unused")
    private static <T> List<T> unmodifiable(List<T> list) {
        return Collections.unmodifiableList(list);
    }
}
