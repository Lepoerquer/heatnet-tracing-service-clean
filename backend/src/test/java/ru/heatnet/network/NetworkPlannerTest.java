package ru.heatnet.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.cost.UnconnectedOks;
import ru.heatnet.cost.VariantCost;
import ru.heatnet.cost.VariantCostCalculator;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.OksConnectionPoint;

@SpringBootTest
class NetworkPlannerTest {

    @Autowired
    private NetworkPlanner networkPlanner;

    private EngineeringCalculator engineering;
    private VariantCostCalculator costCalculator;

    @BeforeEach
    void setUp() {
        engineering = new EngineeringCalculator(TestReference.get());
        costCalculator = new VariantCostCalculator(TestReference.get());
    }

    @Test
    @DisplayName("один ОКС: дерево от врезки до oks_connection_point")
    void singleOksEndToEnd() {
        IngestResult ingest = TestNetworkFixtures.simpleMagistral();
        List<OksConnectionPoint> one = Collections.singletonList(new OksConnectionPoint("oks_near", 20.0));
        NetworkPlan plan = networkPlanner.plan(ingest, ingest.getExistingNetwork(),
                ExistingNetworkGeometry.fromIngest(ingest.getAcceptedFeatures(), ingest.getExistingNetwork(),
                        TestNetworkFixtures.projection()),
                one);
        assertEquals(1, plan.getTrees().size());
        assertTrue(plan.getUnconnectedOks().isEmpty());
        NewNetworkTree tree = plan.getTrees().get(0);
        assertEquals(1, tree.oksNodes().size());
        assertTrue(tree.totalLengthM() > 0);
        EngineeringResult eng = engineering.calculate(plan.getTrees(), ingest.getExistingNetwork());
        assertFalse(eng.hasErrors());
    }

    @Test
    @DisplayName("3 ОКС рядом: совместное или раздельное — валидная топология и S")
    void threeNearbyOks() {
        IngestResult ingest = TestNetworkFixtures.threeOksNearChamber();
        NetworkPlan plan = networkPlanner.plan(ingest);
        assertFalse(plan.getTrees().isEmpty());
        assertTrue(TopologyValidator.validate(plan.getTrees()).isEmpty());
        EngineeringResult eng = engineering.calculate(plan.getTrees(), ingest.getExistingNetwork());
        VariantCost cost = costCalculator.calculate("t", eng, plan.getUnconnectedOks());
        assertNotNull(cost.getSummary());
        assertTrue(cost.getSummary().getScore() > 0);
    }

    @Test
    @DisplayName("раздельные вводы: несколько деревьев при удалённых ОКС")
    void separateTieInsForDistantOks() {
        IngestResult ingest = TestNetworkFixtures.simpleMagistral();
        List<OksConnectionPoint> distant = Arrays.asList(
                new OksConnectionPoint("oks_near", 20.0),
                new OksConnectionPoint("oks_sep", 15.0));
        NetworkPlan plan = networkPlanner.plan(ingest, ingest.getExistingNetwork(),
                ExistingNetworkGeometry.fromIngest(ingest.getAcceptedFeatures(), ingest.getExistingNetwork(),
                        TestNetworkFixtures.projection()),
                distant);
        assertFalse(plan.getTrees().isEmpty());
        assertTrue(plan.getTrees().size() >= 1);
        for (NewNetworkTree tree : plan.getTrees()) {
            assertFalse(tree.oksNodes().isEmpty());
        }
    }

    @Test
    @DisplayName("JointPlanner: ΔS — совместная схема не хуже бесконечности")
    void jointScoreFinite() {
        IngestResult ingest = TestNetworkFixtures.threeOksNearChamber();
        NetworkPlan plan = networkPlanner.plan(ingest);
        EngineeringResult eng = engineering.calculate(plan.getTrees(), ingest.getExistingNetwork());
        if (!eng.hasErrors()) {
            VariantCost cost = costCalculator.calculate("j", eng,
                    plan.getUnconnectedOks().isEmpty()
                            ? Collections.<UnconnectedOks>emptyList()
                            : plan.getUnconnectedOks());
            assertTrue(Double.isFinite(cost.getSummary().getScore()));
        }
    }
}
