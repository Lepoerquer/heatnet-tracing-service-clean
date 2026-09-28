package ru.heatnet.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.heatnet.ContestDatasetPaths;
import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.cost.UnconnectedOks;
import ru.heatnet.cost.VariantCost;
import ru.heatnet.cost.VariantCostCalculator;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;
import ru.heatnet.ingest.OksConnectionPoint;

@SpringBootTest
class NetworkContestDatasetTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private NetworkPlanner networkPlanner;

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @DisplayName("конкурсный dataset: первый ОКС либо в дереве, либо явно в unconnected")
    void planFirstOksOnContestDataset() throws Exception {
        Path dataset = ContestDatasetPaths.find().orElse(null);
        if (dataset == null) {
            return;
        }

        try (InputStream in = Files.newInputStream(dataset)) {
            IngestResult ingest = ingestService.ingest(in);
            assertFalse(ingest.getOksConnectionPoints().isEmpty());

            OksConnectionPoint first = ingest.getOksConnectionPoints().get(0);
            NetworkPlan plan = networkPlanner.plan(ingest, ingest.getExistingNetwork(),
                    ExistingNetworkGeometry.fromIngest(ingest.getAcceptedFeatures(),
                            ingest.getExistingNetwork(),
                            new ru.heatnet.geo.ProjectionService()),
                    Collections.singletonList(first));

            Set<String> requested = Collections.singleton(first.getId());
            Set<String> accounted = new HashSet<>();
            for (NewNetworkTree tree : plan.getTrees()) {
                for (NewNode node : tree.oksNodes()) {
                    accounted.add(node.getOksId());
                }
            }
            for (UnconnectedOks lost : plan.getUnconnectedOks()) {
                accounted.add(lost.getOksId());
            }
            assertEquals(requested, accounted, "ОКС не должен исчезать молча");

            if (!plan.getTrees().isEmpty()) {
                assertTrue(TopologyValidator.validate(plan.getTrees()).isEmpty());
                EngineeringCalculator eng = new EngineeringCalculator(TestReference.get());
                assertFalse(eng.calculate(plan.getTrees(), ingest.getExistingNetwork()).hasErrors());
                VariantCost cost = new VariantCostCalculator(TestReference.get())
                        .calculate("contest", eng.calculate(plan.getTrees(), ingest.getExistingNetwork()),
                                plan.getUnconnectedOks());
                assertTrue(Double.isFinite(cost.getSummary().getScore()));
                assertTrue(cost.getSummary().getScore() < 50.0);
            }
        }
    }
}
