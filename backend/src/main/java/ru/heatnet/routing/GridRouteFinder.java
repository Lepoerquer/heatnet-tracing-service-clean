package ru.heatnet.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;

import ru.heatnet.calc.reference.RulesConfig;
import ru.heatnet.rules.SpatialConstraintEngine;

/**
 * Fallback: A* по регулярной сетке 1–2 м над адаптивной рабочей областью.
 */
final class GridRouteFinder {

    private final SpatialConstraintEngine engine;
    private final List<Geometry> blockedOutlines;
    private final RulesConfig rules;
    private final int dn;

    GridRouteFinder(SpatialConstraintEngine engine, List<Geometry> blockedOutlines,
                     RulesConfig rules, int dn) {
        this.engine = engine;
        this.blockedOutlines = blockedOutlines;
        this.rules = rules;
        this.dn = dn;
    }

    List<Coordinate> findPath(Coordinate start, Coordinate end, RoutingWorkspace workspace) {
        return findPath(start, end, workspace, null);
    }

    /**
     * @param keepOut §2.2: свой полигон ОКС. Любое ребро сетки, ведущее НЕ в конечную ячейку,
     *                не может его пересекать; последний шаг в конечную ячейку — законный
     *                прямой заход и от этой проверки освобождён (как и в
     *                {@link VisibilityGraphBuilder}).
     */
    List<Coordinate> findPath(Coordinate start, Coordinate end, RoutingWorkspace workspace, Geometry keepOut) {
        long deadline = System.currentTimeMillis() + Math.max(1L, rules.getRoutingGridDeadlineMs());
        Envelope env = workspace.envelope();
        double cell = rules.getRoutingGridCellM();
        int cols;
        int rows;
        do {
            cols = Math.max(2, (int) Math.ceil(env.getWidth() / cell) + 1);
            rows = Math.max(2, (int) Math.ceil(env.getHeight() / cell) + 1);
            if ((long) cols * rows <= 250_000L) {
                break;
            }
            cell *= 1.5;
        } while (cell <= 20.0);
        if ((long) cols * rows > 250_000L) {
            return null;
        }

        STRtree outlineIndex = buildOutlineIndex();

        boolean[][] blocked = new boolean[rows][cols];
        Coordinate[][] centers = new Coordinate[rows][cols];
        for (int r = 0; r < rows; r++) {
            if (System.currentTimeMillis() > deadline) {
                return null;
            }
            for (int c = 0; c < cols; c++) {
                double x = env.getMinX() + c * cell;
                double y = env.getMinY() + r * cell;
                Coordinate center = new Coordinate(x, y);
                centers[r][c] = center;
                blocked[r][c] = isBlockedCell(center, outlineIndex);
            }
        }

        int startCell = nearestFreeCell(start, centers, blocked, cell);
        int endCell = nearestFreeCell(end, centers, blocked, cell);
        if (startCell < 0 || endCell < 0) {
            return null;
        }

        int startR = startCell / cols;
        int startC = startCell % cols;
        int endR = endCell / cols;
        int endC = endCell % cols;

        double[] dist = new double[rows * cols];
        int[] prev = new int[rows * cols];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        int startIdx = startR * cols + startC;
        int endIdx = endR * cols + endC;
        dist[startIdx] = 0.0;

        PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingDouble(n -> n.dist));
        queue.add(new Node(startIdx, 0.0));

        int[][] dirs = new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

        while (!queue.isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                break;
            }
            Node current = queue.poll();
            int idx = current.id;
            if (current.dist > dist[idx] + 1e-12) {
                continue;
            }
            if (idx == endIdx) {
                break;
            }
            int r = idx / cols;
            int c = idx % cols;
            Coordinate from = centers[r][c];
            for (int[] d : dirs) {
                int nr = r + d[0];
                int nc = c + d[1];
                if (nr < 0 || nr >= rows || nc < 0 || nc >= cols || blocked[nr][nc]) {
                    continue;
                }
                Coordinate to = centers[nr][nc];
                LineString seg = RoutingGeometry.line(from, to);
                if (engine.isSegmentBlocked(seg, dn)) {
                    continue;
                }
                boolean enteringDestination = nr == endR && nc == endC;
                if (!enteringDestination && crossesKeepOut(seg, keepOut)) {
                    continue;
                }
                int nIdx = nr * cols + nc;
                if (prev[idx] >= 0) {
                    int pr = prev[idx] / cols;
                    int pc = prev[idx] % cols;
                    double deviation = RoutingGeometry.deviationDeg(centers[pr][pc], from, to);
                    if (deviation > TurnRepair.MAX_TURN_DEG + 1e-6) {
                        continue;
                    }
                }
                double step = seg.getLength();
                double candidate = dist[idx] + step;
                if (candidate + 1e-9 < dist[nIdx]) {
                    dist[nIdx] = candidate;
                    prev[nIdx] = idx;
                    queue.add(new Node(nIdx, candidate));
                }
            }
        }

        if (prev[endIdx] < 0 && startIdx != endIdx) {
            return null;
        }
        List<Coordinate> path = new ArrayList<>();
        int cursor = endIdx;
        while (cursor >= 0) {
            int rr = cursor / cols;
            int cc = cursor % cols;
            path.add(0, centers[rr][cc]);
            cursor = prev[cursor];
        }
        if (!path.isEmpty()) {
            path.set(0, RoutingGeometry.copy(start));
            path.set(path.size() - 1, RoutingGeometry.copy(end));
        }
        return path;
    }

    private STRtree buildOutlineIndex() {
        STRtree tree = new STRtree();
        for (Geometry outline : blockedOutlines) {
            if (outline == null || outline.isEmpty()) {
                continue;
            }
            tree.insert(outline.getEnvelopeInternal(), outline);
        }
        tree.build();
        return tree;
    }

    /** AUDIT-13: подготовленный свой полигон (на один поиск; сетка строится заново для каждого маршрута). */
    private org.locationtech.jts.geom.prep.PreparedGeometry keepOutPrepared;
    private Geometry keepOutPreparedFor;

    private boolean crossesKeepOut(LineString segment, Geometry keepOut) {
        if (keepOut == null || keepOut.isEmpty()) {
            return false;
        }
        try {
            // AUDIT-13 (Claude, 25.09): оверлей — только для звена, задевающего полигон (пустое пересечение ⇔ нет
            // intersects), как в VisibilityGraphBuilder.
            if (!segment.getEnvelopeInternal().intersects(keepOut.getEnvelopeInternal())) {
                return false;
            }
            if (keepOutPreparedFor != keepOut) {
                keepOutPrepared = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(keepOut);
                keepOutPreparedFor = keepOut;
            }
            if (!keepOutPrepared.intersects(segment)) {
                return false;
            }
            Geometry inter = segment.intersection(keepOut);
            return inter != null && !inter.isEmpty() && inter.getLength() > 1e-6;
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private boolean isBlockedCell(Coordinate center, STRtree outlineIndex) {
        Point p = RoutingGeometry.point(center);
        Envelope env = new Envelope(center);
        env.expandBy(1e-6);
        @SuppressWarnings("unchecked")
        List<Geometry> hits = outlineIndex.query(env);
        for (Geometry outline : hits) {
            if (outline.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private static int nearestFreeCell(Coordinate target, Coordinate[][] centers, boolean[][] blocked, double cell) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int r = 0; r < centers.length; r++) {
            for (int c = 0; c < centers[r].length; c++) {
                if (blocked[r][c]) {
                    continue;
                }
                double d = target.distance(centers[r][c]);
                if (d < bestDist) {
                    bestDist = d;
                    best = r * centers[r].length + c;
                }
            }
        }
        if (bestDist > cell * 5.0) {
            return -1;
        }
        return best;
    }

    private static final class Node {
        private final int id;
        private final double dist;

        private Node(int id, double dist) {
            this.id = id;
            this.dist = dist;
        }
    }
}
