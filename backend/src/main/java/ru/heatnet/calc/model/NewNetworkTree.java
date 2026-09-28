package ru.heatnet.calc.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ru.heatnet.calc.CalcException;

/**
 * Дерево новой сети от одной врезки до ОКС (контракт с network/).
 * Проверяется при создании: один корень-врезка, у каждого узла кроме корня ровно один
 * входящий участок, нет циклов, все узлы достижимы, ОКС — только листья.
 */
public final class NewNetworkTree {

    private final TieInPoint tieIn;
    private final Map<String, NewNode> nodes;
    private final Map<String, NewSegment> segments;
    private final Map<String, List<NewSegment>> childrenByNode;
    private final Map<String, NewSegment> incomingByNode;
    private final List<NewSegment> topDown;

    public NewNetworkTree(TieInPoint tieIn, List<NewNode> nodeList, List<NewSegment> segmentList) {
        this.tieIn = Objects.requireNonNull(tieIn, "tieIn");
        Map<String, NewNode> n = new LinkedHashMap<>();
        for (NewNode node : nodeList) {
            if (n.put(node.getId(), node) != null) {
                throw new CalcException("Дерево врезки " + tieIn.getId() + ": узел " + node.getId() + " указан дважды");
            }
        }
        NewNode root = n.get(tieIn.getId());
        if (root == null || root.getKind() != NodeKind.TIE_IN) {
            throw new CalcException("Дерево врезки " + tieIn.getId() + ": нет корневого узла TIE_IN с тем же id");
        }
        Map<String, NewSegment> s = new LinkedHashMap<>();
        Map<String, List<NewSegment>> children = new HashMap<>();
        Map<String, NewSegment> incoming = new HashMap<>();
        for (NewSegment seg : segmentList) {
            if (s.put(seg.getId(), seg) != null) {
                throw new CalcException("Дерево врезки " + tieIn.getId() + ": участок " + seg.getId() + " указан дважды");
            }
            NewNode from = n.get(seg.getFromNodeId());
            NewNode to = n.get(seg.getToNodeId());
            if (from == null || to == null) {
                throw new CalcException("Участок " + seg.getId() + " ссылается на несуществующий узел");
            }
            if (to.getKind() == NodeKind.TIE_IN) {
                throw new CalcException("Участок " + seg.getId() + " направлен в точку врезки — ожидается направление от врезки к ОКС");
            }
            if (from.getKind() == NodeKind.OKS_CONNECTION) {
                throw new CalcException("Участок " + seg.getId() + " выходит из точки подключения ОКС — ОКС должен быть листом");
            }
            if (incoming.put(seg.getToNodeId(), seg) != null) {
                throw new CalcException("Узел " + seg.getToNodeId() + " имеет более одного пути к врезке (цикл/не дерево)");
            }
            children.computeIfAbsent(seg.getFromNodeId(), k -> new ArrayList<>()).add(seg);
        }
        List<NewSegment> order = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(tieIn.getId());
        int visitedNodes = 0;
        while (!queue.isEmpty()) {
            String nodeId = queue.poll();
            visitedNodes++;
            for (NewSegment child : children.getOrDefault(nodeId, Collections.<NewSegment>emptyList())) {
                order.add(child);
                queue.add(child.getToNodeId());
            }
        }
        if (visitedNodes != n.size() || order.size() != s.size()) {
            throw new CalcException("Дерево врезки " + tieIn.getId() + ": не все узлы/участки связаны с врезкой");
        }
        List<NewSegment> rootChildren = children.getOrDefault(tieIn.getId(), Collections.<NewSegment>emptyList());
        if (rootChildren.size() != 1) {
            throw new CalcException("Врезка " + tieIn.getId() + " должна иметь ровно один новый луч, найдено " + rootChildren.size());
        }
        for (NewNode node : n.values()) {
            if (node.getKind() == NodeKind.OKS_CONNECTION) {
                if (!(node.getOksFlowTph() >= 0) || Double.isInfinite(node.getOksFlowTph())) {
                    throw new CalcException("ОКС " + node.getOksId() + ": некорректный flow_tph " + node.getOksFlowTph());
                }
            } else if (node.getKind() != NodeKind.TIE_IN && !children.containsKey(node.getId())) {
                throw new CalcException("Узел " + node.getId() + " (" + node.getKind() + ") — тупик без ОКС");
            }
        }
        this.nodes = Collections.unmodifiableMap(n);
        this.segments = Collections.unmodifiableMap(s);
        this.childrenByNode = children;
        this.incomingByNode = incoming;
        this.topDown = Collections.unmodifiableList(order);
    }

    public TieInPoint getTieIn() {
        return tieIn;
    }

    public NewNode node(String id) {
        NewNode node = nodes.get(id);
        if (node == null) {
            throw new CalcException("Узел " + id + " не найден");
        }
        return node;
    }

    public Map<String, NewNode> getNodes() {
        return nodes;
    }

    public Map<String, NewSegment> getSegments() {
        return segments;
    }

    /** Участки, выходящие из узла в сторону ОКС. */
    public List<NewSegment> childrenOf(String nodeId) {
        return Collections.unmodifiableList(childrenByNode.getOrDefault(nodeId, Collections.<NewSegment>emptyList()));
    }

    /** Участок, входящий в узел со стороны врезки; null для корня. */
    public NewSegment incomingOf(String nodeId) {
        return incomingByNode.get(nodeId);
    }

    /** Участок, выходящий из точки врезки. */
    public NewSegment rootSegment() {
        return childrenByNode.get(tieIn.getId()).get(0);
    }

    /** Все участки, примыкающие к узлу. */
    public List<NewSegment> incidentTo(String nodeId) {
        List<NewSegment> result = new ArrayList<>(childrenOf(nodeId));
        NewSegment in = incomingOf(nodeId);
        if (in != null) {
            result.add(in);
        }
        return result;
    }

    /** Участки в порядке обхода в ширину от врезки. */
    public List<NewSegment> segmentsTopDown() {
        return topDown;
    }

    /** Участки от листьев к врезке: каждый участок идёт после всех своих дочерних. */
    public List<NewSegment> segmentsBottomUp() {
        List<NewSegment> reversed = new ArrayList<>(topDown);
        Collections.reverse(reversed);
        return reversed;
    }

    public List<NewNode> oksNodes() {
        List<NewNode> result = new ArrayList<>();
        for (NewNode node : nodes.values()) {
            if (node.getKind() == NodeKind.OKS_CONNECTION) {
                result.add(node);
            }
        }
        return result;
    }

    public double totalLengthM() {
        double sum = 0;
        for (NewSegment seg : topDown) {
            sum += seg.getLengthM();
        }
        return sum;
    }
}
