package ru.heatnet.routing;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import ru.heatnet.calc.TestReference;

/** §2.1: поворот больше 90° не выбирается, прямой ход не штрафуется как разворот. */
class GraphPathSearcherTurnTest {

    @Test
    @DisplayName("разворот около 180° не является маршрутом")
    void hairpinIsRejected() {
        VisibilityGraph.Builder builder = VisibilityGraph.builder();
        builder.addVertex(new VisibilityVertex(0, new Coordinate(0, 0), VisibilityVertex.Kind.START));
        builder.addVertex(new VisibilityVertex(1, new Coordinate(10, 0), VisibilityVertex.Kind.OBSTACLE));
        builder.addVertex(new VisibilityVertex(2, new Coordinate(1, 0), VisibilityVertex.Kind.END));
        builder.addEdge(0, 1, 10);
        builder.addEdge(1, 0, 10);
        builder.addEdge(1, 2, 9);
        builder.addEdge(2, 1, 9);
        GraphPathSearcher searcher = new GraphPathSearcher(
                builder.build(), null, TestReference.get().getRules(), 100,
                System.currentTimeMillis() + 5_000L);
        assertNull(searcher.findPath(0, 2));
    }

    @Test
    @DisplayName("прямой ход и поворот 90° допустимы")
    void straightAndRightAnglePass() {
        VisibilityGraph.Builder builder = VisibilityGraph.builder();
        builder.addVertex(new VisibilityVertex(0, new Coordinate(0, 0), VisibilityVertex.Kind.START));
        builder.addVertex(new VisibilityVertex(1, new Coordinate(10, 0), VisibilityVertex.Kind.OBSTACLE));
        builder.addVertex(new VisibilityVertex(2, new Coordinate(10, 10), VisibilityVertex.Kind.END));
        builder.addEdge(0, 1, 10);
        builder.addEdge(1, 0, 10);
        builder.addEdge(1, 2, 10);
        builder.addEdge(2, 1, 10);
        GraphPathSearcher searcher = new GraphPathSearcher(
                builder.build(), null, TestReference.get().getRules(), 100,
                System.currentTimeMillis() + 5_000L);
        assertTrue(searcher.findPath(0, 2).size() >= 2);
        double deviation = RoutingGeometry.deviationDeg(
                new Coordinate(0, 0), new Coordinate(10, 0), new Coordinate(10, 10));
        assertTrue(Math.abs(deviation - 90.0) < 1e-6);
    }
}
