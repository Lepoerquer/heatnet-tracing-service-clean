package ru.heatnet.variants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.model.ExistingChamber;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingSegment;
import ru.heatnet.cost.UnconnectedOks;
import ru.heatnet.cost.VariantCost;
import ru.heatnet.cost.VariantCostCalculator;
import ru.heatnet.network.NetworkPlan;

/**
 * M7: фильтр содержательного отличия (§6 приложения, §2.8 ТЗ).
 * «Небольшое смещение одной и той же трассы отдельным вариантом не считается».
 */
class VariantDistinctnessTest {

    private final VariantDistinctness filter = new VariantDistinctness();

    private static ExistingNetwork existing() {
        return new ExistingNetwork(
                Arrays.asList(new ExistingSegment("net_main", 300, null, "src", 120.0),
                        new ExistingSegment("net_alt", 300, null, "src", 100.0)),
                Arrays.asList(new ExistingChamber("ch_main", 300, "net_main"),
                        new ExistingChamber("ch_alt", 300, "net_alt")),
                Collections.singletonList("src"));
    }

    private GeneratedVariant variant(String id, VariantStrategy strategy, NetworkPlan plan) {
        EngineeringCalculator eng = new EngineeringCalculator(TestReference.get());
        VariantCost cost = new VariantCostCalculator(TestReference.get())
                .calculate(id, eng.calculate(plan.getTrees(), existing()),
                        Collections.<UnconnectedOks>emptyList());
        return new GeneratedVariant(id, strategy, plan, cost);
    }

    /** Трасса к ОКС o1 с врезкой в указанную камеру и заданным смещением конца, м. */
    private GeneratedVariant route(String id, VariantStrategy strategy, String chamber, double shiftM) {
        NetworkPlan plan = VariantsFixtures.planWithTree("tie_" + chamber + "_" + id, chamber, "o1", 12.0, 80.0,
                new Coordinate(413000, 6174000), new Coordinate(413060 + shiftM, 6174050));
        return variant(id, strategy, plan);
    }

    @Test
    @DisplayName("M7-DIST-1: сдвиг трассы на 5 м — тот же вариант, не новый (§6)")
    void smallShiftIsNotADistinctVariant() {
        GeneratedVariant a = route("v1", VariantStrategy.JOINT_ALL, "ch_main", 0);
        GeneratedVariant b = route("v2", VariantStrategy.SEPARATE_EACH, "ch_main", 5);
        assertFalse(filter.isDistinct(a, b),
                "смещение 5 м при пороге " + VariantDistinctness.DEFAULT_HAUSDORFF_M + " м — то же решение");
    }

    @Test
    @DisplayName("M7-DIST-2: сдвиг трассы на 100 м — уже другой вариант")
    void largeShiftIsDistinct() {
        GeneratedVariant a = route("v1", VariantStrategy.JOINT_ALL, "ch_main", 0);
        GeneratedVariant b = route("v2", VariantStrategy.SEPARATE_EACH, "ch_main", 100);
        assertTrue(filter.isDistinct(a, b), "расхождение трасс больше порога Хаусдорфа");
    }

    @Test
    @DisplayName("M7-DIST-3: другая точка врезки — другой вариант даже при той же геометрии (§2.8)")
    void differentTieInIsDistinct() {
        GeneratedVariant a = route("v1", VariantStrategy.JOINT_ALL, "ch_main", 0);
        GeneratedVariant b = route("v2", VariantStrategy.SEPARATE_EACH, "ch_alt", 0);
        assertTrue(filter.isDistinct(a, b), "смена объекта присоединения — признак отличия по §2.8");
    }

    @Test
    @DisplayName("M7-DIST-4: другое объединение ОКС — другой вариант")
    void differentGroupingIsDistinct() {
        NetworkPlan joint = VariantsFixtures.planWithTree("tie_j", "ch_main", "o1", 12.0, 80.0,
                new Coordinate(413000, 6174000), new Coordinate(413060, 6174050));
        NetworkPlan split = VariantPlanMerger.merge(Arrays.asList(
                VariantsFixtures.planWithTree("tie_s1", "ch_main", "o1", 12.0, 80.0,
                        new Coordinate(413000, 6174000), new Coordinate(413060, 6174050)),
                VariantsFixtures.planWithTree("tie_s2", "ch_main", "o2", 9.0, 60.0,
                        new Coordinate(413000, 6174000), new Coordinate(413040, 6173960))));
        assertTrue(filter.isDistinct(variant("v1", VariantStrategy.JOINT_ALL, joint),
                        variant("v2", VariantStrategy.SEPARATE_EACH, split)),
                "разное разбиение ОКС по деревьям — признак отличия");
    }

    @Test
    @DisplayName("M7-DIST-5: фильтр оставляет первый из совпадающих и пишет причину отклонения")
    void filterKeepsFirstAndExplains() {
        List<GeneratedVariant> candidates = Arrays.asList(
                route("v1", VariantStrategy.JOINT_ALL, "ch_main", 0),
                route("v2", VariantStrategy.SEPARATE_EACH, "ch_main", 5),
                route("v3", VariantStrategy.CLUSTERED, "ch_alt", 0));
        List<String> rejected = new ArrayList<>();
        List<GeneratedVariant> kept = filter.filter(candidates, rejected);

        assertEquals(2, kept.size(), "v2 — тот же вариант, что v1");
        assertEquals("v1", kept.get(0).getVariantId());
        assertEquals("v3", kept.get(1).getVariantId());
        assertEquals(1, rejected.size());
        assertTrue(rejected.get(0).contains("v2"), "причина отклонения должна называть вариант: " + rejected);
    }

    @Test
    @DisplayName("M7-DIST-6: объяснение отличий человекочитаемо — для режима инспектора и защиты")
    void differenceIsExplainable() {
        List<String> reasons = VariantDistinctness.explainDifference(
                route("v1", VariantStrategy.JOINT_ALL, "ch_main", 0),
                route("v2", VariantStrategy.SEPARATE_EACH, "ch_alt", 0));
        assertFalse(reasons.isEmpty());
        assertTrue(reasons.toString().contains("присоединения"), "ожидалось упоминание точек присоединения: " + reasons);
    }
}
