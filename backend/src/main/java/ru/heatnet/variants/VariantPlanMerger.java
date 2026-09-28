package ru.heatnet.variants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

import ru.heatnet.calc.model.ExistingObjectType;
import ru.heatnet.calc.model.LayingMethod;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.cost.UnconnectedOks;
import ru.heatnet.network.BuiltNetworkTree;
import ru.heatnet.network.NetworkPlan;
import ru.heatnet.network.NetworkTreeLayout;
import ru.heatnet.network.TopologyValidator;

/**
 * M7. Слияние нескольких планов M4 в один вариант.
 *
 * <p><b>Зачем нужно переименование.</b> {@code NetworkTreeBuilder} нумерует узлы и участки
 * счётчиками {@code segSeq}/{@code nodeSeq}, которые сбрасываются в начале каждого вызова
 * {@code NetworkPlanner.plan(...)}. Поэтому два независимых прогона дают одинаковые служебные
 * имена вида {@code tn_1}, {@code nch_branch_1}. При слиянии это приводит либо к отказу
 * {@code VariantCostCalculator} (id камеры/участка не уникален), либо к молчаливому склеиванию
 * разных объектов.</p>
 *
 * <p>Переименование затрагивает только служебные идентификаторы узлов, участков и врезок.
 * Идентификаторы ВХОДНЫХ объектов не меняются никогда: {@code oksId} точки подключения и
 * {@code existingObjectId} врезки остаются как во входных данных — от них зависят
 * {@code unconnected_oks_ids} и ссылки выходной схемы (§7.2).</p>
 */
public final class VariantPlanMerger {

    private VariantPlanMerger() {
    }

    /** Сливает части в один план, переименовывая только конфликтующие служебные id. */
    public static NetworkPlan merge(List<NetworkPlan> parts) {
        return merge(parts, 0.01, 4);
    }

    /**
     * @param toleranceM             геометрический допуск (§4: 4,999 → 5 м) — {@code config.geometry.tolerance_m}
     * @param maxSegmentsPerChamber  лимит §2.1 — {@code config.network.max_segments_per_chamber}
     */
    public static NetworkPlan merge(List<NetworkPlan> parts, double toleranceM, int maxSegmentsPerChamber) {
        List<NewNetworkTree> trees = new ArrayList<>();
        List<NetworkTreeLayout> layouts = new ArrayList<>();
        List<UnconnectedOks> unconnected = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        boolean joint = false;

        for (int p = 0; p < parts.size(); p++) {
            NetworkPlan part = parts.get(p);
            joint = joint || part.isJointConnection();
            unconnected.addAll(part.getUnconnectedOks());
            diagnostics.addAll(part.getDiagnostics());

            for (int i = 0; i < part.getTrees().size(); i++) {
                NewNetworkTree tree = part.getTrees().get(i);
                NetworkTreeLayout layout = i < part.getLayouts().size()
                        ? part.getLayouts().get(i) : new NetworkTreeLayout();
                if (collides(tree, usedIds)) {
                    Renamed renamed = rename(tree, layout, "g" + p + "_");
                    tree = renamed.getTree();
                    layout = renamed.getLayout();
                }
                register(tree, usedIds);
                trees.add(tree);
                layouts.add(layout);
            }
        }
        // §2.1: «новые участки не должны пересекаться между собой вне общего узла» — правило про
        // ВСЮ новую сеть, а не про часть, построенную одним прогоном планировщика. Каждая часть
        // (группа CLUSTERED/SEPARATE_EACH) была проверена планировщиком только САМА С СОБОЙ —
        // пересечения МЕЖДУ частями (например, ветка группы А режет ветку группы Б) до этой
        // проверки были не видны никому: ни планировщику каждой части, ни VariantGenerator.
        List<BuiltNetworkTree> built = new ArrayList<>();
        for (int i = 0; i < trees.size(); i++) {
            built.add(new BuiltNetworkTree(trees.get(i), layouts.get(i)));
        }
        List<String> crossPartProblems = TopologyValidator.problems(built, maxSegmentsPerChamber, toleranceM);
        for (String problem : crossPartProblems) {
            if (!diagnostics.contains(problem)) {
                diagnostics.add(problem);
            }
        }
        return new NetworkPlan(trees, layouts, unconnected, joint, diagnostics);
    }

    private static boolean collides(NewNetworkTree tree, Set<String> used) {
        if (used.contains(tree.getTieIn().getId())) {
            return true;
        }
        for (String nodeId : tree.getNodes().keySet()) {
            if (used.contains(nodeId)) {
                return true;
            }
        }
        for (String segId : tree.getSegments().keySet()) {
            if (used.contains(segId)) {
                return true;
            }
        }
        String newChamber = tree.getTieIn().getNewChamberId();
        return newChamber != null && used.contains(newChamber);
    }

    private static void register(NewNetworkTree tree, Set<String> used) {
        used.add(tree.getTieIn().getId());
        used.addAll(tree.getNodes().keySet());
        used.addAll(tree.getSegments().keySet());
        String newChamber = tree.getTieIn().getNewChamberId();
        if (newChamber != null) {
            used.add(newChamber);
        }
    }

    /** Пересобирает дерево и раскладку с префиксом у служебных идентификаторов. */
    static Renamed rename(NewNetworkTree tree, NetworkTreeLayout layout, String prefix) {
        Map<String, String> nodeIds = new HashMap<>();
        for (String id : tree.getNodes().keySet()) {
            nodeIds.put(id, prefix + id);
        }

        TieInPoint oldTie = tree.getTieIn();
        String newTieId = nodeIds.get(oldTie.getId());
        if (newTieId == null) {
            newTieId = prefix + oldTie.getId();
            nodeIds.put(oldTie.getId(), newTieId);
        }
        TieInPoint tie;
        if (oldTie.getExistingObjectType() == ExistingObjectType.HEAT_CHAMBER) {
            tie = TieInPoint.intoChamber(newTieId, oldTie.getExistingObjectId());
        } else {
            Double dist = oldTie.getDistanceFromUpstreamEndM();
            String chamber = oldTie.getNewChamberId() == null ? null : prefix + oldTie.getNewChamberId();
            tie = TieInPoint.intoPipe(newTieId, oldTie.getExistingObjectId(),
                    dist == null ? 0.0 : dist.doubleValue(), chamber);
        }

        List<NewNode> nodes = new ArrayList<>();
        for (NewNode node : tree.getNodes().values()) {
            String id = nodeIds.get(node.getId());
            switch (node.getKind()) {
                case TIE_IN:
                    nodes.add(NewNode.tieIn(id));
                    break;
                case NEW_CHAMBER:
                    nodes.add(NewNode.chamber(id));
                    break;
                case OKS_CONNECTION:
                    // oksId — идентификатор ВХОДНОГО объекта, не переименовывается
                    nodes.add(NewNode.oks(id, node.getOksId(), node.getOksFlowTph()));
                    break;
                default:
                    nodes.add(NewNode.technical(id));
                    break;
            }
        }

        List<NewSegment> segments = new ArrayList<>();
        NetworkTreeLayout newLayout = new NetworkTreeLayout();
        for (NewSegment seg : tree.segmentsTopDown()) {
            String id = prefix + seg.getId();
            String from = nodeIds.get(seg.getFromNodeId());
            String to = nodeIds.get(seg.getToNodeId());
            segments.add(seg.getLayingMethod() == LayingMethod.SPECIAL
                    ? NewSegment.special(id, from, to, seg.getLengthM(), seg.getKSpec())
                    : NewSegment.base(id, from, to, seg.getLengthM()));
            LineString line = layout.segmentLine(seg.getId());
            if (line != null) {
                newLayout.putSegment(id, line);
            }
        }
        for (Map.Entry<String, String> e : nodeIds.entrySet()) {
            Coordinate c = layout.nodeCoordinate(e.getKey());
            if (c != null) {
                newLayout.putNode(e.getValue(), c);
            }
        }
        return new Renamed(new NewNetworkTree(tie, nodes, segments), newLayout);
    }

    /** Результат переименования: пересобранные дерево и раскладка. */
    static final class Renamed {

        private final NewNetworkTree tree;
        private final NetworkTreeLayout layout;

        Renamed(NewNetworkTree tree, NetworkTreeLayout layout) {
            this.tree = tree;
            this.layout = layout;
        }

        NewNetworkTree getTree() {
            return tree;
        }

        NetworkTreeLayout getLayout() {
            return layout;
        }
    }
}
