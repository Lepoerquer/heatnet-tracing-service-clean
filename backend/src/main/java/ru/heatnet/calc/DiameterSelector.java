package ru.heatnet.calc;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import ru.heatnet.calc.reference.DiameterSpec;
import ru.heatnet.calc.reference.DiameterTable;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewSegment;

/**
 * M5. Подбор условного диаметра: минимальный DN табл. 4.1 с пропускной способностью
 * не меньше расхода; по направлению к источнику DN не убывает.
 */
public final class DiameterSelector {

    private final DiameterTable table;

    public DiameterSelector(DiameterTable table) {
        this.table = table;
    }

    /** Минимальный DN для расхода; {@link CalcException}, если расход больше DN1400. */
    public int minDiameter(double flowTph) {
        return minSpec(flowTph).getDn();
    }

    public DiameterSpec minSpec(double flowTph) {
        DiameterSpec spec = table.minFor(flowTph).orElse(null);
        if (spec == null) {
            return table.byIndex(table.size() - 1);
        }
        return spec;
    }

    /** true, если расход больше пропускной способности наибольшего ДУ таблицы. */
    public boolean exceedsTable(double flowTph) {
        return !table.minFor(flowTph).isPresent();
    }

    /**
     * Гидравлически минимальные DN всех участков дерева с правилом неубывания к источнику.
     *
     * @param flows расходы участков от {@link FlowAggregator}
     */
    public Map<String, Integer> select(NewNetworkTree tree, Map<String, Double> flows) {
        Map<String, Integer> dn = new HashMap<>();
        for (NewSegment seg : tree.segmentsBottomUp()) {
            Double flow = flows.get(seg.getId());
            if (flow == null) {
                throw new CalcException("Нет расхода для участка " + seg.getId());
            }
            int d = minDiameter(flow);
            for (NewSegment child : tree.childrenOf(seg.getToNodeId())) {
                d = Math.max(d, dn.get(child.getId()));
            }
            dn.put(seg.getId(), d);
        }
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (NewSegment seg : tree.segmentsTopDown()) {
            ordered.put(seg.getId(), dn.get(seg.getId()));
        }
        return ordered;
    }
}
