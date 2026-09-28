package ru.heatnet.calc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.heatnet.calc.model.CalcDiagnostic;
import ru.heatnet.calc.model.ExistingChamber;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingObjectType;
import ru.heatnet.calc.model.ExistingSegment;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.NodeKind;

/**
 * M5. Диаметры камер по разд. 8.2.
 *
 * <ul>
 *   <li>Существующая камера — только если в неё есть врезка; одна оценка на камеру независимо от
 *       числа врезок. Требуемый DN = max(новые участки врезок, DN примыкающих существующих участков
 *       после реконструкции). Реконструкция — если он больше входного diameter камеры.</li>
 *   <li>Камеры не в точке врезки не рассматриваются, даже если примыкающий участок реконструируется.</li>
 *   <li>Новая камера (разветвление или врезка в трубу) — max DN всех примыкающих участков,
 *       для врезки в трубу — включая обе части разрезанного существующего участка.</li>
 * </ul>
 * Если у существующей камеры нет diameter, исходным принимается максимум DN примыкающих
 * существующих участков (дефолт команды, 03-dataset-audit).
 */
public final class ChamberReconstruction {

    private final int maxSegmentsPerChamber;

    public ChamberReconstruction(int maxSegmentsPerChamber) {
        this.maxSegmentsPerChamber = maxSegmentsPerChamber;
    }

    /** Существующие камеры, в которые выполнены врезки. */
    public List<ChamberSizing> existingChambers(ExistingNetwork network, List<NewNetworkTree> trees,
                                                Map<String, Map<String, Integer>> diametersByTieIn,
                                                ReconstructionResult recon, List<CalcDiagnostic> diagnostics) {
        Map<String, List<NewNetworkTree>> byChamber = new LinkedHashMap<>();
        for (NewNetworkTree tree : trees) {
            if (tree.getTieIn().getExistingObjectType() == ExistingObjectType.HEAT_CHAMBER) {
                byChamber.computeIfAbsent(tree.getTieIn().getExistingObjectId(), k -> new ArrayList<>()).add(tree);
            }
        }
        List<ChamberSizing> result = new ArrayList<>();
        for (Map.Entry<String, List<NewNetworkTree>> e : byChamber.entrySet()) {
            ExistingChamber chamber = network.chamber(e.getKey());
            int adjacentMaxBefore = 0;
            int adjacentMaxAfter = 0;
            int adjacentCount = 0;
            String up = chamber.getUpstreamObjectId();
            if (up != null && network.isSegment(up)) {
                ExistingSegment s = network.segment(up);
                adjacentCount++;
                adjacentMaxBefore = Math.max(adjacentMaxBefore, s.getDiameter());
                // к камере обращён конец участка со стороны потребителей
                adjacentMaxAfter = Math.max(adjacentMaxAfter,
                        recon.diameterAfterAt(s.getId(), s.getDiameter(), s.getLengthM()));
            } else if (up != null) {
                adjacentCount++;
            }
            for (ExistingSegment s : network.downstreamSegmentsOf(chamber.getId())) {
                adjacentCount++;
                adjacentMaxBefore = Math.max(adjacentMaxBefore, s.getDiameter());
                adjacentMaxAfter = Math.max(adjacentMaxAfter, recon.diameterAfterAt(s.getId(), s.getDiameter(), 0.0));
            }
            int newMax = 0;
            for (NewNetworkTree tree : e.getValue()) {
                newMax = Math.max(newMax, diametersByTieIn.get(tree.getTieIn().getId()).get(tree.rootSegment().getId()));
            }
            int total = adjacentCount + e.getValue().size();
            if (total > maxSegmentsPerChamber) {
                diagnostics.add(CalcDiagnostic.error("CHAMBER_SEGMENT_LIMIT", chamber.getId(),
                        "К камере будет примыкать " + total + " участков при лимите " + maxSegmentsPerChamber));
            }
            boolean missing = chamber.getDiameter() == null;
            int original = missing ? adjacentMaxBefore : chamber.getDiameter();
            if (missing) {
                diagnostics.add(CalcDiagnostic.warning("CHAMBER_DIAMETER_MISSING", chamber.getId(),
                        "У камеры нет diameter — принят максимум примыкающих существующих участков DN" + original));
            }
            int required = Math.max(original, Math.max(newMax, adjacentMaxAfter));
            result.add(ChamberSizing.existingChamber(chamber.getId(), original, required, missing));
        }
        return result;
    }

    /** Новые камеры одного дерева: узлы NEW_CHAMBER и камера в точке врезки в трубу. */
    public List<ChamberSizing> newChambers(NewNetworkTree tree, Map<String, Integer> diameters,
                                           ExistingNetwork network, ReconstructionResult recon) {
        List<ChamberSizing> result = new ArrayList<>();
        for (NewNode node : tree.getNodes().values()) {
            if (node.getKind() != NodeKind.NEW_CHAMBER) {
                continue;
            }
            int d = 0;
            for (NewSegment seg : tree.incidentTo(node.getId())) {
                d = Math.max(d, diameters.get(seg.getId()));
            }
            result.add(ChamberSizing.newChamber(node.getId(), d));
        }
        if (tree.getTieIn().getExistingObjectType() == ExistingObjectType.HEAT_NETWORK
                && tree.getTieIn().getNewChamberId() != null) {
            ExistingSegment pipe = network.segment(tree.getTieIn().getExistingObjectId());
            // AUDIT-24.09 (Claude): §2.4 приложения и Разъяснение №14 — существующая сеть в расчётной
            // модели не реконструируется. ДУ новой камеры на трубе = max(ДУ нового участка, ДУ самой трубы
            // по входным данным); «ДУ после реконструкции» (recon.diameterAfterAt) сюда больше не попадает —
            // иначе при большом присоединённом расходе камера получала бы завышенный diameter и стоимость.
            int d = Math.max(diameters.get(tree.rootSegment().getId()), pipe.getDiameter());
            result.add(ChamberSizing.newChamber(tree.getTieIn().getNewChamberId(), d));
        }
        return result;
    }
}
