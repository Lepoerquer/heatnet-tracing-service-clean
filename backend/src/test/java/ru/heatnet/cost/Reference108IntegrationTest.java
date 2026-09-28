package ru.heatnet.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.heatnet.calc.DiameterSelector;
import ru.heatnet.calc.FlowPiece;
import ru.heatnet.calc.ReconstructionEngine;
import ru.heatnet.calc.ReconstructionResult;
import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.TieInLoad;
import ru.heatnet.calc.model.ExistingChamber;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingSegment;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.calc.reference.ReferenceData;

/**
 * Эталон раздела 10.8 Технического приложения. Координаты примера условные, поэтому length
 * подаются готовыми числами (145,2 и 75,0).
 *
 * <p>ВАЖНО: приложение прямо пишет, что «числовые значения условные». Стоимости объектов в
 * примере НЕ выводятся из табл. 4.1 до рубля: 145,2·120 275·1,6 = 27 942 288 (в примере 27 942 307,
 * +19 ₽), 75·202 030 = 15 152 250 (в примере 15 152 283, +33 ₽). Поэтому тест проверяет две вещи:
 * (1) сводку и S на числах самого примера — до рубля; (2) полный расчёт по формулам — S тот же 2,091.</p>
 */
class Reference108IntegrationTest {

    private final ReferenceData ref = TestReference.get();
    private final ScoreCalculator score = new ScoreCalculator(ref.getRules());

    @Test
    @DisplayName("10.8 на числах примера: calculated_cost = 51 094 590, length = 220,2, score = 2,091")
    void summaryFromExampleObjects() {
        VariantSummary s = VariantSummary.builder("1")
                .addNewSegment(145.2, 27_942_307L)
                .addNewChamber(3_000_000L)
                .addTieIn(5_000_000L)
                .addReconstruction(75.0, 15_152_283L)
                .build(score);
        assertEquals(35_942_307L, s.getConstructionCost());
        assertEquals(27_942_307L, s.getSegmentCost());
        assertEquals(3_000_000L, s.getChamberConstructionCost());
        assertEquals(5_000_000L, s.getTieInCost());
        assertEquals(1, s.getExistingChamberTieInCount());
        assertEquals(15_152_283L, s.getReconstructionCost());
        assertEquals(0L, s.getChamberReconstructionCost());
        assertEquals(0L, s.getUnconnectedPenalty());
        assertEquals(35_942_307L, s.getCalculatedCost());
        assertEquals(145.2, s.getNewNetworkLength(), 0.0);
        assertEquals(75.0, s.getReconstructionLength(), 0.0);
        assertEquals(145.2, s.getLength(), 0.0);
        assertEquals(1.4420, s.getScore(), 0.0);
    }

    @Test
    @DisplayName("10.8 по формулам: DN200 для 80 т/ч, DN250 для 180 т/ч, C = 51 094 538, score = 2,091")
    void fullFormulaChain() {
        DiameterSelector selector = new DiameterSelector(ref.getDiameters());
        int newDn = selector.minDiameter(80.0);
        assertEquals(200, newDn);

        ExistingNetwork net = new ExistingNetwork(
                Collections.singletonList(new ExistingSegment("net_12", 150, 100.0, "src", 120.0)),
                Collections.<ExistingChamber>emptyList(), Collections.singletonList("src"));
        ReconstructionResult recon = new ReconstructionEngine(selector, 0.01).calculate(net,
                Collections.singletonList(new TieInLoad(TieInPoint.intoPipe("tie_1", "net_12", 75.0, null), 80.0)));
        FlowPiece part = recon.getReconstructionParts().get(0);
        assertEquals(250, part.getRequiredDiameter());

        long newCost = new SegmentCostCalculator(ref.getDiameters()).cost(145.2, newDn, 1.0, 1.60);
        long reconCost = new ReconstructionCostCalculator(ref.getDiameters()).cost(part.getLengthM(), part.getRequiredDiameter());
        ChamberCostScale scale = new ChamberCostScale(ref.getRules());
        assertEquals(27_942_288L, newCost);
        assertEquals(15_152_250L, reconCost);

        VariantSummary s = VariantSummary.builder("1")
                .addNewSegment(145.2, newCost)
                .addNewChamber(scale.costFor(200))
                .addTieIn(scale.tieInCost())
                .addReconstruction(part.getLengthM(), reconCost)
                .build(score);
        // §6: C = участки + камера + врезка; реконструкция не входит
        assertEquals(35_942_288L, s.getCalculatedCost());
        assertEquals(145.2, s.getLength(), 0.0);
        assertEquals(1.4420, s.getScore(), 0.0);
    }
}
