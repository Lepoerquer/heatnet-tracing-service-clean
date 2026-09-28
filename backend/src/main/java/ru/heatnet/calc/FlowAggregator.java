package ru.heatnet.calc;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.NodeKind;

/**
 * M5. Агрегация расчётных расходов по дереву новой сети от ОКС к врезке (разд. 3).
 * Используется только flow_tph ОКС; heat_load не участвует в расчёте (ТЗ п. 2.2).
 * Суммирование — в десятичной арифметике, чтобы 30,1 + 50,2 давало ровно 80,3
 * и не ломало сравнение с пропускной способностью на границе табл. 4.1.
 */
public final class FlowAggregator {

    /** Расход каждого участка, т/ч, в порядке обхода от врезки. */
    public Map<String, Double> aggregate(NewNetworkTree tree) {
        Map<String, BigDecimal> exact = new HashMap<>();
        List<NewSegment> bottomUp = tree.segmentsBottomUp();
        for (NewSegment seg : bottomUp) {
            NewNode to = tree.node(seg.getToNodeId());
            BigDecimal g = to.getKind() == NodeKind.OKS_CONNECTION
                    ? BigDecimal.valueOf(to.getOksFlowTph()) : BigDecimal.ZERO;
            for (NewSegment child : tree.childrenOf(to.getId())) {
                g = g.add(exact.get(child.getId()));
            }
            exact.put(seg.getId(), g);
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (NewSegment seg : tree.segmentsTopDown()) {
            result.put(seg.getId(), exact.get(seg.getId()).doubleValue());
        }
        return result;
    }

    /** Суммарный расход через врезку, т/ч (= расход участка, выходящего из врезки). */
    public double totalFlow(NewNetworkTree tree) {
        return aggregate(tree).get(tree.rootSegment().getId());
    }
}
