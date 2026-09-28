package ru.heatnet.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.locationtech.jts.geom.Coordinate;

import ru.heatnet.calc.reference.RulesConfig;
import ru.heatnet.rules.SpatialConstraintEngine;

/**
 * Dijkstra по visibility graph. Вес ребра (длина × K_спец) задан при построении графа.
 * §2.1: поворот — отклонение от прямой, допустимо до 90° включительно. Разворот не выбирается.
 * Штраф {@code turn_penalty_m} только за реальный излом, не за прямое продолжение.
 */
final class GraphPathSearcher {

    private final VisibilityGraph graph;
    private final RulesConfig rules;
    private final long deadlineMs;

    GraphPathSearcher(VisibilityGraph graph, SpatialConstraintEngine engine, RulesConfig rules, int dn) {
        this(graph, engine, rules, dn, System.currentTimeMillis() + 15_000L);
    }

    GraphPathSearcher(VisibilityGraph graph, SpatialConstraintEngine engine, RulesConfig rules, int dn,
                      long deadlineMs) {
        this.graph = graph;
        this.rules = rules;
        this.deadlineMs = deadlineMs;
    }

    List<Coordinate> findPath(int startId, int endId) {
        if (graph.size() == 0 || startId < 0 || endId < 0 || startId >= graph.size() || endId >= graph.size()) {
            return null;
        }
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, Long> parentKey = new HashMap<>();
        long startKey = stateKey(-1, startId);
        dist.put(startKey, 0.0);

        PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingDouble(n -> n.dist));
        queue.add(new Node(startKey, startId, -1, 0.0));
        Long bestEnd = null;

        while (!queue.isEmpty()) {
            if (System.currentTimeMillis() > deadlineMs) {
                break;
            }
            Node currentNode = queue.poll();
            Double known = dist.get(currentNode.key);
            if (known == null || currentNode.dist > known + 1e-12) {
                continue;
            }
            if (currentNode.node == endId) {
                bestEnd = currentNode.key;
                break;
            }
            Coordinate at = graph.vertex(currentNode.node).coordinate();
            Coordinate prevCoord = currentNode.prev >= 0 ? graph.vertex(currentNode.prev).coordinate() : null;

            for (VisibilityGraph.Edge edge : graph.edgesFrom(currentNode.node)) {
                int next = edge.to();
                Coordinate nextCoord = graph.vertex(next).coordinate();
                double step = edge.cost();
                if (prevCoord != null) {
                    double deviation = RoutingGeometry.deviationDeg(prevCoord, at, nextCoord);
                    if (deviation > TurnRepair.MAX_TURN_DEG + 1e-6) {
                        continue;
                    }
                    if (deviation > rules.getAngleToleranceDeg()) {
                        step += rules.getRoutingTurnPenaltyM();
                    }
                }
                double candidate = currentNode.dist + step;
                long nextKey = stateKey(currentNode.node, next);
                Double best = dist.get(nextKey);
                if (best == null || candidate + 1e-9 < best) {
                    dist.put(nextKey, candidate);
                    parentKey.put(nextKey, currentNode.key);
                    queue.add(new Node(nextKey, next, currentNode.node, candidate));
                }
            }
        }

        if (bestEnd == null) {
            return null;
        }
        return reconstruct(bestEnd, parentKey);
    }

    private List<Coordinate> reconstruct(long endKey, Map<Long, Long> parentKey) {
        List<Coordinate> path = new ArrayList<>();
        Long cursor = endKey;
        int guard = 0;
        while (cursor != null && guard++ < graph.size() + 2) {
            int node = (int) cursor.longValue();
            path.add(0, graph.vertex(node).coordinate());
            cursor = parentKey.get(cursor);
        }
        return path.size() >= 2 ? path : null;
    }

    /** prev+1 в старших битах, узел — в младших. prev = -1 у старта. */
    private static long stateKey(int prev, int node) {
        return (((long) prev + 1L) << 32) | (node & 0xffffffffL);
    }

    private static final class Node {
        private final long key;
        private final int node;
        private final int prev;
        private final double dist;

        private Node(long key, int node, int prev, double dist) {
            this.key = key;
            this.node = node;
            this.prev = prev;
            this.dist = dist;
        }
    }
}
