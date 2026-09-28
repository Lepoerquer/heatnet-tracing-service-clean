package ru.heatnet.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.calc.reference.ReferenceData;

/** Предельная длина (табл. 4.1: DN50 — 181 м, DN65 — 245 м, DN80 — 327 м, DN100 — 419 м). */
class LengthLimitValidatorTest {

    private final ReferenceData ref = TestReference.get();
    private final LengthLimitValidator validator = new LengthLimitValidator(ref.getDiameters(), 1, 0.01);

    private LengthLimitResult run(NewNetworkTree tree) {
        Map<String, Integer> hyd = new DiameterSelector(ref.getDiameters()).select(tree, new FlowAggregator().aggregate(tree));
        return validator.apply(tree, hyd);
    }

    /** Линейная цепочка tie → n1 → ... → oks с заданными длинами и расходом ОКС. */
    private static NewNetworkTree chain(double oksFlow, double... lengths) {
        List<NewNode> nodes = new ArrayList<>();
        List<NewSegment> segs = new ArrayList<>();
        nodes.add(NewNode.tieIn("tie"));
        String prev = "tie";
        for (int i = 0; i < lengths.length; i++) {
            String next = i == lengths.length - 1 ? "oks" : "tn" + (i + 1);
            nodes.add(i == lengths.length - 1 ? NewNode.oks("oks", null, oksFlow) : NewNode.technical(next));
            segs.add(i == 1
                    ? NewSegment.special("s" + (i + 1), prev, next, lengths[i], 1.6)
                    : NewSegment.base("s" + (i + 1), prev, next, lengths[i]));
            prev = next;
        }
        return new NewNetworkTree(TieInPoint.intoChamber("tie", "c"), nodes, segs);
    }

    @Test
    @DisplayName("3 technical_node без смены DN: 40 + 25 (special) + 50 + 10 = 125 м — одна плеть, счётчик не сброшен")
    void technicalNodesDoNotResetCounter() {
        LengthLimitResult r = run(chain(20.0, 40, 25, 50, 10)); // 20 т/ч → DN100
        assertEquals(1, r.getRuns().size());
        assertEquals(100, r.getRuns().get(0).getDn());
        assertEquals(125.0, r.getRuns().get(0).getTotalLengthM(), 1e-9);
        assertEquals(4, r.getRuns().get(0).getSegmentIds().size());
        assertTrue(r.isWithinLimits());
    }

    @Test
    @DisplayName("превышение: DN50 100 + 100 = 200 м > 181 → вся плеть DN65 (лимит 245)")
    void raiseOneStep() {
        LengthLimitResult r = run(chain(3.0, 100, 100));
        assertEquals(65, (int) r.getDiameters().get("s1"));
        assertEquals(65, (int) r.getDiameters().get("s2"));
        assertEquals(50, (int) r.getHydraulicDiameters().get("s1"));
        assertTrue(r.isWithinLimits());
    }

    @Test
    @DisplayName("§2.3: 300 м при гидр. DN50 → следующий ДУ по длине (DN80, 327 м), без потолка +1")
    void raiseUntilLengthFits() {
        LengthLimitResult r = run(chain(3.0, 150, 150));
        assertEquals(80, (int) r.getDiameters().get("s1"));
        assertEquals(80, (int) r.getDiameters().get("s2"));
        assertTrue(r.isWithinLimits());
    }

    @Test
    @DisplayName("допуск округления: 181,005 м при лимите 181 м не считается превышением (протокол п. 9)")
    void tolerance() {
        LengthLimitResult r = run(chain(3.0, 100, 81.005));
        assertEquals(50, (int) r.getDiameters().get("s1"));
        assertTrue(r.isWithinLimits());
    }

    /**
     * tie → s1 (13 т/ч, DN80, 150 м) → ch → s2 → ОКС 3 т/ч (DN50, 150 м);
     *                                    ch → s3 → ОКС 10 т/ч (DN80, 200 м).
     * DN80: s1 + s3 = 350 м > 327 → s1, s3 → DN100 (419 м). DN50: s2 = 150 м ≤ 181 — отдельный счётчик.
     */
    @Test
    @DisplayName("разветвление: ветви одного DN суммируются, другой DN считается отдельно")
    void branchesSummedByNomenclature() {
        NewNetworkTree tree = new NewNetworkTree(TieInPoint.intoChamber("tie", "c"),
                Arrays.asList(NewNode.tieIn("tie"), NewNode.chamber("ch"),
                        NewNode.oks("p1", null, 3.0), NewNode.oks("p2", null, 10.0)),
                Arrays.asList(NewSegment.base("s1", "tie", "ch", 150),
                        NewSegment.base("s2", "ch", "p1", 150),
                        NewSegment.base("s3", "ch", "p2", 200)));
        LengthLimitResult r = run(tree);
        assertEquals(80, (int) r.getHydraulicDiameters().get("s1"));
        assertEquals(100, (int) r.getDiameters().get("s1"));
        assertEquals(100, (int) r.getDiameters().get("s3"));
        assertEquals(50, (int) r.getDiameters().get("s2"));
        assertTrue(r.isWithinLimits());
    }

    @Test
    @DisplayName("смена DN начинает новый отсчёт: DN80 300 м + DN50 150 м — оба в пределах")
    void diameterChangeResetsCounter() {
        NewNetworkTree tree = new NewNetworkTree(TieInPoint.intoChamber("tie", "c"),
                Arrays.asList(NewNode.tieIn("tie"), NewNode.chamber("ch"),
                        NewNode.oks("p1", null, 3.0), NewNode.oks("p2", null, 9.0)),
                Arrays.asList(NewSegment.base("s1", "tie", "ch", 300),
                        NewSegment.base("s2", "ch", "p1", 150),
                        NewSegment.base("s3", "ch", "p2", 20)));
        LengthLimitResult r = run(tree);
        assertEquals(80, (int) r.getDiameters().get("s1"));
        assertEquals(50, (int) r.getDiameters().get("s2"));
        assertTrue(r.isWithinLimits());
    }

    @Test
    @DisplayName("после подъёма DN сохраняется неубывание к источнику")
    void monotonicAfterRaise() {
        NewNetworkTree tree = new NewNetworkTree(TieInPoint.intoChamber("tie", "c"),
                Arrays.asList(NewNode.tieIn("tie"), NewNode.chamber("ch"),
                        NewNode.oks("p1", null, 3.0), NewNode.oks("p2", null, 5.0)),
                Arrays.asList(NewSegment.base("s1", "tie", "ch", 60),
                        NewSegment.base("s2", "ch", "p1", 190),
                        NewSegment.base("s3", "ch", "p2", 10)));
        LengthLimitResult r = run(tree);
        assertTrue(r.getDiameters().get("s1") >= r.getDiameters().get("s2"));
        assertTrue(r.getDiameters().get("s1") >= r.getDiameters().get("s3"));
        assertTrue(r.isWithinLimits());
    }
}
