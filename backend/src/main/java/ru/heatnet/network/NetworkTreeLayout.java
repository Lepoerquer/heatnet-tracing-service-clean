package ru.heatnet.network;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/**
 * UTM-геометрия дерева новой сети (EPSG:32637). Держится в M4 параллельно {@link ru.heatnet.calc.model.NewNetworkTree}.
 */
public final class NetworkTreeLayout {

    private final Map<String, Coordinate> nodeCoordinates;
    private final Map<String, LineString> segmentGeometries;

    public NetworkTreeLayout() {
        this.nodeCoordinates = new LinkedHashMap<>();
        this.segmentGeometries = new LinkedHashMap<>();
    }

    private NetworkTreeLayout(Map<String, Coordinate> nodes, Map<String, LineString> segments) {
        this.nodeCoordinates = new LinkedHashMap<>(nodes);
        this.segmentGeometries = new LinkedHashMap<>(segments);
    }

    public void putNode(String nodeId, Coordinate utm) {
        nodeCoordinates.put(nodeId, copyCoord(utm));
    }

    public void putSegment(String segmentId, LineString lineUtm) {
        segmentGeometries.put(segmentId, lineUtm);
    }

    public Coordinate nodeCoordinate(String nodeId) {
        Coordinate c = nodeCoordinates.get(nodeId);
        return c == null ? null : copyCoord(c);
    }

    public LineString segmentLine(String segmentId) {
        return segmentGeometries.get(segmentId);
    }

    public Map<String, Coordinate> getNodeCoordinates() {
        return Collections.unmodifiableMap(nodeCoordinates);
    }

    public Map<String, LineString> getSegmentGeometries() {
        return Collections.unmodifiableMap(segmentGeometries);
    }

    /**
     * Полная копия раскладки.
     *
     * <p>QA-FIX M-7: геометрия копируется, а не делится по ссылке с исходным layout.
     * Раньше {@code copy()} копировал только координаты узлов, а {@code LineString}-ы оставались
     * общими: безопасно только пока никто не мутирует геометрию на месте.
     */
    public NetworkTreeLayout copy() {
        Map<String, Coordinate> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, Coordinate> e : nodeCoordinates.entrySet()) {
            nodes.put(e.getKey(), copyCoord(e.getValue()));
        }
        Map<String, LineString> segments = new LinkedHashMap<>();
        for (Map.Entry<String, LineString> e : segmentGeometries.entrySet()) {
            LineString line = e.getValue();
            segments.put(e.getKey(), line == null ? null : (LineString) line.copy());
        }
        return new NetworkTreeLayout(nodes, segments);
    }

    void removeSegment(String segmentId) {
        segmentGeometries.remove(segmentId);
    }

    void removeNode(String nodeId) {
        nodeCoordinates.remove(nodeId);
    }

    private static Coordinate copyCoord(Coordinate c) {
        return new Coordinate(c.x, c.y, c.getZ());
    }
}
