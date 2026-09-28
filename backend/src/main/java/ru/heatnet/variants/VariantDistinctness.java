package ru.heatnet.variants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.network.NetworkPlan;
import ru.heatnet.network.NetworkTreeLayout;

/**
 * M7. Фильтр «содержательного отличия» вариантов.
 *
 * <p>Приложение §6: «сервис может вернуть до трёх содержательно отличающихся вариантов.
 * Небольшое смещение одной и той же трассы отдельным вариантом не считается».
 * ТЗ «2. ДИТ» §2.8 перечисляет признаки отличия: точка врезки; объединение ОКС в общую сеть;
 * маршрут; разделение на несколько отдельных частей новой сети; способ прохождения ограничения.</p>
 *
 * <p>Вариант признаётся отличающимся, если выполнено хотя бы одно:</p>
 * <ol>
 *   <li>другой набор объектов присоединения ({@code existing_object_id} врезок);</li>
 *   <li>другая топология — другое разбиение ОКС по деревьям либо другое число частей сети;</li>
 *   <li>другой набор неподключённых ОКС;</li>
 *   <li>расстояние Хаусдорфа между геометриями трасс больше порога (дефолт 30 м).</li>
 * </ol>
 *
 * <p>Порог Хаусдорфа — соглашение команды (численного критерия организаторы не дают,
 * см. {@code clarifications.md} №3); первые три признака от порога не зависят.</p>
 */
public final class VariantDistinctness {

    /** Порог по умолчанию, м. Ниже него разные геометрии считаются «тем же вариантом». */
    public static final double DEFAULT_HAUSDORFF_M = 30.0;

    private static final GeometryFactory GF = new GeometryFactory();

    private final double hausdorffThresholdM;

    public VariantDistinctness() {
        this(DEFAULT_HAUSDORFF_M);
    }

    public VariantDistinctness(double hausdorffThresholdM) {
        this.hausdorffThresholdM = hausdorffThresholdM;
    }

    /**
     * Отбирает содержательно различающиеся варианты, сохраняя порядок подачи.
     * Первый вариант принимается всегда; каждый следующий — только если отличается от всех принятых.
     *
     * @param rejected сюда дописываются причины отклонения (может быть null)
     */
    public List<GeneratedVariant> filter(List<GeneratedVariant> candidates, List<String> rejected) {
        List<GeneratedVariant> accepted = new ArrayList<>();
        for (GeneratedVariant candidate : candidates) {
            GeneratedVariant twin = null;
            for (GeneratedVariant already : accepted) {
                if (!isDistinct(already, candidate)) {
                    twin = already;
                    break;
                }
            }
            if (twin == null) {
                accepted.add(candidate);
            } else if (rejected != null) {
                rejected.add("вариант " + candidate.getVariantId() + " (" + candidate.getStrategy().getCode()
                        + ") отброшен: содержательно совпадает с " + twin.getVariantId()
                        + " (" + twin.getStrategy().getCode() + ")");
            }
        }
        return accepted;
    }

    /** true, если варианты различаются содержательно по §2.8 ТЗ. */
    public boolean isDistinct(GeneratedVariant a, GeneratedVariant b) {
        NetworkPlan pa = a.getPlan();
        NetworkPlan pb = b.getPlan();
        if (!tieInSignature(pa).equals(tieInSignature(pb))) {
            return true;
        }
        if (!topologySignature(pa).equals(topologySignature(pb))) {
            return true;
        }
        if (!unconnectedSignature(pa).equals(unconnectedSignature(pb))) {
            return true;
        }
        return hausdorffM(pa, pb) > hausdorffThresholdM;
    }

    /** Набор объектов существующей сети, в которые выполнено присоединение. */
    public static Set<String> tieInSignature(NetworkPlan plan) {
        Set<String> ids = new TreeSet<>();
        for (NewNetworkTree tree : plan.getTrees()) {
            ids.add(tree.getTieIn().getExistingObjectType() + ":" + tree.getTieIn().getExistingObjectId());
        }
        return ids;
    }

    /** Разбиение ОКС по деревьям: множество отсортированных групп. */
    public static Set<String> topologySignature(NetworkPlan plan) {
        Set<String> groups = new TreeSet<>();
        for (NewNetworkTree tree : plan.getTrees()) {
            Set<String> oks = new TreeSet<>();
            for (NewNode node : tree.oksNodes()) {
                oks.add(node.getOksId());
            }
            groups.add(String.join("+", oks));
        }
        return groups;
    }

    /** Идентификаторы неподключённых точек подключения. */
    public static Set<String> unconnectedSignature(NetworkPlan plan) {
        Set<String> ids = new TreeSet<>();
        for (ru.heatnet.cost.UnconnectedOks u : plan.getUnconnectedOks()) {
            ids.add(u.getOksId());
        }
        return ids;
    }

    /**
     * Расстояние Хаусдорфа между геометриями трасс двух планов, м (EPSG:32637).
     * Если у одного из планов нет геометрии, возвращается {@link Double#POSITIVE_INFINITY} —
     * такие варианты считаются заведомо различными, чтобы не склеить их вслепую.
     */
    public static double hausdorffM(NetworkPlan a, NetworkPlan b) {
        Geometry ga = geometryOf(a);
        Geometry gb = geometryOf(b);
        boolean emptyA = ga == null || ga.isEmpty();
        boolean emptyB = gb == null || gb.isEmpty();
        if (emptyA && emptyB) {
            // Оба плана без единого построенного участка (например, ни одного ОКС не подключено
            // ни в одной стратегии) — геометрически неразличимы, а не «бесконечно разные».
            // tieIn/topology/unconnected-сигнатуры в этом случае тоже совпадают, поэтому такие
            // варианты — точные дубли и должны схлопнуться фильтром, а не размножиться в выходе.
            return 0.0;
        }
        if (emptyA || emptyB) {
            return Double.POSITIVE_INFINITY;
        }
        return DiscreteHausdorffDistance.distance(ga, gb);
    }

    private static Geometry geometryOf(NetworkPlan plan) {
        List<LineString> lines = new ArrayList<>();
        for (NetworkTreeLayout layout : plan.getLayouts()) {
            for (LineString line : layout.getSegmentGeometries().values()) {
                if (line != null && !line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        return GF.createMultiLineString(lines.toArray(new LineString[0]));
    }

    /** Человекочитаемое описание отличий — для диагностики и объяснимости на защите. */
    public static List<String> explainDifference(GeneratedVariant a, GeneratedVariant b) {
        List<String> reasons = new ArrayList<>();
        Set<String> ta = tieInSignature(a.getPlan());
        Set<String> tb = tieInSignature(b.getPlan());
        if (!ta.equals(tb)) {
            Set<String> onlyA = new LinkedHashSet<>(ta);
            onlyA.removeAll(tb);
            Set<String> onlyB = new LinkedHashSet<>(tb);
            onlyB.removeAll(ta);
            reasons.add("другие точки присоединения: только в " + a.getVariantId() + " " + onlyA
                    + ", только в " + b.getVariantId() + " " + onlyB);
        }
        if (!topologySignature(a.getPlan()).equals(topologySignature(b.getPlan()))) {
            reasons.add("другое объединение ОКС: " + a.getVariantId() + " "
                    + topologySignature(a.getPlan()) + " против " + b.getVariantId() + " "
                    + topologySignature(b.getPlan()));
        }
        if (!unconnectedSignature(a.getPlan()).equals(unconnectedSignature(b.getPlan()))) {
            reasons.add("другой состав неподключённых ОКС");
        }
        double h = hausdorffM(a.getPlan(), b.getPlan());
        if (Double.isFinite(h)) {
            reasons.add(String.format(java.util.Locale.ROOT, "расстояние Хаусдорфа между трассами %.1f м", h));
        }
        return reasons.isEmpty() ? Collections.singletonList("отличий не найдено") : reasons;
    }
}
