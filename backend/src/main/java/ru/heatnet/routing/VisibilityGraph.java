package ru.heatnet.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Невзвешенный граф видимости с весами рёбер для Dijkstra. */
final class VisibilityGraph {

    static final class Edge {
        private final int to;
        private final double cost;

        Edge(int to, double cost) {
            this.to = to;
            this.cost = cost;
        }

        int to() {
            return to;
        }

        double cost() {
            return cost;
        }
    }

    private final List<VisibilityVertex> vertices;
    private final List<List<Edge>> adjacency;
    private final boolean partial;

    VisibilityGraph(List<VisibilityVertex> vertices, List<List<Edge>> adjacency, boolean partial) {
        this.vertices = Collections.unmodifiableList(vertices);
        this.adjacency = adjacency;
        this.partial = partial;
    }

    List<VisibilityVertex> vertices() {
        return vertices;
    }

    List<Edge> edgesFrom(int vertexId) {
        return adjacency.get(vertexId);
    }

    int size() {
        return vertices.size();
    }

    /** true, если построение оборвано по дедлайну — граф неполный, Дейкстра по нему ненадёжна. */
    boolean isPartial() {
        return partial;
    }

    VisibilityVertex vertex(int id) {
        return vertices.get(id);
    }

    static VisibilityGraph empty() {
        return new VisibilityGraph(Collections.<VisibilityVertex>emptyList(),
                Collections.<List<Edge>>emptyList(), false);
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private final List<VisibilityVertex> vertices = new ArrayList<>();
        private final Map<Integer, List<Edge>> edges = new HashMap<>();
        private boolean partial;

        void addVertex(VisibilityVertex vertex) {
            vertices.add(vertex);
            edges.put(vertex.id(), new ArrayList<Edge>());
        }

        void addEdge(int from, int to, double cost) {
            edges.get(from).add(new Edge(to, cost));
        }

        void markPartial() {
            this.partial = true;
        }

        VisibilityGraph build() {
            List<List<Edge>> adjacency = new ArrayList<>(vertices.size());
            for (VisibilityVertex v : vertices) {
                adjacency.add(edges.get(v.id()));
            }
            return new VisibilityGraph(vertices, adjacency, partial);
        }
    }
}
