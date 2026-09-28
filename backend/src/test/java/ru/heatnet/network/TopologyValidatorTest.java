package ru.heatnet.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.TieInPoint;

class TopologyValidatorTest {

    @Test
    @DisplayName("новая камера: 5 примыканий — ошибка")
    void newChamberDegreeLimit() {
        NewNetworkTree tree = new NewNetworkTree(TieInPoint.intoPipe("tie", "net_1", 10.0, "nch"),
                Arrays.asList(NewNode.tieIn("tie"), NewNode.chamber("nch"),
                        NewNode.oks("o1", "oks1", 1), NewNode.oks("o2", "oks2", 1),
                        NewNode.oks("o3", "oks3", 1), NewNode.oks("o4", "oks4", 1)),
                Arrays.asList(
                        NewSegment.base("s0", "tie", "nch", 10),
                        NewSegment.base("s1", "nch", "o1", 10),
                        NewSegment.base("s2", "nch", "o2", 10),
                        NewSegment.base("s3", "nch", "o3", 10),
                        NewSegment.base("s4", "nch", "o4", 10)));
        List<String> errors = TopologyValidator.validate(Collections.singletonList(tree));
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("не более 4"));
    }

    @Test
    @DisplayName("новая камера: 4 примыкания — допустимо")
    void newChamberDegreeOk() {
        NewNetworkTree tree = new NewNetworkTree(TieInPoint.intoPipe("tie", "net_1", 10.0, "nch"),
                Arrays.asList(NewNode.tieIn("tie"), NewNode.chamber("nch"),
                        NewNode.oks("o1", "oks1", 1), NewNode.oks("o2", "oks2", 1),
                        NewNode.oks("o3", "oks3", 1)),
                Arrays.asList(
                        NewSegment.base("s0", "tie", "nch", 10),
                        NewSegment.base("s1", "nch", "o1", 10),
                        NewSegment.base("s2", "nch", "o2", 10),
                        NewSegment.base("s3", "nch", "o3", 10)));
        assertTrue(TopologyValidator.validate(Collections.singletonList(tree)).isEmpty());
    }
}
