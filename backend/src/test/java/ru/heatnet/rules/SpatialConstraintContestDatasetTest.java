package ru.heatnet.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.LineString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.heatnet.ContestDatasetPaths;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;

@SpringBootTest
class SpatialConstraintContestDatasetTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private RestrictionEngineFactory engineFactory;

    @Test
    @DisplayName("конкурсный dataset.geojson: ingest → M2 engine без ошибок")
    void contestDatasetSmoke() throws Exception {
        Path dataset = ContestDatasetPaths.find().orElse(null);
        if (dataset == null) {
            return;
        }

        try (InputStream in = Files.newInputStream(dataset)) {
            IngestResult result = ingestService.ingest(in);
            assertFalse(result.getRestrictionsWgs84().isEmpty());

            SpatialConstraintEngine engine = engineFactory.create(result, 300);
            assertNotNull(engine);
            assertFalse(result.getOksConnectionPoints().isEmpty());

            LineString probe = RulesTestGeometry.line(
                    TestRules.utmFactory(), 400000, 6174000, 401000, 6175000);
            engine.isSegmentBlocked(probe, 300);
            engine.findCrossings(probe, 300);
            engine.extractSpecialSections(probe, 300);
        }
    }
}
