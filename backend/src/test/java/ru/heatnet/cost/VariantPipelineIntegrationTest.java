package ru.heatnet.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.heatnet.calc.ChamberSizing;
import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.TieInSizing;
import ru.heatnet.calc.model.ExistingChamber;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingSegment;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.calc.reference.ReferenceData;

/**
 * Сквозной вариант: две врезки (в камеру и в трубу), разветвление, спецпроход, реконструкция
 * участков и камеры, неподключённый ОКС. Все числа посчитаны вручную ниже.
 *
 * <pre>
 * Существующая сеть: src ← net_1 (DN200, 120 т/ч, 200 м) ← ch_ex (DN200)
 *                                   ch_ex ← net_2 (DN150, 50 т/ч, 100 м), ch_ex ← net_3 (DN100, 10 т/ч, 80 м)
 * Дерево A (врезка в камеру ch_ex):
 *   tie_A → A1 60 м → nch1 → A2 40 м → oks1 (20 т/ч)
 *                     nch1 → A3 30 м special(road 1,6) → tn1 → A4 50 м → oks2 (15 т/ч)
 *   расходы: A2 = 20 (DN100), A3 = A4 = 15 (DN100), A1 = 35 (DN125); плеть DN100 = 120 м ≤ 419
 * Дерево B (врезка в net_2 в 30 м от конца к источнику, новая камера nchB):
 *   tie_B → B1 25 м → oks3 (40 т/ч → DN125, 40 ≤ 40,2)
 * Реконструкция: net_2 [0;30]: 50 + 40 = 90 → DN200 > 150 → 30 м · 181 766 = 5 452 980
 *                net_1: 120 + 35 + 40 = 195 → DN250 > 200 → 200 м · 202 030 = 40 406 000
 * Камеры: nch1 = max(125, 100, 100) = DN125 → 3 млн; nchB = max(125, net_2 после рек. DN200) → 3 млн
 *         ch_ex: исходный DN200; примыкают net_1 → DN250 после рек., net_2 → 200, net_3 → 100, A1 → 125
 *         → требуемый DN250 > 200 → реконструкция 5 млн (один раз)
 * Новые участки: A1 60·97 275 = 5 836 500; A2 40·89 748 = 3 589 920; A3 30·89 748·1,6 = 4 307 904;
 *                A4 50·89 748 = 4 487 400; B1 25·97 275 = 2 431 875 → 20 653 599
 * Врезки 2 · 5 млн = 10 млн. Штраф oks4 (12,5 т/ч) = 106 250 000.
 * C = 20 653 599 + 6 000 000 + 10 000 000 + 45 858 980 + 5 000 000 + 106 250 000 = 193 762 579
 * L = 205 + 230 = 435 м; S = 0,7 · 7,75050316 + 0,3 · 4,35 = 6,7303522 → 6,730
 * </pre>
 */
class VariantPipelineIntegrationTest {

    private final ReferenceData ref = TestReference.get();

    private EngineeringResult engineering() {
        ExistingNetwork existing = new ExistingNetwork(
                Arrays.asList(new ExistingSegment("net_1", 200, 120.0, "src", 200),
                        new ExistingSegment("net_2", 150, 50.0, "ch_ex", 100),
                        new ExistingSegment("net_3", 100, 10.0, "ch_ex", 80)),
                Collections.singletonList(new ExistingChamber("ch_ex", 200, "net_1")),
                Collections.singletonList("src"));
        NewNetworkTree a = new NewNetworkTree(TieInPoint.intoChamber("tie_A", "ch_ex"),
                Arrays.asList(NewNode.tieIn("tie_A"), NewNode.chamber("nch1"), NewNode.technical("tn1"),
                        NewNode.oks("cp1", "oks1", 20.0), NewNode.oks("cp2", "oks2", 15.0)),
                Arrays.asList(NewSegment.base("A1", "tie_A", "nch1", 60),
                        NewSegment.base("A2", "nch1", "cp1", 40),
                        NewSegment.special("A3", "nch1", "tn1", 30, 1.6),
                        NewSegment.base("A4", "tn1", "cp2", 50)));
        NewNetworkTree b = new NewNetworkTree(TieInPoint.intoPipe("tie_B", "net_2", 30.0, "nchB"),
                Arrays.asList(NewNode.tieIn("tie_B"), NewNode.oks("cp3", "oks3", 40.0)),
                Collections.singletonList(NewSegment.base("B1", "tie_B", "cp3", 25)));
        return new EngineeringCalculator(ref).calculate(Arrays.asList(a, b), existing);
    }

    @Test
    @DisplayName("инженерная часть: DN, реконструкция, камеры, врезки")
    void engineeringPart() {
        EngineeringResult eng = engineering();
        assertFalse(eng.hasErrors());
        assertEquals(125, (int) eng.getTrees().get(0).getDiameters().get("A1"));
        assertEquals(100, (int) eng.getTrees().get(0).getDiameters().get("A3"));
        assertEquals(35.0, eng.getTrees().get(0).getFlows().get("A1"), 0.0);
        assertEquals(125, (int) eng.getTrees().get(1).getDiameters().get("B1"));

        assertEquals(2, eng.getReconstruction().getReconstructionParts().size());
        assertEquals(195.0, eng.getReconstruction().getPiecesBySegment().get("net_1").get(0).getCalculatedFlowTph(), 1e-9);

        assertEquals(2, eng.getNewChambers().size());
        // AUDIT-24.09 (Claude): §2.4 приложения / Разъяснение №14 — существующая сеть не реконструируется,
        // ДУ новой камеры врезки = max(ДУ нового участка 125, ДУ трубы по входу 150) = 150
        // (раньше 200 — «ДУ трубы после реконструкции»). Стоимость та же (полоса 50–200 → 3 млн).
        for (ChamberSizing ch : eng.getNewChambers()) {
            assertEquals("nch1".equals(ch.getChamberId()) ? 125 : 150, ch.getRequiredDiameter());
        }
        ChamberSizing chEx = eng.getExistingChambers().get(0);
        assertEquals(200, (int) chEx.getOriginalDiameter());
        assertEquals(250, chEx.getRequiredDiameter());
        assertTrue(chEx.isReconstructionRequired());

        TieInSizing tA = eng.getTieIns().get(0);
        assertEquals(200, tA.getExistingDiameter());
        assertEquals(125, tA.getRequiredDiameter());
        TieInSizing tB = eng.getTieIns().get(1);
        assertEquals(150, tB.getExistingDiameter());
        assertEquals(125, tB.getRequiredDiameter());
    }

    @Test
    @DisplayName("смета §6/§3.2: construction = участки+камеры+врезка в живую камеру; реконструкция вне C/L")
    void costPart() {
        VariantCost v = new VariantCostCalculator(ref).calculate("1", engineering(),
                Collections.singletonList(new UnconnectedOks("oks4", 12.5)));
        assertEquals(4_307_904L, (long) v.getSegmentCosts().get("A3"));
        VariantSummary s = v.getSummary();
        assertEquals(20_653_599L, s.getSegmentCost());
        assertEquals(6_000_000L, s.getChamberConstructionCost());
        assertEquals(5_000_000L, s.getTieInCost());
        assertEquals(1, s.getExistingChamberTieInCount());
        assertEquals(31_653_599L, s.getConstructionCost());
        assertEquals(45_858_980L, s.getReconstructionCost());
        assertEquals(5_000_000L, s.getChamberReconstructionCost());
        assertEquals(106_250_000L, s.getUnconnectedPenalty());
        assertEquals(137_903_599L, s.getCalculatedCost());
        assertEquals(205.0, s.getNewNetworkLength(), 0.0);
        assertEquals(230.0, s.getReconstructionLength(), 0.0);
        assertEquals(205.0, s.getLength(), 0.0);
        assertEquals(4.4763, s.getScore(), 0.0);
        assertEquals(Collections.singletonList("oks4"), s.getUnconnectedOksIds());

        long sumObjects = 0;
        for (long c : v.getSegmentCosts().values()) {
            sumObjects += c;
        }
        assertEquals(s.getSegmentCost(), sumObjects);
    }

    @Test
    @DisplayName("реконструкция камеры учитывается один раз при двух врезках в неё")
    void chamberReconstructedOnce() {
        ExistingNetwork existing = new ExistingNetwork(
                Collections.singletonList(new ExistingSegment("net_1", 100, 20.0, "src", 50)),
                Collections.singletonList(new ExistingChamber("ch", 100, "net_1")),
                Collections.singletonList("src"));
        NewNetworkTree t1 = new NewNetworkTree(TieInPoint.intoChamber("t1", "ch"),
                Arrays.asList(NewNode.tieIn("t1"), NewNode.oks("c1", null, 30.0)),
                Collections.singletonList(NewSegment.base("s1", "t1", "c1", 10)));
        NewNetworkTree t2 = new NewNetworkTree(TieInPoint.intoChamber("t2", "ch"),
                Arrays.asList(NewNode.tieIn("t2"), NewNode.oks("c2", null, 30.0)),
                Collections.singletonList(NewSegment.base("s2", "t2", "c2", 10)));
        VariantCost v = new VariantCostCalculator(ref).calculate("x",
                new EngineeringCalculator(ref).calculate(Arrays.asList(t1, t2), existing),
                Collections.<UnconnectedOks>emptyList());
        // net_1: 20 + 30 + 30 = 80 → DN200; ch: max(DN200, 125, 125) = 200 > 100 → 3 млн один раз
        assertEquals(1, v.getChamberReconstructionCosts().size());
        assertEquals(3_000_000L, v.getSummary().getChamberReconstructionCost());
        assertEquals(10_000_000L, v.getSummary().getTieInCost());
    }
}
