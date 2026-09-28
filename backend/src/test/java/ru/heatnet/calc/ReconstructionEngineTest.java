package ru.heatnet.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.heatnet.calc.model.CalcDiagnostic;
import ru.heatnet.calc.model.ExistingChamber;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingSegment;
import ru.heatnet.calc.model.TieInPoint;

class ReconstructionEngineTest {

    private final ReconstructionEngine engine =
            new ReconstructionEngine(new DiameterSelector(TestReference.get().getDiameters()), 0.01);

    private static ExistingNetwork net(ExistingSegment... segs) {
        return new ExistingNetwork(Arrays.asList(segs), Collections.<ExistingChamber>emptyList(),
                Collections.singletonList("src"));
    }

    @Test
    @DisplayName("пример 10.8: net_12 DN150, 100 + 80 = 180 т/ч → DN250 только на части 75 м к источнику")
    void example108() {
        ExistingNetwork n = net(new ExistingSegment("net_12", 150, 100.0, "src", 120.0));
        ReconstructionResult r = engine.calculate(n,
                Collections.singletonList(new TieInLoad(TieInPoint.intoPipe("tie_1", "net_12", 75.0, null), 80.0)));
        List<FlowPiece> parts = r.getReconstructionParts();
        assertEquals(1, parts.size());
        FlowPiece p = parts.get(0);
        assertEquals(0.0, p.getFromM(), 1e-9);
        assertEquals(75.0, p.getLengthM(), 1e-9);
        assertEquals(100.0, p.getExistingFlowTph(), 1e-9);
        assertEquals(80.0, p.getAddedFlowTph(), 1e-9);
        assertEquals(180.0, p.getCalculatedFlowTph(), 1e-9);
        assertEquals(150, p.getExistingDiameter());
        assertEquals(250, p.getRequiredDiameter());
        assertFalse(p.coversWholeSegment(120.0, 0.01));
    }

    @Test
    @DisplayName("расход распространяется по upstream_object_id через камеру до источника; ветви суммируются")
    void propagationThroughChamberSums() {
        // src ← net_1 (DN200, 120 т/ч, 200 м) ← ch ← net_2 (DN150, 50 т/ч) и net_3 (DN100, 10 т/ч)
        ExistingNetwork n = new ExistingNetwork(
                Arrays.asList(new ExistingSegment("net_1", 200, 120.0, "src", 200),
                        new ExistingSegment("net_2", 150, 50.0, "ch", 100),
                        new ExistingSegment("net_3", 100, 10.0, "ch", 80)),
                Collections.singletonList(new ExistingChamber("ch", 200, "net_1")),
                Collections.singletonList("src"));
        ReconstructionResult r = engine.calculate(n, Arrays.asList(
                new TieInLoad(TieInPoint.intoPipe("t2", "net_2", 100.0, null), 20.0),   // врезка у дальнего конца: весь участок
                new TieInLoad(TieInPoint.intoPipe("t3", "net_3", 30.0, null), 15.0)));
        // net_1: 120 + 20 + 15 = 155 > 152,3 → DN250, весь участок 200 м
        FlowPiece n1 = r.getPiecesBySegment().get("net_1").get(0);
        assertEquals(35.0, n1.getAddedFlowTph(), 1e-9);
        assertEquals(155.0, n1.getCalculatedFlowTph(), 1e-9);
        assertEquals(250, n1.getRequiredDiameter());
        assertTrue(n1.coversWholeSegment(200, 0.01));
        // net_2: 50 + 20 = 70 > 65,1 → DN200 на всех 100 м
        FlowPiece n2 = r.getPiecesBySegment().get("net_2").get(0);
        assertEquals(100.0, n2.getLengthM(), 1e-9);
        assertEquals(200, n2.getRequiredDiameter());
        // net_3: 10 + 15 = 25 ≤ 40,2 (DN125) > DN100 → реконструкция 30 м
        FlowPiece n3 = r.getPiecesBySegment().get("net_3").get(0);
        assertEquals(30.0, n3.getLengthM(), 1e-9);
        assertEquals(125, n3.getRequiredDiameter());
        assertEquals(3, r.getReconstructionParts().size());
    }

    @Test
    @DisplayName("две врезки в одну трубу: [0;40] несёт обе (30 + 50), [40;100] — только дальнюю, [100;120] — ничего")
    void twoTieInsInOnePipe() {
        ExistingNetwork n = net(new ExistingSegment("p", 150, 60.0, "src", 120.0));
        ReconstructionResult r = engine.calculate(n, Arrays.asList(
                new TieInLoad(TieInPoint.intoPipe("a", "p", 40.0, null), 30.0),
                new TieInLoad(TieInPoint.intoPipe("b", "p", 100.0, null), 50.0)));
        List<FlowPiece> pieces = r.getPiecesBySegment().get("p");
        assertEquals(2, pieces.size());
        assertEquals(40.0, pieces.get(0).getLengthM(), 1e-9);
        assertEquals(140.0, pieces.get(0).getCalculatedFlowTph(), 1e-9); // 60 + 30 + 50 → DN200
        assertEquals(200, pieces.get(0).getRequiredDiameter());
        assertEquals(60.0, pieces.get(1).getLengthM(), 1e-9);
        assertEquals(110.0, pieces.get(1).getCalculatedFlowTph(), 1e-9); // 60 + 50 → DN200
        assertEquals(200, r.diameterAfterAt("p", 150, 0.0));             // у конца к источнику — DN200
        assertEquals(150, r.diameterAfterAt("p", 150, 110.0));           // за дальней врезкой не затронут
    }

    @Test
    @DisplayName("реконструкция не нужна, если требуемый DN не больше существующего")
    void noReconstructionWhenFits() {
        ExistingNetwork n = net(new ExistingSegment("p", 200, 100.0, "src", 50.0));
        ReconstructionResult r = engine.calculate(n,
                Collections.singletonList(new TieInLoad(TieInPoint.intoPipe("a", "p", 50.0, null), 20.0)));
        assertEquals(1, r.getPiecesBySegment().get("p").size()); // 120 т/ч ≤ 152,3 → DN200
        assertTrue(r.getReconstructionParts().isEmpty());
    }

    @Test
    @DisplayName("нет flow_tph у существующего участка → принят 0 с предупреждением")
    void missingExistingFlow() {
        ExistingNetwork n = net(new ExistingSegment("p", 50, null, "src", 50.0));
        ReconstructionResult r = engine.calculate(n,
                Collections.singletonList(new TieInLoad(TieInPoint.intoPipe("a", "p", 50.0, null), 5.0)));
        FlowPiece p = r.getPiecesBySegment().get("p").get(0);
        assertEquals(5.0, p.getCalculatedFlowTph(), 1e-9);
        assertEquals(65, p.getRequiredDiameter());
        assertTrue(p.isExistingFlowMissing());
        assertEquals("EXISTING_FLOW_MISSING", r.getDiagnostics().get(0).getCode());
    }

    @Test
    @DisplayName("цикл и ссылка на неизвестный объект → ошибка; обрыв цепочки → предупреждение")
    void brokenChains() {
        ExistingNetwork cyclic = net(new ExistingSegment("a", 100, 1.0, "b", 10), new ExistingSegment("b", 100, 1.0, "a", 10));
        assertThrows(CalcException.class, () -> engine.calculate(cyclic,
                Collections.singletonList(new TieInLoad(TieInPoint.intoPipe("t", "a", 10.0, null), 1.0))));
        ExistingNetwork unknown = net(new ExistingSegment("a", 100, 1.0, "ghost", 10));
        assertThrows(CalcException.class, () -> engine.calculate(unknown,
                Collections.singletonList(new TieInLoad(TieInPoint.intoPipe("t", "a", 10.0, null), 1.0))));
        ExistingNetwork orphan = net(new ExistingSegment("a", 100, 1.0, null, 10));
        ReconstructionResult r = engine.calculate(orphan,
                Collections.singletonList(new TieInLoad(TieInPoint.intoPipe("t", "a", 10.0, null), 1.0)));
        boolean warned = false;
        for (CalcDiagnostic d : r.getDiagnostics()) {
            warned |= "CHAIN_NOT_REACHING_SOURCE".equals(d.getCode());
        }
        assertTrue(warned);
    }

    @Test
    @DisplayName("расстояние врезки больше длины участка → ошибка")
    void distanceBeyondPipe() {
        ExistingNetwork n = net(new ExistingSegment("p", 100, 1.0, "src", 10));
        assertThrows(CalcException.class, () -> engine.calculate(n,
                Collections.singletonList(new TieInLoad(TieInPoint.intoPipe("t", "p", 10.5, null), 1.0))));
    }
}
