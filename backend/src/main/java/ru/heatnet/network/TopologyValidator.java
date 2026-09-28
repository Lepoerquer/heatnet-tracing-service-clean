package ru.heatnet.network;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;

import ru.heatnet.calc.model.ExistingObjectType;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.NodeKind;

/**
 * Валидация топологии новой сети (п. 2.3 ТЗ): дерево, один путь к ОКС, разветвления в камерах.
 */
public final class TopologyValidator {

    private TopologyValidator() {
    }

    private static final int DEFAULT_MAX_SEGMENTS_PER_CHAMBER = 4;

    public static List<String> validate(List<NewNetworkTree> trees) {
        return validate(trees, DEFAULT_MAX_SEGMENTS_PER_CHAMBER);
    }

    public static List<String> validate(List<NewNetworkTree> trees, int maxSegmentsPerChamber) {
        List<String> errors = new ArrayList<>();
        if (trees == null || trees.isEmpty()) {
            return errors;
        }
        for (NewNetworkTree tree : trees) {
            errors.addAll(validateTree(tree, maxSegmentsPerChamber));
            // Несколько деревьев в одну существующую камеру допустимы (§3.2: каждый участок — одна врезка).
            // TODO: проверка «одна врезка на компонент» для HEAT_NETWORK — разные точки допустимы (M7).
        }
        return errors;
    }

    public static void requireValid(List<NewNetworkTree> trees) {
        requireValid(trees, DEFAULT_MAX_SEGMENTS_PER_CHAMBER);
    }

    public static void requireValid(List<NewNetworkTree> trees, int maxSegmentsPerChamber) {
        List<String> errors = validate(trees, maxSegmentsPerChamber);
        if (!errors.isEmpty()) {
            throw new NetworkException("Топология не прошла проверку: " + String.join("; ", errors));
        }
    }

    /**
     * QA-FIX C-3. §2.1: «Новые участки не должны пересекаться между собой вне общего узла» —
     * правило про <b>всю</b> новую сеть, а не про отдельное дерево. {@link CrossingResolver} работает
     * по одному дереву и междеревные пересечения не видит.
     *
     * @return список нарушений; пустой — норма
     */
    public static List<String> globalCrossings(List<BuiltNetworkTree> trees, double toleranceM) {
        List<String> problems = new ArrayList<>();
        if (trees == null || trees.isEmpty()) {
            return problems;
        }
        List<SegRef> all = new ArrayList<>();
        for (int t = 0; t < trees.size(); t++) {
            BuiltNetworkTree bt = trees.get(t);
            NetworkTreeLayout layout = bt.getLayout();
            for (NewSegment seg : bt.getTree().segmentsTopDown()) {
                LineString line = layout.segmentLine(seg.getId());
                if (line == null || line.isEmpty() || line.getNumPoints() < 2) {
                    continue;
                }
                all.add(new SegRef(t, seg.getId(), line,
                        layout.nodeCoordinate(seg.getFromNodeId()),
                        layout.nodeCoordinate(seg.getToNodeId())));
            }
        }
        if (all.size() < 2) {
            return problems;
        }

        STRtree index = new STRtree();
        for (SegRef s : all) {
            index.insert(s.line.getEnvelopeInternal(), s);
        }
        index.build();

        for (SegRef a : all) {
            org.locationtech.jts.geom.Envelope env = new org.locationtech.jts.geom.Envelope(a.line.getEnvelopeInternal());
            env.expandBy(ru.heatnet.routing.NewNetworkClearance.MIN_GAP_M);
            @SuppressWarnings("unchecked")
            List<SegRef> neighbours = index.query(env);
            for (SegRef b : neighbours) {
                if (b == a || a.segmentId.compareTo(b.segmentId) >= 0) {
                    continue;
                }
                Geometry inter;
                try {
                    inter = a.line.intersection(b.line);
                } catch (RuntimeException ex) {
                    continue;
                }
                if (inter == null || inter.isEmpty()) {
                    if (nearOverlap(a, b)) {
                        problems.add("сближение участков вне общего узла (< "
                                + ru.heatnet.routing.NewNetworkClearance.MIN_GAP_M + " м): " + a.describe() + " и "
                                + b.describe());
                    }
                    continue;
                }
                if (inter.getDimension() >= 1) {
                    problems.add("наложение участков " + a.describe() + " и " + b.describe());
                    continue;
                }
                boolean reported = false;
                for (Coordinate c : inter.getCoordinates()) {
                    if (!a.touchesNode(c, toleranceM) || !b.touchesNode(c, toleranceM)) {
                        problems.add("пересечение вне общего узла: " + a.describe() + " и " + b.describe()
                                + " в (" + round(c.x) + ", " + round(c.y) + ")");
                        reported = true;
                        break;
                    }
                }
                if (!reported && nearOverlap(a, b)) {
                    problems.add("сближение участков вне общего узла (< "
                            + ru.heatnet.routing.NewNetworkClearance.MIN_GAP_M + " м): " + a.describe() + " и "
                            + b.describe());
                }
            }
        }
        return problems;
    }

    /**
     * AUDIT-12 (Claude, 24.09): «почти наложение» — участки вне общего узла ближе 0,3 м друг к другу. Точная
     * топология JTS такие случаи не видит (ветки из одной камеры под углом 0,01° касаются только в камере).
     */
    private static boolean nearOverlap(SegRef a, SegRef b) {
        Coordinate t1 = null;
        Coordinate t2 = null;
        for (Coordinate x : new Coordinate[] {a.from, a.to}) {
            if (x == null) {
                continue;
            }
            if ((b.from != null && b.from.distance(x) <= 0.05) || (b.to != null && b.to.distance(x) <= 0.05)) {
                if (t1 == null) {
                    t1 = x;
                } else {
                    t2 = x;
                }
            }
        }
        return ru.heatnet.routing.NewNetworkClearance.tooClose(a.line, b.line, t1, t2);
    }

    /**
     * QA-FIX C-7. §2.4: новая камера создаётся «непосредственно в выбранной точке». Два дерева,
     * врезанные в одну и ту же точку трубы, — это физически одна камера, а не две по 3–5 млн ₽.
     */
    public static List<String> duplicateTieInPoints(List<BuiltNetworkTree> trees, double toleranceM) {
        List<String> problems = new ArrayList<>();
        if (trees == null || trees.size() < 2) {
            return problems;
        }
        List<Coordinate> placed = new ArrayList<>();
        List<String> placedIds = new ArrayList<>();
        for (BuiltNetworkTree bt : trees) {
            if (bt.getTree().getTieIn().getExistingObjectType() != ExistingObjectType.HEAT_NETWORK) {
                continue;
            }
            String tieId = bt.getTree().getTieIn().getId();
            Coordinate c = bt.getLayout().nodeCoordinate(tieId);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < placed.size(); i++) {
                if (placed.get(i).distance(c) <= Math.max(toleranceM, 1.0)) {
                    problems.add("две новые камеры врезки в одной точке: " + placedIds.get(i) + " и " + tieId);
                    break;
                }
            }
            placed.add(c);
            placedIds.add(tieId);
        }
        return problems;
    }

    /**
     * QA-FIX C-6. §2.1: «Угол поворота определяется как изменение направления относительно
     * продолжения предыдущего участка: 0° соответствует движению по прямой. Допускается
     * произвольный угол поворота до 90° включительно» (то же — Разъяснение №5).
     *
     * <p>Проверяются внутренние вершины каждого LineString и стыки в узлах с ровно двумя
     * примыканиями (технический узел или проходная точка): там трасса непрерывная
     * и понятие «предыдущего участка» однозначно.
     */
    public static List<String> turnViolations(List<BuiltNetworkTree> trees, double maxTurnDeg) {
        List<String> problems = new ArrayList<>();
        if (trees == null || trees.isEmpty()) {
            return problems;
        }
        double limit = maxTurnDeg + 1e-6;
        for (int t = 0; t < trees.size(); t++) {
            BuiltNetworkTree bt = trees.get(t);
            NetworkTreeLayout layout = bt.getLayout();
            java.util.Map<String, List<NewSegment>> byNode = new java.util.LinkedHashMap<>();
            for (NewSegment seg : bt.getTree().segmentsTopDown()) {
                LineString line = layout.segmentLine(seg.getId());
                if (line == null || line.getNumPoints() < 2) {
                    continue;
                }
                for (int i = 1; i < line.getNumPoints() - 1; i++) {
                    double dev = deviationDeg(line.getCoordinateN(i - 1), line.getCoordinateN(i),
                            line.getCoordinateN(i + 1));
                    if (dev > limit) {
                        problems.add("поворот " + Math.round(dev) + "° > " + Math.round(maxTurnDeg)
                                + "° в участке " + t + ":" + seg.getId() + ", вершина " + i);
                    }
                }
                byNode.computeIfAbsent(seg.getFromNodeId(), k -> new ArrayList<NewSegment>()).add(seg);
                byNode.computeIfAbsent(seg.getToNodeId(), k -> new ArrayList<NewSegment>()).add(seg);
            }
            for (java.util.Map.Entry<String, List<NewSegment>> e : byNode.entrySet()) {
                if (e.getValue().size() != 2) {
                    continue;
                }
                Coordinate node = layout.nodeCoordinate(e.getKey());
                if (node == null) {
                    continue;
                }
                Coordinate a = neighbourOf(layout.segmentLine(e.getValue().get(0).getId()), node);
                Coordinate b = neighbourOf(layout.segmentLine(e.getValue().get(1).getId()), node);
                if (a == null || b == null) {
                    continue;
                }
                double dev = deviationDeg(a, node, b);
                if (dev > limit) {
                    problems.add("поворот " + Math.round(dev) + "° > " + Math.round(maxTurnDeg)
                            + "° в узле " + t + ":" + e.getKey());
                }
            }
        }
        return problems;
    }

    /** Соседняя вершина линии со стороны узла. */
    private static Coordinate neighbourOf(LineString line, Coordinate node) {
        if (line == null || line.getNumPoints() < 2) {
            return null;
        }
        Coordinate first = line.getCoordinateN(0);
        Coordinate last = line.getCoordinateN(line.getNumPoints() - 1);
        if (first.distance(node) <= last.distance(node)) {
            return line.getCoordinateN(1);
        }
        return line.getCoordinateN(line.getNumPoints() - 2);
    }

    /** Отклонение от продолжения предыдущего участка, градусы (0° — прямая). */
    private static double deviationDeg(Coordinate prev, Coordinate at, Coordinate next) {
        double ax = at.x - prev.x;
        double ay = at.y - prev.y;
        double bx = next.x - at.x;
        double by = next.y - at.y;
        double la = Math.hypot(ax, ay);
        double lb = Math.hypot(bx, by);
        if (la < 1e-9 || lb < 1e-9) {
            return 0.0;
        }
        double cos = (ax * bx + ay * by) / (la * lb);
        cos = Math.max(-1.0, Math.min(1.0, cos));
        return Math.toDegrees(Math.acos(cos));
    }

    /**
     * QA-FIX H-7: полный набор проблем без исключения. §2.5 допускает неподключение отдельных точек,
     * но не падение всего расчёта: одна плохая ветка не должна обнулять результат по всем ОКС.
     */
    public static List<String> problems(List<BuiltNetworkTree> trees, int maxSegmentsPerChamber,
                                        double toleranceM) {
        List<String> all = new ArrayList<>();
        if (trees == null || trees.isEmpty()) {
            return all;
        }
        List<NewNetworkTree> plain = new ArrayList<>();
        for (BuiltNetworkTree bt : trees) {
            plain.add(bt.getTree());
        }
        all.addAll(validate(plain, maxSegmentsPerChamber));
        all.addAll(globalCrossings(trees, toleranceM));
        all.addAll(duplicateTieInPoints(trees, toleranceM));
        all.addAll(turnViolations(trees, 90.0));
        return all;
    }

    private static long round(double v) {
        return Math.round(v);
    }

    /** Участок с координатами своих узлов — для глобальной проверки пересечений. */
    private static final class SegRef {
        private final int treeIndex;
        private final String segmentId;
        private final LineString line;
        private final Coordinate from;
        private final Coordinate to;

        private SegRef(int treeIndex, String segmentId, LineString line, Coordinate from, Coordinate to) {
            this.treeIndex = treeIndex;
            this.segmentId = segmentId;
            this.line = line;
            this.from = from;
            this.to = to;
        }

        private boolean touchesNode(Coordinate c, double toleranceM) {
            double tol = Math.max(toleranceM, 0.01);
            if (from != null && from.distance(c) <= tol) {
                return true;
            }
            return to != null && to.distance(c) <= tol;
        }

        private String describe() {
            return treeIndex + ":" + segmentId;
        }
    }

    private static List<String> validateTree(NewNetworkTree tree, int maxSegmentsPerChamber) {
        List<String> errors = new ArrayList<>();
        try {
            tree.getTieIn();
        } catch (RuntimeException ex) {
            errors.add(ex.getMessage());
            return errors;
        }

        for (NewNode node : tree.getNodes().values()) {
            if (node.getKind() == NodeKind.OKS_CONNECTION) {
                if (!tree.childrenOf(node.getId()).isEmpty()) {
                    errors.add("ОКС " + node.getId() + " не является листом");
                }
            }
            if (node.getKind() == NodeKind.NEW_CHAMBER) {
                int degree = tree.incidentTo(node.getId()).size();
                if (degree > maxSegmentsPerChamber) {
                    errors.add("Новая камера " + node.getId() + " имеет " + degree
                            + " примыканий, допускается не более " + maxSegmentsPerChamber);
                }
            }
        }

        int branchCount = 0;
        for (NewNode node : tree.getNodes().values()) {
            List<NewSegment> out = tree.childrenOf(node.getId());
            if (out.size() > 1) {
                branchCount++;
                if (node.getKind() != NodeKind.NEW_CHAMBER && node.getKind() != NodeKind.TIE_IN) {
                    errors.add("Разветвление в узле " + node.getId() + " (" + node.getKind()
                            + ") — ожидается камера");
                }
            }
        }
        if (branchCount > 0) {
            boolean hasChamber = false;
            for (NewNode node : tree.getNodes().values()) {
                if (node.getKind() == NodeKind.NEW_CHAMBER) {
                    hasChamber = true;
                    break;
                }
            }
            if (!hasChamber && tree.getTieIn().getExistingObjectType() != ExistingObjectType.HEAT_CHAMBER) {
                errors.add("Разветвление без NEW_CHAMBER во врезке " + tree.getTieIn().getId());
            }
        }
        return errors;
    }
}
