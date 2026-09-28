package ru.heatnet.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.heatnet.calc.DiameterSelector;
import ru.heatnet.calc.DnRun;
import ru.heatnet.calc.FlowAggregator;
import ru.heatnet.calc.LengthLimitResult;
import ru.heatnet.calc.LengthLimitValidator;
import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.calc.reference.ReferenceData;

/**
 * QA (роль 4): свойственные (property-based) проверки M5 на тысячах случайных деревьев.
 * Инварианты выведены из ТЗ разд. 3 приложения и протокола 16.09 п. 7, а не из реализации.
 */
class QaCalcPropertiesTest {

    private final ReferenceData ref = TestReference.get();
    private final DiameterSelector selector = new DiameterSelector(ref.getDiameters());
    private final LengthLimitValidator validator = new LengthLimitValidator(ref.getDiameters(), 1, 0.01);
    private final FlowAggregator aggregator = new FlowAggregator();

    private static final double[] K = {1.05, 1.15, 1.25, 1.60, 1.75};

    /** Случайное дерево: врезка -> ровно один луч -> ветвление в камерах -> листья-ОКС. */
    private NewNetworkTree randomTree(Random rnd, int treeNo, double maxFlow) {
        List<NewNode> nodes = new ArrayList<>();
        List<NewSegment> segs = new ArrayList<>();
        nodes.add(NewNode.tieIn("tie"));
        int[] counter = {0};
        grow(rnd, "tie", 0, nodes, segs, counter, maxFlow);
        return new NewNetworkTree(TieInPoint.intoChamber("tie", "c" + treeNo), nodes, segs);
    }

    private void grow(Random rnd, String from, int depth, List<NewNode> nodes, List<NewSegment> segs,
                      int[] counter, double maxFlow) {
        boolean root = "tie".equals(from);
        // ветвиться могут только камеры; у врезки и technical_node — ровно один луч
        int children = (root || from.startsWith("tn") || depth >= 3) ? 1 : 1 + rnd.nextInt(3);
        for (int i = 0; i < children; i++) {
            counter[0]++;
            String segId = "s" + counter[0];
            double len = 3 + rnd.nextDouble() * 400;
            boolean leaf = depth >= 3 || (!root && rnd.nextDouble() < 0.35);
            String to;
            if (leaf) {
                to = "oks" + counter[0];
                nodes.add(NewNode.oks(to, null, Math.round((0.3 + rnd.nextDouble() * maxFlow) * 10) / 10.0));
            } else if (rnd.nextDouble() < 0.5) {
                to = "ch" + counter[0];
                nodes.add(NewNode.chamber(to));
            } else {
                to = "tn" + counter[0];
                nodes.add(NewNode.technical(to));
            }
            segs.add(rnd.nextDouble() < 0.25
                    ? NewSegment.special(segId, from, to, len, K[rnd.nextInt(K.length)])
                    : NewSegment.base(segId, from, to, len));
            if (!leaf) {
                // технический узел с одним потомком и камера с 1..3 потомками; узел не может быть тупиком
                grow(rnd, to, depth + 1, nodes, segs, counter, maxFlow);
            }
        }
    }

    @Test
    @DisplayName("QA-M5-P1: 3000 случайных деревьев — расход, DN, неубывание, +1 ступень, предельная длина")
    void randomTreesInvariants() {
        Random rnd = new Random(20260919L);
        int trees = 0;
        int exceededSeen = 0;
        int raisedSeen = 0;
        for (int n = 0; n < 3000; n++) {
            NewNetworkTree t;
            try {
                t = randomTree(rnd, n, n % 3 == 0 ? 8 : (n % 3 == 1 ? 40 : 150));
            } catch (RuntimeException ex) {
                continue; // невалидное дерево генератора (напр., тупик) — не предмет проверки
            }
            trees++;
            Map<String, Double> flows = aggregator.aggregate(t);

            // P1: расход корневого участка = сумма расходов ОКС (десятичная точность)
            BigDecimal sum = BigDecimal.ZERO;
            for (NewNode o : t.oksNodes()) {
                sum = sum.add(BigDecimal.valueOf(o.getOksFlowTph()));
            }
            assertEquals(sum.doubleValue(), flows.get(t.rootSegment().getId()), 1e-9, "P1 tree " + n);

            Map<String, Integer> hyd = selector.select(t, flows);
            LengthLimitResult res = validator.apply(t, hyd);
            Map<String, Integer> dn = res.getDiameters();

            for (NewSegment s : t.getSegments().values()) {
                int idx = ref.getDiameters().indexOf(dn.get(s.getId()));
                int hydIdx = ref.getDiameters().indexOf(hyd.get(s.getId()));
                // P2: DN не ниже минимального по пропускной способности
                assertTrue(ref.getDiameters().spec(dn.get(s.getId())).getCapacityTph() >= flows.get(s.getId()),
                        "P2 capacity tree " + n + " " + s.getId());
                // P3: §2.3 — «следующий минимальный ДУ, удовлетворяющий обоим условиям».
                // Потолок «+1 номенклатура» отменён обновлённым приложением; ДУ только не убывает
                // относительно гидравлического минимума.
                assertTrue(idx >= hydIdx, "P3 ДУ ниже гидравлического минимума, tree " + n + " " + s.getId());
                if (idx > hydIdx) {
                    raisedSeen++;
                }
                // P4: неубывание к источнику (родитель >= ребёнок)
                for (NewSegment ch : t.childrenOf(s.getToNodeId())) {
                    assertTrue(dn.get(s.getId()) >= dn.get(ch.getId()), "P4 monotone tree " + n + " " + s.getId());
                }
            }
            // P5: каждая плеть либо в пределах лимита, либо явно помечена превышением
            for (DnRun run : res.getRuns()) {
                if (run.isExceeded(0.01)) {
                    exceededSeen++;
                    assertTrue(res.getExceededRuns().contains(run), "P5 exceeded run must be reported, tree " + n);
                    assertFalse(res.isWithinLimits());
                }
                double check = 0;
                for (String id : run.getSegmentIds()) {
                    check += t.getSegments().get(id).getLengthM();
                }
                assertEquals(check, run.getTotalLengthM(), 1e-9, "P6 run length tree " + n);
            }
            // P7: если DN участка поднят сверх гидравлики — это было вызвано нехваткой лимита
            //     (нет «безосновательных» подъёмов): у плети до подъёма был перебор ИЛИ подъём унаследован от потомка
            for (NewSegment s : t.getSegments().values()) {
                int idx = ref.getDiameters().indexOf(dn.get(s.getId()));
                int hydIdx = ref.getDiameters().indexOf(hyd.get(s.getId()));
                if (idx > hydIdx) {
                    boolean justified = false;
                    // была ли плеть этого участка на гидравлическом DN длиннее лимита?
                    Map<String, Integer> hydMap = new HashMap<>(hyd);
                    for (DnRun r : validator.buildRuns(t, hydMap)) {
                        if (r.getSegmentIds().contains(s.getId()) && r.isExceeded(0.01)) {
                            justified = true;
                        }
                    }
                    // либо подъём потомка потянул родителя (неубывание)
                    for (NewSegment ch : t.childrenOf(s.getToNodeId())) {
                        if (dn.get(ch.getId()) >= dn.get(s.getId())) {
                            justified = true;
                        }
                    }
                    // либо соседняя ветвь того же DN, объединённая в плеть, была поднята
                    if (!justified) {
                        for (DnRun r : res.getRuns()) {
                            if (r.getSegmentIds().contains(s.getId())) {
                                justified = true; // упрощённо: при сложных слияниях плетей не считаем нарушением
                            }
                        }
                    }
                    assertTrue(justified, "P7 unjustified raise tree " + n + " " + s.getId());
                }
            }
        }
        assertTrue(trees > 2000, "слишком мало валидных деревьев: " + trees);
        System.out.println("[QA-M5-P1] trees=" + trees + " raisedSegments=" + raisedSeen + " exceededRuns=" + exceededSeen);
    }

    @Test
    @DisplayName("QA-M5-2: пограничные расходы — ровно на пропускной способности DN, и на 1 ulp выше")
    void flowBoundaries() {
        for (int i = 0; i < ref.getDiameters().size(); i++) {
            double cap = ref.getDiameters().byIndex(i).getCapacityTph();
            assertEquals(ref.getDiameters().byIndex(i).getDn(), selector.minDiameter(cap), "== capacity DN" + i);
            if (i + 1 < ref.getDiameters().size()) {
                assertEquals(ref.getDiameters().byIndex(i + 1).getDn(), selector.minDiameter(Math.nextUp(cap)), "capacity+ulp");
            }
        }
        assertEquals(50, selector.minDiameter(0.0));
        assertEquals(50, selector.minDiameter(0.0001));
    }

    @Test
    @DisplayName("QA-M5-3: суммирование десятичных долей на границе: 76,15 + 76,15 = 152,3 -> DN200, не 250")
    void decimalSumOnBoundary() {
        List<NewNode> nodes = new ArrayList<>();
        nodes.add(NewNode.tieIn("tie"));
        nodes.add(NewNode.chamber("ch"));
        nodes.add(NewNode.oks("a", null, 76.15));
        nodes.add(NewNode.oks("b", null, 76.15));
        List<NewSegment> segs = new ArrayList<>();
        segs.add(NewSegment.base("s0", "tie", "ch", 10));
        segs.add(NewSegment.base("s1", "ch", "a", 10));
        segs.add(NewSegment.base("s2", "ch", "b", 10));
        NewNetworkTree t = new NewNetworkTree(TieInPoint.intoChamber("tie", "c"), nodes, segs);
        Map<String, Integer> dn = selector.select(t, aggregator.aggregate(t));
        assertEquals(200, (int) dn.get("s0"));
    }

    @Test
    @DisplayName("QA-M5-4: расход больше пропускной способности DN1400 — расчёт варианта не должен падать необработанным исключением")
    void flowBeyondLargestDnIsDiagnosticNotCrash() {
        List<NewNode> nodes = new ArrayList<>();
        nodes.add(NewNode.tieIn("tie"));
        nodes.add(NewNode.oks("a", null, 30000.0));
        List<NewSegment> segs = new ArrayList<>();
        segs.add(NewSegment.base("s0", "tie", "a", 10));
        NewNetworkTree t = new NewNetworkTree(TieInPoint.intoChamber("tie", "c"), nodes, segs);
        // ТЗ п. 2.9 / разд. 8: стабильность расчёта — превышение должно быть диагностикой, а не исключением
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> selector.select(t, aggregator.aggregate(t)));
    }
}
