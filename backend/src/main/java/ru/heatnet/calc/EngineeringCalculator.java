package ru.heatnet.calc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.heatnet.calc.model.CalcDiagnostic;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingObjectType;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.calc.reference.ReferenceData;

/**
 * M5. Фасад инженерного расчёта одного варианта:
 * расходы → DN → предельная длина → реконструкция сети → камеры → врезки.
 * Геометрию не строит: принимает готовые деревья от network/.
 */
public final class EngineeringCalculator {

    private final FlowAggregator flowAggregator = new FlowAggregator();
    private final DiameterSelector diameterSelector;
    private final LengthLimitValidator lengthLimitValidator;
    private final ReconstructionEngine reconstructionEngine;
    private final ChamberReconstruction chamberReconstruction;

    public EngineeringCalculator(ReferenceData reference) {
        double tol = reference.getRules().getGeometryToleranceM();
        this.diameterSelector = new DiameterSelector(reference.getDiameters());
        this.lengthLimitValidator = new LengthLimitValidator(reference.getDiameters(),
                reference.getRules().getLengthLimitMaxDnSteps(), tol);
        this.reconstructionEngine = new ReconstructionEngine(diameterSelector, tol);
        this.chamberReconstruction = new ChamberReconstruction(reference.getRules().getMaxSegmentsPerChamber());
        this.chamberCost = dn -> {
            for (ru.heatnet.calc.reference.DnBand band : reference.getRules().getChamberScale()) {
                if (band.contains(dn)) {
                    return band.getLongValue();
                }
            }
            return 0L;
        };
    }

    private final DiameterOptimizer.ChamberCost chamberCost;

    /**
     * @param trees    деревья новой сети варианта, по одному на врезку (несколько независимых врезок)
     * @param existing существующая сеть
     */
    public EngineeringResult calculate(List<NewNetworkTree> trees, ExistingNetwork existing) {
        List<CalcDiagnostic> diagnostics = new ArrayList<>();
        List<TreeCalcResult> treeResults = new ArrayList<>();
        List<TieInLoad> loads = new ArrayList<>();
        Map<String, Map<String, Integer>> diametersByTieIn = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();

        for (NewNetworkTree tree : trees) {
            if (!ids.add(tree.getTieIn().getId())) {
                throw new CalcException("Врезка " + tree.getTieIn().getId() + " встречается в варианте дважды");
            }
            Map<String, Double> flows = flowAggregator.aggregate(tree);
            Map<String, Integer> hydraulic = diameterSelector.select(tree, flows);
            for (Map.Entry<String, Double> e : flows.entrySet()) {
                if (diameterSelector.exceedsTable(e.getValue())) {
                    diagnostics.add(CalcDiagnostic.error("FLOW_ABOVE_TABLE", e.getKey(),
                            "Расход " + e.getValue() + " т/ч превышает пропускную способность DN1400 — принят наибольший ДУ"));
                }
            }
            LengthLimitResult length = lengthLimitValidator.apply(tree, hydraulic, flows, chamberCost);
            diagnostics.addAll(length.getDiagnostics());
            treeResults.add(new TreeCalcResult(tree, flows, length));
            diametersByTieIn.put(tree.getTieIn().getId(), length.getDiameters());
            loads.add(new TieInLoad(tree.getTieIn(), flows.get(tree.rootSegment().getId())));
        }

        ReconstructionResult recon = reconstructionEngine.calculate(existing, loads);
        diagnostics.addAll(recon.getDiagnostics());

        List<ChamberSizing> newChambers = new ArrayList<>();
        for (TreeCalcResult tr : treeResults) {
            newChambers.addAll(chamberReconstruction.newChambers(tr.getTree(), tr.getDiameters(), existing, recon));
        }
        List<ChamberSizing> existingChambers =
                chamberReconstruction.existingChambers(existing, trees, diametersByTieIn, recon, diagnostics);
        Map<String, ChamberSizing> existingById = new LinkedHashMap<>();
        for (ChamberSizing c : existingChambers) {
            existingById.put(c.getChamberId(), c);
        }

        List<TieInSizing> tieIns = new ArrayList<>();
        for (TreeCalcResult tr : treeResults) {
            TieInPoint tie = tr.getTree().getTieIn();
            int existingDn = tie.getExistingObjectType() == ExistingObjectType.HEAT_NETWORK
                    ? existing.segment(tie.getExistingObjectId()).getDiameter()
                    : existingById.get(tie.getExistingObjectId()).getOriginalDiameter();
            String root = tr.getTree().rootSegment().getId();
            tieIns.add(new TieInSizing(tie, existingDn, tr.getDiameters().get(root), tr.getFlows().get(root)));
        }
        return new EngineeringResult(treeResults, tieIns, recon, newChambers, existingChambers, diagnostics);
    }
}
