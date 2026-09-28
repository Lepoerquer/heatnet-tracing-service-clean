package ru.heatnet.calc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import ru.heatnet.calc.model.CalcDiagnostic;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingObjectType;
import ru.heatnet.calc.model.ExistingSegment;
import ru.heatnet.calc.model.TieInPoint;

/**
 * M5. Реконструкция существующей сети (разд. 7).
 *
 * <ol>
 *   <li>Дополнительный расход каждой врезки распространяется по upstream_object_id до source.</li>
 *   <li>Расходы нескольких врезок на общей части суммируются.</li>
 *   <li>При врезке в тело трубы расход действует только на часть от точки врезки к источнику
 *       ({@link TieInPoint#getDistanceFromUpstreamEndM()}).</li>
 *   <li>G_итог = G_сущ + G_нов; требуемый DN — минимальный по табл. 4.1; реконструкция,
 *       если он больше существующего.</li>
 * </ol>
 * Отсутствующий flow_tph существующего участка принимается равным 0 (дефолт команды,
 * clarifications №8) с предупреждением.
 */
public final class ReconstructionEngine {

    private final DiameterSelector selector;
    private final double toleranceM;

    public ReconstructionEngine(DiameterSelector selector, double toleranceM) {
        this.selector = selector;
        this.toleranceM = toleranceM;
    }

    public ReconstructionResult calculate(ExistingNetwork network, List<TieInLoad> loads) {
        List<CalcDiagnostic> diagnostics = new ArrayList<>();
        Map<String, BigDecimal> passThrough = new LinkedHashMap<>();
        Map<String, TreeMap<Double, BigDecimal>> partial = new LinkedHashMap<>();

        for (TieInLoad load : loads) {
            TieInPoint tie = load.getTieIn();
            BigDecimal g = BigDecimal.valueOf(load.getAddedFlowTph());
            String next;
            if (tie.getExistingObjectType() == ExistingObjectType.HEAT_NETWORK) {
                ExistingSegment pipe = network.segment(tie.getExistingObjectId());
                double d = tie.getDistanceFromUpstreamEndM();
                if (d > pipe.getLengthM() + toleranceM) {
                    throw new CalcException("Врезка " + tie.getId() + ": расстояние " + d + " м больше длины участка "
                            + pipe.getId() + " (" + pipe.getLengthM() + " м)");
                }
                d = Math.min(d, pipe.getLengthM());
                if (d >= pipe.getLengthM() - toleranceM) {
                    passThrough.merge(pipe.getId(), g, BigDecimal::add);
                } else if (d > toleranceM) {
                    partial.computeIfAbsent(pipe.getId(), k -> new TreeMap<>()).merge(d, g, BigDecimal::add);
                }
                next = pipe.getUpstreamObjectId();
            } else {
                next = network.chamber(tie.getExistingObjectId()).getUpstreamObjectId();
            }
            propagate(network, tie, next, g, passThrough, diagnostics);
        }

        Set<String> affected = new java.util.LinkedHashSet<>(passThrough.keySet());
        affected.addAll(partial.keySet());
        Map<String, List<FlowPiece>> pieces = new LinkedHashMap<>();
        for (String segId : affected) {
            pieces.put(segId, piecesOf(network.segment(segId),
                    passThrough.containsKey(segId) ? passThrough.get(segId) : BigDecimal.ZERO,
                    partial.containsKey(segId) ? partial.get(segId) : new TreeMap<Double, BigDecimal>(),
                    diagnostics));
        }
        return new ReconstructionResult(pieces, diagnostics);
    }

    private void propagate(ExistingNetwork network, TieInPoint tie, String startId, BigDecimal g,
                           Map<String, BigDecimal> passThrough, List<CalcDiagnostic> diagnostics) {
        Set<String> visited = new HashSet<>();
        String previous = tie.getExistingObjectId();
        String cur = startId;
        while (true) {
            if (cur == null) {
                diagnostics.add(CalcDiagnostic.warning("CHAIN_NOT_REACHING_SOURCE", previous,
                        "Цепочка upstream_object_id от врезки " + tie.getId() + " обрывается на " + previous
                                + " и не доходит до источника"));
                return;
            }
            if (network.isSource(cur)) {
                return;
            }
            if (!visited.add(cur)) {
                throw new CalcException("Цикл в цепочке upstream_object_id на объекте " + cur);
            }
            if (network.isSegment(cur)) {
                passThrough.merge(cur, g, BigDecimal::add);
                previous = cur;
                cur = network.segment(cur).getUpstreamObjectId();
            } else if (network.isChamber(cur)) {
                previous = cur;
                cur = network.chamber(cur).getUpstreamObjectId();
            } else {
                throw new CalcException("upstream_object_id объекта " + previous + " ссылается на неизвестный объект " + cur);
            }
        }
    }

    private List<FlowPiece> piecesOf(ExistingSegment seg, BigDecimal pass, TreeMap<Double, BigDecimal> tieIns,
                                     List<CalcDiagnostic> diagnostics) {
        boolean missing = seg.getFlowTph() == null;
        if (missing) {
            diagnostics.add(CalcDiagnostic.warning("EXISTING_FLOW_MISSING", seg.getId(),
                    "У участка нет flow_tph — принят текущий расход 0 т/ч (дефолт команды)"));
        }
        BigDecimal existing = missing ? BigDecimal.ZERO : BigDecimal.valueOf(seg.getFlowTph());
        List<Double> bounds = new ArrayList<>();
        bounds.add(0.0);
        bounds.addAll(tieIns.keySet());
        bounds.add(seg.getLengthM());
        List<FlowPiece> result = new ArrayList<>();
        for (int i = 1; i < bounds.size(); i++) {
            double from = bounds.get(i - 1);
            double to = bounds.get(i);
            if (to - from <= 0) {
                continue;
            }
            // врезка в точке d питает часть [0; d] → на [from; to] действуют врезки с d >= to
            BigDecimal added = pass;
            for (Map.Entry<Double, BigDecimal> e : tieIns.tailMap(to, true).entrySet()) {
                added = added.add(e.getValue());
            }
            if (added.signum() == 0) {
                continue;
            }
            BigDecimal total = existing.add(added);
            int required = selector.minDiameter(total.doubleValue());
            result.add(new FlowPiece(seg.getId(), from, to, existing.doubleValue(), added.doubleValue(),
                    total.doubleValue(), seg.getDiameter(), required, missing));
        }
        return result;
    }
}
