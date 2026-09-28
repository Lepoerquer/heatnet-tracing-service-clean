package ru.heatnet.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.TieInPoint;

class FlowAndDiameterTest {

    private final DiameterSelector selector = new DiameterSelector(TestReference.get().getDiameters());

    @Test
    @DisplayName("мин. DN: 80 т/ч → DN200 (65,1 < 80 ≤ 152,3); граница ≥ включительно")
    void minDiameter() {
        assertEquals(50, selector.minDiameter(0.1));
        assertEquals(50, selector.minDiameter(3.5));    // ровно пропускная способность DN50
        assertEquals(65, selector.minDiameter(3.51));
        assertEquals(150, selector.minDiameter(65.1));
        assertEquals(200, selector.minDiameter(80.0));   // пример 10.8: flow 80 → DN200
        assertEquals(250, selector.minDiameter(180.0));  // пример 10.8: 100 + 80 = 180 → DN250
        assertEquals(1400, selector.minDiameter(22_501.9));
    }

    @Test
    @DisplayName("расход больше DN1400 → принимается наибольший ДУ, без исключения")
    void flowAboveTable() {
        assertTrue(selector.exceedsTable(22_502.0));
        assertEquals(1400, selector.minDiameter(22_502.0));
    }

    /** tie → s1 → ch → s2 → oks1 (30,1) ; ch → s3 → oks2 (50,2). */
    static NewNetworkTree branchingTree() {
        return new NewNetworkTree(TieInPoint.intoChamber("tie", "chX"),
                Arrays.asList(NewNode.tieIn("tie"), NewNode.chamber("ch"),
                        NewNode.oks("p1", "oks1", 30.1), NewNode.oks("p2", "oks2", 50.2)),
                Arrays.asList(NewSegment.base("s1", "tie", "ch", 100),
                        NewSegment.base("s2", "ch", "p1", 40),
                        NewSegment.base("s3", "ch", "p2", 60)));
    }

    @Test
    @DisplayName("агрегация: общий участок = 30,1 + 50,2 = 80,3 т/ч ровно (без погрешности double)")
    void flowAggregation() {
        Map<String, Double> flows = new FlowAggregator().aggregate(branchingTree());
        assertEquals(30.1, flows.get("s2"), 0.0);
        assertEquals(50.2, flows.get("s3"), 0.0);
        assertEquals(80.3, flows.get("s1"), 0.0);
    }

    @Test
    @DisplayName("DN по дереву: 30,1 → DN125, 50,2 → DN150, 80,3 → DN200; к источнику не убывает")
    void diametersOnTree() {
        NewNetworkTree tree = branchingTree();
        Map<String, Integer> dn = selector.select(tree, new FlowAggregator().aggregate(tree));
        assertEquals(125, (int) dn.get("s2"));
        assertEquals(150, (int) dn.get("s3"));
        assertEquals(200, (int) dn.get("s1"));
    }

    @Test
    @DisplayName("дерево: ОКС не может быть транзитным узлом, узел не может иметь два пути")
    void treeValidation() {
        assertThrows(CalcException.class, () -> new NewNetworkTree(TieInPoint.intoChamber("tie", "c"),
                Arrays.asList(NewNode.tieIn("tie"), NewNode.oks("p1", null, 1), NewNode.oks("p2", null, 1)),
                Arrays.asList(NewSegment.base("s1", "tie", "p1", 10), NewSegment.base("s2", "p1", "p2", 10))));
        assertThrows(CalcException.class, () -> new NewNetworkTree(TieInPoint.intoChamber("tie", "c"),
                Arrays.asList(NewNode.tieIn("tie"), NewNode.chamber("a"), NewNode.oks("p", null, 1)),
                Arrays.asList(NewSegment.base("s1", "tie", "a", 10), NewSegment.base("s2", "a", "p", 10),
                        NewSegment.base("s3", "tie", "p", 10))));
    }
}
