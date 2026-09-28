package ru.heatnet.cost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.heatnet.calc.CalcException;
import ru.heatnet.calc.ChamberSizing;
import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.FlowPiece;
import ru.heatnet.calc.TieInSizing;
import ru.heatnet.calc.TreeCalcResult;
import ru.heatnet.calc.model.ExistingObjectType;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.reference.ReferenceData;

/**
 * M6. Смета варианта (§6 нового приложения): новые участки + новые камеры
 * + врезки в существующие камеры + штраф. Реконструкция не входит в C и L.
 */
public final class VariantCostCalculator {

    private final SegmentCostCalculator segmentCost;
    private final ReconstructionCostCalculator reconstructionCost;
    private final ChamberCostScale chamberScale;
    private final UnconnectedPenaltyCalculator penalty;
    private final ScoreCalculator scoreCalculator;

    public VariantCostCalculator(ReferenceData reference) {
        this.segmentCost = new SegmentCostCalculator(reference.getDiameters());
        this.reconstructionCost = new ReconstructionCostCalculator(reference.getDiameters());
        this.chamberScale = new ChamberCostScale(reference.getRules());
        this.penalty = new UnconnectedPenaltyCalculator(reference.getRules());
        this.scoreCalculator = new ScoreCalculator(reference.getRules());
    }

    public VariantCost calculate(String variantId, EngineeringResult eng, List<UnconnectedOks> unconnected) {
        VariantSummary.Builder summary = VariantSummary.builder(variantId);

        Map<String, Long> segmentCosts = new LinkedHashMap<>();
        for (TreeCalcResult tr : eng.getTrees()) {
            for (NewSegment seg : tr.getTree().segmentsTopDown()) {
                long c = segmentCost.cost(seg, tr.getDiameters().get(seg.getId()));
                if (segmentCosts.put(seg.getId(), c) != null) {
                    throw new CalcException("Вариант " + variantId + ": id участка " + seg.getId() + " не уникален");
                }
                summary.addNewSegment(seg.getLengthM(), c);
            }
        }

        Map<String, Long> newChamberCosts = new LinkedHashMap<>();
        for (ChamberSizing ch : eng.getNewChambers()) {
            long c = chamberScale.costFor(ch.getRequiredDiameter());
            if (newChamberCosts.put(ch.getChamberId(), c) != null) {
                throw new CalcException("Вариант " + variantId + ": id камеры " + ch.getChamberId() + " не уникален");
            }
            summary.addNewChamber(c);
        }

        // §3.2: 5 млн — только присоединение к СУЩЕСТВУЮЩЕЙ камере.
        // Новая камера уже включает присоединение к сети.
        Map<String, Long> tieInCosts = new LinkedHashMap<>();
        for (TieInSizing t : eng.getTieIns()) {
            if (t.getTieIn().getExistingObjectType() != ExistingObjectType.HEAT_CHAMBER) {
                continue;
            }
            long c = chamberScale.tieInCost();
            tieInCosts.put(t.getTieIn().getId(), c);
            summary.addTieIn(c);
        }

        List<CostedPiece> reconCosts = new ArrayList<>();
        for (FlowPiece p : eng.getReconstruction().getReconstructionParts()) {
            long c = reconstructionCost.cost(p.getLengthM(), p.getRequiredDiameter());
            reconCosts.add(new CostedPiece(p, c));
            summary.addReconstruction(p.getLengthM(), c);
        }

        Map<String, Long> chamberReconCosts = new LinkedHashMap<>();
        for (ChamberSizing ch : eng.getExistingChambers()) {
            if (ch.isReconstructionRequired()) {
                long c = chamberScale.costFor(ch.getRequiredDiameter());
                chamberReconCosts.put(ch.getChamberId(), c);
                summary.addChamberReconstruction(c);
            }
        }

        Map<String, Long> penalties = new LinkedHashMap<>();
        for (UnconnectedOks oks : unconnected) {
            long c = penalty.penalty(oks.getFlowTph());
            if (penalties.put(oks.getOksId(), c) != null) {
                throw new CalcException("Вариант " + variantId + ": ОКС " + oks.getOksId() + " указан как неподключённый дважды");
            }
            summary.addUnconnected(oks.getOksId(), c);
        }

        return new VariantCost(variantId, eng, segmentCosts, newChamberCosts, tieInCosts, reconCosts,
                chamberReconCosts, penalties, summary.build(scoreCalculator));
    }
}
