package ru.heatnet.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.heatnet.calc.reference.RulesConfig;
import ru.heatnet.rules.SpatialConstraintEngine;
import ru.heatnet.rules.model.SpecialSection;

/**
 * Один граф видимости на весь район. Много запросов «откуда угодно → куда угодно»
 * без пересборки рёбер: так дерево Штейнера успевает обойти дворы, а не каждую улицу заново.
 */
public final class SharedVisibilityRouter {

    private static final Logger log = LoggerFactory.getLogger(SharedVisibilityRouter.class);
    private static final double MAX_EDGE_M = 180.0;
    private static final int MAX_VERTICES = 2400;
    private static final double EARLY_STOP_FACTOR = 1.5;
    private static final double EARLY_STOP_SLACK_M = 250.0;
    /**
     * AUDIT-24.09 (Claude): страховочный лимит времени сборки графа. Был 120 с: на медленной машине
     * граф «не собирался», и расчёт молча уходил в старый планировщик с другим результатом —
     * итог зависел от скорости железа. Теперь это только защита от зависания.
     */
    private static final long BUILD_BUDGET_MS = 900_000L;

    private final SpatialConstraintEngine engine;
    private final List<Geometry> blockedOutlines;
    private final RulesConfig rules;
    private final int dn;
    private final STRtree outlineIndex = new STRtree();

    private VisibilityGraph graph = VisibilityGraph.empty();
    /** Точки интереса (ОКС, ближайшие точки сети): при переполнении вершин оставляем ближайшие к ним. */
    private List<Coordinate> focus = java.util.Collections.emptyList();
    private STRtree vertexIndex = new STRtree();
    private final Map<String, List<Link>> linkCache = new HashMap<>();
    private boolean ready;

    public SharedVisibilityRouter(SpatialConstraintEngine engine, List<Geometry> blockedOutlines,
                                  RulesConfig rules, int dn) {
        this.engine = engine;
        this.blockedOutlines = blockedOutlines == null
                ? java.util.Collections.<Geometry>emptyList() : blockedOutlines;
        this.rules = rules;
        this.dn = dn;
        for (Geometry outline : this.blockedOutlines) {
            if (outline != null && !outline.isEmpty()) {
                outlineIndex.insert(outline.getEnvelopeInternal(), outline);
            }
        }
        outlineIndex.build();
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * AUDIT-24.09 (Claude): при числе вершин больше {@link #MAX_VERTICES} раньше хвост списка просто
     * отрезался (а в хвосте — выпуклые углы зданий: они добавлялись после «юбок»), и на наборе чуть
     * больше конкурсного (2353 вершины при лимите 2400) граф терял углы кварталов. Теперь
     * отбрасываются вершины, наиболее удалённые от точек интереса.
     */
    /**
     * AUDIT-24.09 (Claude): уже построенные участки новой сети. Пересекать их вне общего узла нельзя
     * (§2.1), поэтому поиск пути обходит их, а не находит «кратчайший» путь, который планировщик потом
     * отбраковывает. Касание допускается только в точке семени (ответвление от этой сети).
     */
    public void setForbidden(List<LineString> lines) {
        STRtree index = new STRtree();
        int n = 0;
        if (lines != null) {
            for (LineString line : lines) {
                if (line == null || line.getNumPoints() < 2) {
                    continue;
                }
                for (int i = 0; i + 1 < line.getNumPoints(); i++) {
                    LineString edge = RoutingGeometry.line(line.getCoordinateN(i), line.getCoordinateN(i + 1));
                    index.insert(edge.getEnvelopeInternal(), edge);
                    n++;
                }
            }
        }
        index.build();
        this.forbiddenIndex = n == 0 ? null : index;
        forbiddenEdgeMemo.clear();
    }

    private STRtree forbiddenIndex;
    private static final double FORBIDDEN_TOUCH_M = 1.05;

    private boolean crossesForbidden(Coordinate a, Coordinate b, Coordinate allowedTouch) {
        return crossesForbidden(a, b, allowedTouch, null);
    }

    private boolean crossesForbidden(Coordinate a, Coordinate b, Coordinate allowedTouch, Coordinate allowedTouch2) {
        STRtree index = forbiddenIndex;
        if (index == null) {
            return false;
        }
        // AUDIT-12 (Claude, 24.09): вместо LineString.intersects/intersection (оверлей JTS на каждый шаг Дейкстры —
        // главный расход времени на больших наборах) — арифметика отрезков: построенные участки разбиты на
        // двухточечные звенья. Семантика прежняя: пересечение вне допустимого касания ±1,05 м, наложение
        // (коллинеарное) и «почти наложение» ближе NewNetworkClearance.MIN_GAP_M — запрет.
        Envelope env = new Envelope(a, b);
        env.expandBy(NewNetworkClearance.MIN_GAP_M);
        @SuppressWarnings("unchecked")
        List<LineString> near = index.query(env);
        if (near.isEmpty()) {
            return false;
        }
        org.locationtech.jts.algorithm.RobustLineIntersector li = new org.locationtech.jts.algorithm.RobustLineIntersector();
        for (LineString other : near) {
            Coordinate p0 = other.getCoordinateN(0);
            Coordinate p1 = other.getCoordinateN(other.getNumPoints() - 1);
            li.computeIntersection(a, b, p0, p1);
            if (li.hasIntersection()) {
                if (li.getIntersectionNum() == 2
                        && li.getIntersection(0).distance(li.getIntersection(1)) > 1e-6) {
                    return true; // коллинеарное наложение
                }
                for (int k = 0; k < li.getIntersectionNum(); k++) {
                    Coordinate c = li.getIntersection(k);
                    boolean ok1 = allowedTouch != null && c.distance(allowedTouch) <= FORBIDDEN_TOUCH_M;
                    boolean ok2 = allowedTouch2 != null && c.distance(allowedTouch2) <= FORBIDDEN_TOUCH_M;
                    if (!ok1 && !ok2) {
                        return true;
                    }
                }
            }
            if (NewNetworkClearance.tooCloseSegments(a, b, p0, p1, allowedTouch, allowedTouch2)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Мемо «ребро графа пересекает построенную сеть» на время одного набора запретов.
     * AUDIT-13 (Claude, 25.09): примитивная таблица с перемешиванием ключа вместо {@code HashMap<Long, Boolean>}
     * (см. {@link LongKeyMaps}).
     */
    private final LongKeyMaps.LongFlagMap forbiddenEdgeMemo = new LongKeyMaps.LongFlagMap(4096);

    private boolean edgeForbidden(int from, int to, Coordinate a, Coordinate b) {
        if (forbiddenIndex == null) {
            return false;
        }
        long key = ((long) Math.min(from, to) << 32) | (Math.max(from, to) & 0xffffffffL);
        Boolean memo = forbiddenEdgeMemo.get(key);
        if (memo != null) {
            return memo.booleanValue();
        }
        boolean r = crossesForbidden(a, b, null, null);
        forbiddenEdgeMemo.put(key, r);
        return r;
    }

    public void setFocus(List<Coordinate> focusPoints) {
        this.focus = focusPoints == null ? java.util.Collections.<Coordinate>emptyList()
                : new ArrayList<>(focusPoints);
    }

    /** @return false, если граф не собрался целиком — такой индекс использовать нельзя */
    public boolean build(Envelope area) {
        ready = false;
        linkCache.clear();
        if (area == null) {
            return false;
        }
        List<Coordinate> vertices = convexVertices(area);
        log.info("Вершин графа дворов до прореживания: {}", vertices.size());
        if (vertices.size() > MAX_VERTICES) {
            int step = vertices.size() / MAX_VERTICES + 1;
            List<Coordinate> thinned = new ArrayList<>();
            for (int i = 0; i < vertices.size(); i += step) {
                thinned.add(vertices.get(i));
            }
            vertices = thinned;
        }
        log.info("Вершин графа дворов: {}", vertices.size());
        VisibilityGraph.Builder builder = VisibilityGraph.builder();
        for (int i = 0; i < vertices.size(); i++) {
            builder.addVertex(new VisibilityVertex(i, vertices.get(i), VisibilityVertex.Kind.OBSTACLE));
        }
        if (vertices.isEmpty()) {
            graph = builder.build();
            ready = true;
            return true;
        }
        STRtree vertexIndex = new STRtree();
        for (int i = 0; i < vertices.size(); i++) {
            vertexIndex.insert(new Envelope(vertices.get(i)), Integer.valueOf(i));
        }
        vertexIndex.build();
        long deadline = System.currentTimeMillis() + BUILD_BUDGET_MS;
        for (int i = 0; i < vertices.size(); i++) {
            if (System.currentTimeMillis() > deadline) {
                log.info("Граф дворов оборван на вершине {} из {}", i, vertices.size());
                return false;
            }
            Coordinate from = vertices.get(i);
            Envelope search = new Envelope(from);
            search.expandBy(MAX_EDGE_M);
            @SuppressWarnings("unchecked")
            List<Integer> neighbours = vertexIndex.query(search);
            for (Integer jObj : neighbours) {
                int j = jObj.intValue();
                if (j <= i) {
                    continue;
                }
                Coordinate to = vertices.get(j);
                double cost = edgeCost(from, to);
                if (Double.isNaN(cost)) {
                    continue;
                }
                builder.addEdge(i, j, cost);
                builder.addEdge(j, i, cost);
            }
        }
        graph = builder.build();
        this.vertexIndex = new STRtree();
        for (int i = 0; i < graph.size(); i++) {
            this.vertexIndex.insert(new Envelope(graph.vertex(i).coordinate()), Integer.valueOf(i));
        }
        this.vertexIndex.build();
        ready = !graph.isPartial() && graph.size() == vertices.size();
        return ready;
    }

    /**
     * Кратчайшие допустимые пути от набора источников до каждой цели.
     * Вес — длина × Kспец плюс {@link Seed#getExtraCost()}. Поворот &gt; 90° отбрасывается.
     */
    public List<RouteHit> cheapest(List<Seed> seeds, List<Coordinate> targets) {
        List<Target> wrapped = new ArrayList<>();
        if (targets != null) {
            for (Coordinate target : targets) {
                wrapped.add(target == null ? null : new Target(target, null));
            }
        }
        return cheapestTo(seeds, wrapped);
    }

    /**
     * То же, что {@link #cheapest(List, List)}, но у цели может быть задана следующая точка трассы
     * ({@link Target#getNext()}): поворот в самой цели тоже не больше 90° (§2.1). Так финальный
     * прямой заход к точке ОКС (§2.2) стыкуется с трассой без разворота.
     */
    public List<RouteHit> cheapestTo(List<Seed> seedsIn, List<Target> targetSpecs) {
        List<RouteHit> hits = new ArrayList<>();
        if (!ready || seedsIn == null || seedsIn.isEmpty() || targetSpecs == null || targetSpecs.isEmpty()) {
            return hits;
        }
        // AUDIT-13 (Claude, 25.09): семя, «запертое» по пробе 5 см (см. escape), но имеющее допустимые прямые звенья к
        // вершинам графа, раньше заменялось точкой «выхода» — первым свободным направлением по кругу (4–36 м). У врезки
        // в трубу на краю дороги проба 5 см внутрь дороги короче допуска пересечения и считается «ходом вдоль дороги»,
        // поэтому врезка «заперта»; выход уводил ветку на 8 м внутрь дороги, и прямой перпендикулярный спуск через
        // дорогу и газопровод терялся (синтетика s1, ОКС 1056: 257 м вместо 237 м). Теперь у такого семени
        // рассматриваются оба начала: через точку выхода (как раньше) и прямо из самой точки (виртуальное семя с тем же
        // индексом в результате). Поиск только расширяется — найденный путь не может стать дороже.
        List<Seed> seeds = new ArrayList<>(seedsIn);
        List<Integer> ownerList = new ArrayList<>();
        List<Coordinate> escapedList = new ArrayList<>();
        for (int s = 0; s < seedsIn.size(); s++) {
            ownerList.add(s);
            Seed seed = seedsIn.get(s);
            Coordinate origin = seed == null || seed.getPoint() == null ? null : escape(seed.getPoint());
            escapedList.add(origin);
        }
        for (int s = 0; s < seedsIn.size(); s++) {
            Seed seed = seedsIn.get(s);
            Coordinate origin = escapedList.get(s);
            if (seed != null && origin != null && origin != seed.getPoint() && !links(seed.getPoint()).isEmpty()) {
                seeds.add(seed);
                ownerList.add(s);
                escapedList.add(seed.getPoint());
            }
        }
        int[] owner = new int[ownerList.size()];
        for (int i = 0; i < owner.length; i++) {
            owner[i] = ownerList.get(i);
        }
        List<Coordinate> targets = new ArrayList<>(targetSpecs.size());
        for (Target spec : targetSpecs) {
            targets.add(spec == null ? null : spec.getPoint());
        }
        for (int i = 0; i < targets.size(); i++) {
            hits.add(null);
        }
        // AUDIT-13 (Claude, 25.09): состояния «(откуда, где)» — в примитивных таблицах с перемешиванием ключа
        // (см. LongKeyMaps): у HashMap<Long, …> ключи с одинаковым «prev XOR node» попадали в одну корзину.
        LongKeyMaps.LongDoubleMap dist = new LongKeyMaps.LongDoubleMap(1 << 14);
        LongKeyMaps.LongLongMap parent = new LongKeyMaps.LongLongMap(1 << 14);
        PriorityQueue<State> queue = new PriorityQueue<>(Comparator.comparingDouble(s -> s.dist));

        Coordinate[] escaped = new Coordinate[seeds.size()];
        for (int s = 0; s < seeds.size(); s++) {
            Seed seed = seeds.get(s);
            if (seed == null || seed.getPoint() == null) {
                continue;
            }
            Coordinate origin = escapedList.get(s);
            if (origin == null) {
                origin = seed.getPoint();
            }
            if (origin != seed.getPoint() && crossesForbidden(seed.getPoint(), origin, seed.getPoint())) {
                continue; // выход из зоны пересёк бы уже построенную сеть
            }
            escaped[s] = origin;
            for (Link link : links(origin)) {
                if (!headingOk(seed, origin, graph.vertex(link.vertex).coordinate())) {
                    continue;
                }
                if (crossesForbidden(origin, graph.vertex(link.vertex).coordinate(), seed.getPoint())) {
                    continue;
                }
                long key = stateKey(-s - 1, link.vertex);
                double cost = seed.getExtraCost() + seed.getPoint().distance(origin) + link.cost;
                double known = dist.get(key, Double.POSITIVE_INFINITY);
                if (cost + 1e-9 < known) {
                    dist.put(key, cost);
                    queue.add(new State(key, link.vertex, -s - 1, cost));
                }
            }
        }

        double[] bestCost = new double[targets.size()];
        long[] bestKey = new long[targets.size()];
        java.util.Arrays.fill(bestCost, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(bestKey, Long.MIN_VALUE);
        boolean[] direct = new boolean[targets.size()];
        int[] directSeed = new int[targets.size()];

        for (int t = 0; t < targets.size(); t++) {
            Coordinate target = targets.get(t);
            if (target == null) {
                continue;
            }
            for (int s = 0; s < seeds.size(); s++) {
                Seed seed = seeds.get(s);
                if (seed == null || seed.getPoint() == null) {
                    continue;
                }
            if (!headingOk(seed, seed.getPoint(), target)
                    || !exitOk(seed.getPoint(), target, targetSpecs.get(t).getNext())
                    || crossesForbidden(seed.getPoint(), target, seed.getPoint(), target)) {
                continue;
            }
            double hop = edgeCost(seed.getPoint(), target);
            if (Double.isNaN(hop)) {
                continue;
            }
            double cost = seed.getExtraCost() + hop;
            if (cost < bestCost[t]) {
                bestCost[t] = cost;
                direct[t] = true;
                directSeed[t] = s;
            }
            }
        }

        List<List<TargetHop>> hopsByVertex = new ArrayList<>();
        for (int i = 0; i < graph.size(); i++) {
            hopsByVertex.add(new ArrayList<TargetHop>());
        }
        int emptyTargets = 0;
        double nearest = Double.POSITIVE_INFINITY;
        for (int t = 0; t < targets.size(); t++) {
            if (targets.get(t) == null) {
                continue;
            }
            List<Link> gateLinks = links(targets.get(t));
            if (gateLinks.isEmpty()) {
                emptyTargets++;
                if (nearest == Double.POSITIVE_INFINITY && graph.size() > 0) {
                    for (int v = 0; v < graph.size(); v++) {
                        nearest = Math.min(nearest, targets.get(t).distance(graph.vertex(v).coordinate()));
                    }
                }
            }
            for (Link link : gateLinks) {
                if (link.vertex >= 0 && link.vertex < hopsByVertex.size()) {
                    hopsByVertex.get(link.vertex).add(new TargetHop(t, link.cost));
                }
            }
        }
        if (emptyTargets > 0) {
            log.info("ROUTER emptyTargets={}/{} nearestVertexM={}", emptyTargets, targets.size(),
                    nearest == Double.POSITIVE_INFINITY ? -1 : Math.round(nearest));
        }

        double bestAny = Double.POSITIVE_INFINITY;
        for (double c : bestCost) {
            bestAny = Math.min(bestAny, c);
        }
        while (!queue.isEmpty()) {
            State state = queue.poll();
            double known = dist.get(state.key, Double.NaN);
            if (Double.isNaN(known) || state.dist > known + 1e-9) {
                continue;
            }
            // AUDIT-24.09 (Claude): ранний останов. Планировщику нужны самые дешёвые попытки; всё, что
            // дороже лучшей найденной вдвое (+ запас), в дерево не попадёт. Раньше Дейкстра всякий раз
            // проходила весь граф состояний «(откуда, куда)» — секунды на каждый шаг дерева.
            if (state.dist > EARLY_STOP_FACTOR * bestAny + EARLY_STOP_SLACK_M) {
                break;
            }
            bestAny = Math.min(bestAny,
                    considerTargets(state, hopsByVertex, targetSpecs, seeds, escaped, bestCost, bestKey, direct));
            if (graph.size() == 0) {
                continue;
            }
            Coordinate at = graph.vertex(state.node).coordinate();
            Coordinate prevCoord = prevCoordinate(state.prevCode, seeds, escaped);
            for (VisibilityGraph.Edge edge : graph.edgesFrom(state.node)) {
                Coordinate nextCoord = graph.vertex(edge.to()).coordinate();
                if (prevCoord != null && RoutingGeometry.turnSharperThan90(prevCoord, at, nextCoord)) {
                    continue;
                }
                if (edgeForbidden(state.node, edge.to(), at, nextCoord)) {
                    continue;
                }
                double candidate = state.dist + edge.cost();
                long nextKey = stateKey(state.node, edge.to());
                double best = dist.get(nextKey, Double.POSITIVE_INFINITY);
                if (candidate + 1e-9 < best) {
                    dist.put(nextKey, candidate);
                    parent.put(nextKey, state.key);
                    queue.add(new State(nextKey, edge.to(), state.node, candidate));
                }
            }
        }

        for (int t = 0; t < targets.size(); t++) {
            if (bestCost[t] == Double.POSITIVE_INFINITY || targets.get(t) == null) {
                continue;
            }
            if (direct[t] && bestKey[t] == Long.MIN_VALUE) {
                Seed seed = seeds.get(directSeed[t]);
                List<Coordinate> path = new ArrayList<>();
                path.add(RoutingGeometry.copy(seed.getPoint()));
                path.add(RoutingGeometry.copy(targets.get(t)));
                hits.set(t, new RouteHit(owner[directSeed[t]], bestCost[t], path));
                continue;
            }
            if (bestKey[t] == Long.MIN_VALUE) {
                continue;
            }
            List<Coordinate> path = reconstruct(bestKey[t], parent, seeds, escaped, targets.get(t));
            if (path != null && path.size() >= 2) {
                int internal = seedIndexOf(bestKey[t], parent);
                hits.set(t, new RouteHit(internal >= 0 && internal < owner.length ? owner[internal] : internal,
                        bestCost[t], path));
            }
        }
        return hits;
    }

    /** @return наименьшая стоимость, записанная для какой-либо цели на этом шаге (или +∞) */
    private double considerTargets(State state, List<List<TargetHop>> hopsByVertex, List<Target> targets,
                                   List<Seed> seeds, Coordinate[] escaped, double[] bestCost, long[] bestKey,
                                   boolean[] direct) {
        double improved = Double.POSITIVE_INFINITY;
        if (state.node < 0 || state.node >= hopsByVertex.size()) {
            return improved;
        }
        Coordinate at = graph.vertex(state.node).coordinate();
        Coordinate prevCoord = prevCoordinate(state.prevCode, seeds, escaped);
        for (TargetHop hop : hopsByVertex.get(state.node)) {
            Target spec = targets.get(hop.target);
            if (spec == null) {
                continue;
            }
            Coordinate target = spec.getPoint();
            if (prevCoord != null && RoutingGeometry.turnSharperThan90(prevCoord, at, target)) {
                continue;
            }
            if (!exitOk(at, target, spec.getNext())) {
                continue;
            }
            if (crossesForbidden(at, target, target)) {
                continue;
            }
            double cost = state.dist + hop.cost;
            if (cost + 1e-9 < bestCost[hop.target]) {
                bestCost[hop.target] = cost;
                bestKey[hop.target] = state.key;
                direct[hop.target] = false;
                improved = Math.min(improved, cost);
            }
        }
        return improved;
    }

    private List<Coordinate> reconstruct(long endKey, LongKeyMaps.LongLongMap parent, List<Seed> seeds,
                                        Coordinate[] escaped, Coordinate target) {
        List<Coordinate> path = new ArrayList<>();
        path.add(RoutingGeometry.copy(target));
        long cursor = endKey;
        boolean present = true;
        int guard = 0;
        while (present && guard++ < graph.size() + seeds.size() + 2) {
            int node = (int) cursor;
            if (node < 0 || node >= graph.size()) {
                return null;
            }
            path.add(0, graph.vertex(node).coordinate());
            int prev = prevCode(cursor);
            if (prev < 0) {
                int seedIndex = -prev - 1;
                if (seedIndex < 0 || seedIndex >= seeds.size()) {
                    return null;
                }
                Coordinate tie = seeds.get(seedIndex).getPoint();
                Coordinate via = seedIndex < escaped.length ? escaped[seedIndex] : null;
                if (via != null && via.distance(tie) > 0.5
                        && via.distance(path.get(0)) > 0.5) {
                    path.add(0, RoutingGeometry.copy(via));
                }
                path.add(0, RoutingGeometry.copy(tie));
                break;
            }
            if (parent.containsKey(cursor)) {
                cursor = parent.get(cursor, 0L);
            } else {
                present = false;
            }
        }
        return path;
    }

    private int seedIndexOf(long endKey, LongKeyMaps.LongLongMap parent) {
        long cursor = endKey;
        int guard = 0;
        while (guard++ < graph.size() + 2) {
            int prev = prevCode(cursor);
            if (prev < 0) {
                return -prev - 1;
            }
            if (!parent.containsKey(cursor)) {
                break;
            }
            cursor = parent.get(cursor, 0L);
        }
        return -1;
    }

    private Coordinate prevCoordinate(int prevCode, List<Seed> seeds, Coordinate[] escaped) {
        if (prevCode >= 0) {
            return graph.vertex(prevCode).coordinate();
        }
        int seedIndex = -prevCode - 1;
        if (seedIndex < 0 || seedIndex >= seeds.size()) {
            return null;
        }
        if (escaped != null && seedIndex < escaped.length && escaped[seedIndex] != null) {
            return escaped[seedIndex];
        }
        return seeds.get(seedIndex).getPoint();
    }

    /** Выход из точки внутри дороги или буфера в свободное место, откуда виден граф. */
    private Coordinate escape(Coordinate origin) {
        if (origin == null) {
            return null;
        }
        // AUDIT-24.09 (Claude): раньше «заблокированность» точки проверялась отрезком 1,5 м строго на восток —
        // точка на границе зоны отступа (все вершины и узлы сети лежат на ней) считалась «запертой»,
        // и к пути добавлялся лишний «выход» до 36 м в сторону: отсюда зигзаги у начала ветвей и
        // пересечения собственной сети. Теперь точка «заперта», только если свободного направления нет
        // даже на 5 см.
        boolean enclosed = true;
        for (int k = 0; k < 8 && enclosed; k++) {
            double ang = k * Math.PI / 4.0;
            LineString tiny = RoutingGeometry.line(origin,
                    new Coordinate(origin.x + Math.cos(ang) * 0.05, origin.y + Math.sin(ang) * 0.05));
            try {
                if (!engine.isSegmentBlocked(tiny, dn)) {
                    enclosed = false;
                }
            } catch (RuntimeException ex) {
                enclosed = false;
            }
        }
        if (!enclosed) {
            return origin;
        }
        for (int k = 0; k < 16; k++) {
            double ang = k * Math.PI / 8.0;
            double ux = Math.cos(ang);
            double uy = Math.sin(ang);
            for (double dist = 4.0; dist <= 36.0; dist += 4.0) {
                Coordinate p = new Coordinate(origin.x + ux * dist, origin.y + uy * dist);
                LineString hop = RoutingGeometry.line(origin, p);
                try {
                    if (!engine.isSegmentBlocked(hop, dn)) {
                        return p;
                    }
                } catch (RuntimeException ex) {
                    break;
                }
            }
        }
        return origin;
    }

    private List<Link> links(Coordinate point) {
        String key = RoutingGeometry.quantizeKey(point, 0.25);
        List<Link> cached = linkCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<Link> links = new ArrayList<>();
        if (graph.size() > 0) {
            Envelope search = new Envelope(point);
            search.expandBy(MAX_EDGE_M);
            @SuppressWarnings("unchecked")
            List<Integer> nearby = vertexIndex.query(search);
            for (Integer id : nearby) {
                int i = id.intValue();
                double cost = edgeCost(point, graph.vertex(i).coordinate());
                if (!Double.isNaN(cost)) {
                    links.add(new Link(i, cost));
                }
            }
        }
        linkCache.put(key, links);
        return links;
    }

    private static Link linkTo(List<Link> links, int vertex) {
        for (Link link : links) {
            if (link.vertex == vertex) {
                return link;
            }
        }
        return null;
    }

    private double edgeCost(Coordinate from, Coordinate to) {
        if (from == null || to == null) {
            return Double.NaN;
        }
        double length = from.distance(to);
        if (length < rules.getGeometryToleranceM() || length > MAX_EDGE_M) {
            return Double.NaN;
        }
        LineString segment = RoutingGeometry.line(from, to);
        // Контуры — только площадные запреты; коридоры линейных ограничений (газ, кабель,
        // существующая теплосеть) в них не входят, поэтому ребро проверяется движком всегда.
        // Заблокированное ребро не допускается ни при какой длине (§4, табл. 2).
        try {
            if (engine.isSegmentBlocked(segment, dn)) {
                return Double.NaN;
            }
        } catch (RuntimeException ex) {
            return Double.NaN;
        }
        // AUDIT-13 (Claude, 25.09): Kспец — только на спецучастке звена (как в смете M6), а не на всей длине.
        List<SpecialSection> sections = engine.extractSpecialSections(segment, dn);
        return SpecialCost.weightedLength(segment, sections);
    }

    private static boolean headingOk(Seed seed, Coordinate at, Coordinate next) {
        return seed.getHeading() == null
                || RoutingGeometry.deviationDeg(seed.getHeading(), at, next) <= TurnRepair.MAX_TURN_DEG + 1e-6;
    }

    private static boolean exitOk(Coordinate prev, Coordinate target, Coordinate next) {
        return next == null
                || RoutingGeometry.deviationDeg(prev, target, next) <= TurnRepair.MAX_TURN_DEG + 1e-6;
    }

    private List<Coordinate> convexVertices(Envelope area) {
        List<Coordinate> convex = new ArrayList<>();
        List<Coordinate> skirt = new ArrayList<>();
        Envelope grown = new Envelope(area);
        grown.expandBy(rules.getRoutingWorkspaceMarginM());
        for (Geometry outline : blockedOutlines) {
            if (outline == null || outline.isEmpty() || !outline.getEnvelopeInternal().intersects(grown)) {
                continue;
            }
            Geometry simplified = outline;
            try {
                simplified = DouglasPeuckerSimplifier.simplify(outline, 1.5);
            } catch (RuntimeException ex) {
                simplified = outline;
            }
            collectConvex(simplified, convex, grown);
            addSkirt(outline, skirt, grown);
        }
        List<Coordinate> out = new ArrayList<>(skirt);
        out.addAll(convex);
        // AUDIT-24.09 (Claude): вершина графа = возможный поворот трассы. Поворот внутри зоны
        // спецпрохода (дорога/трамвай + 3 м, газ/кабель/чужая теплосеть ± 2 м) делает спецпроход
        // ломаным, а табл. 2/§4 требуют «одним прямым участком». Такие вершины исключаются.
        List<Coordinate> allowed = new ArrayList<>(out.size());
        for (Coordinate c : out) {
            boolean inside;
            try {
                inside = engine.isInsideSpecialZone(c);
            } catch (RuntimeException ex) {
                inside = true;
            }
            if (!inside) {
                allowed.add(c);
            }
        }
        out = RoutingGeometry.deduplicate(allowed, 0.5);
        if (out.size() > MAX_VERTICES) {
            final List<Coordinate> f = focus;
            if (f.isEmpty()) {
                Coordinate centre = area.centre();
                out.sort(java.util.Comparator.comparingDouble(c -> c.distance(centre)));
            } else {
                STRtree focusIndex = new STRtree();
                for (Coordinate c : f) {
                    focusIndex.insert(new Envelope(c), c);
                }
                focusIndex.build();
                Map<Coordinate, Double> dist = new java.util.IdentityHashMap<>();
                for (Coordinate c : out) {
                    Object nearest = focusIndex.nearestNeighbour(new Envelope(c), c,
                            (a, b) -> ((Coordinate) a.getItem()).distance((Coordinate) b.getItem()));
                    dist.put(c, nearest == null ? 0.0 : ((Coordinate) nearest).distance(c));
                }
                out.sort(java.util.Comparator.comparingDouble(dist::get));
            }
            log.info("Вершин графа {} > {} — оставлены ближайшие к ОКС и сети", out.size(), MAX_VERTICES);
            out = new ArrayList<>(out.subList(0, MAX_VERTICES));
        }
        return out;
    }

    /** Точки в 2 м снаружи запретной зоны, чтобы к воротам у стены был короткий видимый шаг. */
    private void addSkirt(Geometry outline, List<Coordinate> out, Envelope area) {
        Geometry skirt;
        try {
            skirt = outline.buffer(2.0);
        } catch (RuntimeException ex) {
            return;
        }
        addSkirtGeometry(skirt, out, area);
    }

    private void addSkirtGeometry(Geometry geometry, List<Coordinate> out, Envelope area) {
        if (geometry instanceof Polygon) {
            sampleRing(((Polygon) geometry).getExteriorRing(), out, area);
            return;
        }
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            addSkirtGeometry(geometry.getGeometryN(i), out, area);
        }
    }

    private void sampleRing(LineString ring, List<Coordinate> out, Envelope area) {
        Coordinate[] coords = ring.getCoordinates();
        if (coords.length < 2) {
            return;
        }
        double carry = 0.0;
        final double step = 20.0;
        for (int i = 1; i < coords.length; i++) {
            Coordinate a = coords[i - 1];
            Coordinate b = coords[i];
            double len = a.distance(b);
            if (len < 1e-6) {
                continue;
            }
            double pos = step - carry;
            while (pos <= len) {
                double t = pos / len;
                Coordinate p = new Coordinate(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
                if (area.contains(p)) {
                    out.add(p);
                }
                pos += step;
            }
            carry = len - (pos - step);
            if (carry < 0) {
                carry = 0;
            }
        }
    }

    private void collectConvex(Geometry geometry, List<Coordinate> out, Envelope area) {
        if (geometry instanceof Polygon) {
            addPolygon((Polygon) geometry, out, area);
            return;
        }
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            collectConvex(geometry.getGeometryN(i), out, area);
        }
    }

    private void addPolygon(Polygon polygon, List<Coordinate> out, Envelope area) {
        addRing(polygon, polygon.getExteriorRing(), false, out, area);
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            addRing(polygon, polygon.getInteriorRingN(i), true, out, area);
        }
    }

    private void addRing(Polygon polygon, LineString ring, boolean hole, List<Coordinate> out, Envelope area) {
        Coordinate[] cs = ring.getCoordinates();
        if (cs.length < 4) {
            return;
        }
        boolean ccw = Orientation.isCCW(cs);
        int n = cs.length - 1;
        for (int i = 0; i < n; i++) {
            Coordinate a = cs[(i - 1 + n) % n];
            Coordinate b = cs[i];
            Coordinate c = cs[(i + 1) % n];
            double cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
            boolean convex = ccw ? cross > 0 : cross < 0;
            if (hole) {
                convex = !convex;
            }
            if (!convex || !area.contains(b)) {
                continue;
            }
            // AUDIT-13 (Claude, 25.09): сдвиг по биссектрисе угла наружу, а не «от центроида» (см. RoutingGeometry).
            Coordinate pushed = RoutingGeometry.outwardVertex(a, b, c, polygon, rules.getRoutingVertexOutwardM());
            try {
                if (polygon.contains(RoutingGeometry.point(pushed))) {
                    continue;
                }
            } catch (RuntimeException ex) {
                continue;
            }
            out.add(pushed);
        }
    }

    private static long stateKey(int prevCode, int node) {
        return (((long) prevCode + 1L) << 32) | (node & 0xffffffffL);
    }

    private static int prevCode(long key) {
        return (int) (key >>> 32) - 1;
    }

    /** Источник пути: точка существующей сети или уже построенного участка. */
    public static final class Seed {
        private final Coordinate point;
        private final double extraCost;
        private final int tag;
        private final Coordinate heading;

        public Seed(Coordinate point, double extraCost, int tag) {
            this(point, extraCost, tag, null);
        }

        /**
         * @param heading точка перед семенем на питающем участке: первое звено новой ветки
         *                отклоняется от его продолжения не больше чем на 90° (§2.1)
         */
        public Seed(Coordinate point, double extraCost, int tag, Coordinate heading) {
            this.point = point;
            this.extraCost = extraCost;
            this.tag = tag;
            this.heading = heading;
        }

        public Coordinate getPoint() {
            return point;
        }

        public Coordinate getHeading() {
            return heading;
        }

        public double getExtraCost() {
            return extraCost;
        }

        public int getTag() {
            return tag;
        }
    }

    /** Цель поиска и (необязательно) следующая за ней точка трассы. */
    public static final class Target {
        private final Coordinate point;
        private final Coordinate next;

        public Target(Coordinate point, Coordinate next) {
            this.point = point;
            this.next = next;
        }

        public Coordinate getPoint() {
            return point;
        }

        public Coordinate getNext() {
            return next;
        }
    }

    /** Путь от семени {@code seedTag} до цели, включая оба конца. */
    public static final class RouteHit {
        private final int seedTag;
        private final double cost;
        private final List<Coordinate> path;

        RouteHit(int seedTag, double cost, List<Coordinate> path) {
            this.seedTag = seedTag;
            this.cost = cost;
            this.path = path;
        }

        public int getSeedTag() {
            return seedTag;
        }

        public double getCost() {
            return cost;
        }

        public List<Coordinate> getPath() {
            return path;
        }
    }

    private static final class TargetHop {
        private final int target;
        private final double cost;

        private TargetHop(int target, double cost) {
            this.target = target;
            this.cost = cost;
        }
    }

    private static final class Link {
        private final int vertex;
        private final double cost;

        private Link(int vertex, double cost) {
            this.vertex = vertex;
            this.cost = cost;
        }
    }

    private static final class State {
        private final long key;
        private final int node;
        private final int prevCode;
        private final double dist;

        private State(long key, int node, int prevCode, double dist) {
            this.key = key;
            this.node = node;
            this.prevCode = prevCode;
            this.dist = dist;
        }
    }
}
