package ru.heatnet.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.RulesTestGeometry;
import ru.heatnet.rules.SpatialConstraintEngine;
import ru.heatnet.rules.TestRules;
import ru.heatnet.rules.model.CrossingResult;
import ru.heatnet.rules.model.RestrictionFeature;

/**
 * QA (роль 4): регрессия после правок 20–21.09.
 *
 * <p>{@code RestrictionSpatialIndex.build()} вставляет ОДИН И ТОТ ЖЕ {@code IndexedRestriction}
 * в STRtree дважды — по {@code specialZone} и по {@code linearCorridor}. У линейных спецобъектов
 * (газ, кабель, теплосеть) обе зоны непусты, поэтому {@code querySpecial} возвращает объект
 * дважды, и одно физическое пересечение превращается в два спецучастка.</p>
 */
class QaDuplicateIndexTest {

    private final ReferenceData ref = TestRules.reference();
    private final GeometryFactory gf = TestRules.utmFactory();
    private final RestrictionEngineFactory factory = TestRules.engineFactory();

    private RestrictionFeature feat(String id, String key, org.locationtech.jts.geom.Geometry g) {
        return new RestrictionFeature(id, key, ref.getRules().restriction(key), g);
    }

    @Test
    @DisplayName("QA-DUP-1: одно пересечение газопровода даёт ОДНО пересечение, не два")
    void singleGasCrossingIsReportedOnce() {
        LineString gas = RulesTestGeometry.line(gf, 100, -50, 100, 50);
        SpatialConstraintEngine e = factory.create(
                Collections.singletonList(feat("g1", "gas_pipeline", gas)), 200);
        LineString seg = RulesTestGeometry.line(gf, 0, 0, 200, 0);

        List<CrossingResult> crossings = e.findCrossings(seg, 200);
        Set<String> ids = new LinkedHashSet<>();
        for (CrossingResult c : crossings) {
            ids.add(c.getRestrictionId());
        }
        assertEquals(1, ids.size(), "ожидался один объект-ограничение");
        assertEquals(1, crossings.size(),
                "одно физическое пересечение одного газопровода должно дать ОДИН CrossingResult, "
                        + "получено " + crossings.size() + " (дубль из STRtree: объект вставлен "
                        + "и по specialZone, и по linearCorridor)");
    }

    @Test
    @DisplayName("QA-DUP-2: дубль удваивает спецучастки → двойная оплата K_спец")
    void duplicateSectionsWouldBePaidTwice() {
        LineString gas = RulesTestGeometry.line(gf, 100, -50, 100, 50);
        SpatialConstraintEngine e = factory.create(
                Collections.singletonList(feat("g1", "gas_pipeline", gas)), 200);
        LineString seg = RulesTestGeometry.line(gf, 0, 0, 200, 0);
        assertEquals(1, e.extractSpecialSections(seg, 200).size(),
                "§4: одно пересечение — один специальный проход");
    }
}
