package ru.heatnet.calc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.heatnet.calc.model.CalcDiagnostic;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.reference.DiameterTable;

/**
 * M5. Предельная длина (§2.3 нового приложения, табл. 4.1).
 *
 * <ul>
 *   <li>Плеть — непрерывный путь одного DN от врезки к ОКС. Камера и technical_node
 *       без смены DN счётчик не сбрасывают.</li>
 *   <li>Общий участок учитывается в каждом пути; длины параллельных ветвей не суммируются.</li>
 *   <li>При превышении выбирается следующий минимальный ДУ, удовлетворяющий расходу и длине
 *       (потолка «+1 номенклатура» больше нет).</li>
 * </ul>
 */
public final class LengthLimitValidator {

    private final DiameterTable table;
    private final int maxSteps;
    private final double toleranceM;

    public LengthLimitValidator(DiameterTable table, int maxSteps, double toleranceM) {
        this.table = table;
        this.maxSteps = maxSteps;
        this.toleranceM = toleranceM;
    }

    /**
     * AUDIT-24.09 (Claude): точный подбор ({@link DiameterOptimizer}) — минимальная стоимость при
     * соблюдении расхода, неубывания к врезке, постоянства ДУ на отрезке постоянного расхода и
     * предельной длины по каждому пути. Если точного решения нет (путь длиннее предела DN1400),
     * используется прежний пошаговый подъём с диагностикой LENGTH_LIMIT_EXCEEDED.
     */
    public LengthLimitResult apply(NewNetworkTree tree, Map<String, Integer> hydraulicDiameters,
                                   Map<String, Double> flows, DiameterOptimizer.ChamberCost chamberCost) {
        if (flows != null) {
            Map<String, Integer> optimal;
            try {
                optimal = new DiameterOptimizer(table, chamberCost).optimize(tree, flows, toleranceM);
            } catch (RuntimeException ex) {
                optimal = null;
            }
            if (optimal != null) {
                return finish(tree, hydraulicDiameters, optimal);
            }
        }
        return apply(tree, hydraulicDiameters);
    }

    /**
     * @param hydraulicDiameters DN от {@link DiameterSelector#select}
     */
    public LengthLimitResult apply(NewNetworkTree tree, Map<String, Integer> hydraulicDiameters) {
        Map<String, Integer> dn = new LinkedHashMap<>(hydraulicDiameters);
        if (maxSteps < 0) {
            throw new IllegalArgumentException("length_limit_max_dn_steps");
        }
        int guard = 0;
        int guardLimit = table.size() * (tree.getSegments().size() + 1) + 1;
        while (true) {
            enforceNonDecreasingToSource(tree, dn);
            boolean changed = false;
            Set<String> bump = new LinkedHashSet<String>();
            for (DnRun run : buildRuns(tree, dn)) {
                if (!run.isExceeded(toleranceM)) {
                    continue;
                }
                for (String segId : run.getSegmentIds()) {
                    int idx = table.indexOf(dn.get(segId));
                    if (idx + 1 < table.size()) {
                        bump.add(segId);
                    }
                }
            }
            for (String segId : bump) {
                dn.put(segId, table.byIndex(table.indexOf(dn.get(segId)) + 1).getDn());
                changed = true;
            }
            if (!changed) {
                break;
            }
            if (++guard > guardLimit) {
                throw new CalcException("Проверка предельной длины не сошлась для врезки " + tree.getTieIn().getId());
            }
        }

        return finish(tree, hydraulicDiameters, dn);
    }

    private LengthLimitResult finish(NewNetworkTree tree, Map<String, Integer> hydraulicDiameters,
                                     Map<String, Integer> dn) {
        List<DnRun> runs = buildRuns(tree, dn);
        List<DnRun> exceeded = new ArrayList<>();
        List<CalcDiagnostic> diagnostics = new ArrayList<>();
        for (Map.Entry<String, Integer> e : dn.entrySet()) {
            int hyd = hydraulicDiameters.get(e.getKey());
            if (e.getValue() != hyd) {
                diagnostics.add(CalcDiagnostic.info("DN_RAISED", e.getKey(),
                        "DN" + hyd + " → DN" + e.getValue() + " (предельная длина или неубывание DN к источнику)"));
            }
        }
        for (DnRun run : runs) {
            if (run.isExceeded(toleranceM)) {
                exceeded.add(run);
                diagnostics.add(CalcDiagnostic.error("LENGTH_LIMIT_EXCEEDED", run.getSegmentIds().get(0),
                        String.format("Плеть DN%d длиной %.2f м превышает предельную %.0f м даже после подъёма DN; "
                                + "участки %s", run.getDn(), run.getTotalLengthM(),
                                run.getMaxLengthM(), run.getSegmentIds())));
            }
        }
        return new LengthLimitResult(dn, new LinkedHashMap<>(hydraulicDiameters), runs, exceeded, diagnostics);
    }

    /**
     * Плети одного DN вдоль каждого непрерывного пути «врезка → ОКС» (§2.3).
     * Один участок может входить в несколько плетей.
     */
    public List<DnRun> buildRuns(NewNetworkTree tree, Map<String, Integer> dn) {
        List<DnRun> runs = new ArrayList<>();
        for (NewNode node : tree.getNodes().values()) {
            if (!tree.childrenOf(node.getId()).isEmpty()) {
                continue;
            }
            List<NewSegment> path = new ArrayList<>();
            NewSegment cur = tree.incomingOf(node.getId());
            while (cur != null) {
                path.add(0, cur);
                cur = tree.incomingOf(cur.getFromNodeId());
            }
            int i = 0;
            while (i < path.size()) {
                int d = dn.get(path.get(i).getId());
                double length = 0;
                List<String> ids = new ArrayList<>();
                int j = i;
                while (j < path.size() && dn.get(path.get(j).getId()) == d) {
                    length += path.get(j).getLengthM();
                    ids.add(path.get(j).getId());
                    j++;
                }
                runs.add(new DnRun(d, length, table.spec(d).getMaxLengthM(), ids));
                i = j;
            }
        }
        return runs;
    }

    private static void enforceNonDecreasingToSource(NewNetworkTree tree, Map<String, Integer> dn) {
        for (NewSegment seg : tree.segmentsBottomUp()) {
            int d = dn.get(seg.getId());
            for (NewSegment child : tree.childrenOf(seg.getToNodeId())) {
                d = Math.max(d, dn.get(child.getId()));
            }
            dn.put(seg.getId(), d);
        }
    }
}
