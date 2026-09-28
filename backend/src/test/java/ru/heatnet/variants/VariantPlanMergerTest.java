package ru.heatnet.variants;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.model.ExistingChamber;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingSegment;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.cost.UnconnectedOks;
import ru.heatnet.cost.VariantCostCalculator;
import ru.heatnet.network.NetworkPlan;

/**
 * M7: слияние планов нескольких прогонов планировщика.
 *
 * <p>Служебные id узлов и участков нумеруются счётчиками, которые сбрасываются в начале каждого
 * вызова {@code NetworkPlanner.plan(...)}. Тесты фиксируют, что слияние переживает такие
 * совпадения и не теряет данные.</p>
 */
class VariantPlanMergerTest {

    private NetworkPlan planA() {
        return VariantsFixtures.planWithTree("tie_c_ch_main_a", "ch_main", "o1", 12.0, 80.0,
                new Coordinate(413000, 6174000), new Coordinate(413060, 6174050));
    }

    private NetworkPlan planB() {
        return VariantsFixtures.planWithTree("tie_c_ch_main_b", "ch_main", "o2", 9.0, 60.0,
                new Coordinate(413000, 6174000), new Coordinate(413040, 6173960));
    }

    @Test
    @DisplayName("M7-MERGE-1: два прогона с одинаковыми служебными id сливаются без потерь")
    void mergeRenamesCollidingIds() {
        NetworkPlan merged = VariantPlanMerger.merge(Arrays.asList(planA(), planB()));

        assertEquals(2, merged.getTrees().size(), "оба дерева должны попасть в объединённый план");
        assertEquals(2, merged.getLayouts().size(), "раскладок столько же, сколько деревьев");

        Set<String> segIds = new HashSet<>();
        Set<String> nodeIds = new HashSet<>();
        for (NewNetworkTree t : merged.getTrees()) {
            for (String id : t.getSegments().keySet()) {
                assertTrue(segIds.add(id), "id участка не уникален после слияния: " + id);
            }
            for (String id : t.getNodes().keySet()) {
                assertTrue(nodeIds.add(id), "id узла не уникален после слияния: " + id);
            }
        }
    }

    @Test
    @DisplayName("M7-MERGE-2: id входных объектов (oksId, existing_object_id) слияние не меняет")
    void mergeKeepsInputIdentifiers() {
        NetworkPlan merged = VariantPlanMerger.merge(Arrays.asList(planA(), planB()));

        Set<String> oksIds = new HashSet<>();
        Set<String> tieTargets = new HashSet<>();
        for (NewNetworkTree t : merged.getTrees()) {
            for (NewNode n : t.oksNodes()) {
                oksIds.add(n.getOksId());
            }
            tieTargets.add(t.getTieIn().getExistingObjectId());
        }
        assertEquals(new HashSet<>(Arrays.asList("o1", "o2")), oksIds,
                "oksId — идентификатор входного объекта, он не должен переименовываться");
        assertEquals(Collections.singleton("ch_main"), tieTargets,
                "existing_object_id врезки должен остаться прежним");
    }

    @Test
    @DisplayName("M7-MERGE-3: после слияния смета M6 считается (id уникальны для VariantCostCalculator)")
    void mergedPlanIsCostable() {
        NetworkPlan merged = VariantPlanMerger.merge(Arrays.asList(planA(), planB()));
        ExistingNetwork existing = new ExistingNetwork(
                Collections.singletonList(new ExistingSegment("net_main", 300, null, "src", 120.0)),
                Collections.singletonList(new ExistingChamber("ch_main", 300, "net_main")),
                Collections.singletonList("src"));

        assertDoesNotThrow(() -> {
            EngineeringCalculator eng = new EngineeringCalculator(TestReference.get());
            new VariantCostCalculator(TestReference.get())
                    .calculate("m", eng.calculate(merged.getTrees(), existing),
                            Collections.<UnconnectedOks>emptyList());
        }, "слитый план должен считаться сметой без конфликта идентификаторов");
    }

    @Test
    @DisplayName("M7-MERGE-4: геометрия участков переносится в переименованную раскладку")
    void mergeKeepsGeometry() {
        NetworkPlan merged = VariantPlanMerger.merge(Arrays.asList(planA(), planB()));
        int withGeometry = 0;
        for (int i = 0; i < merged.getTrees().size(); i++) {
            NewNetworkTree tree = merged.getTrees().get(i);
            for (NewSegment seg : tree.getSegments().values()) {
                assertNotNull(merged.getLayouts().get(i).segmentLine(seg.getId()),
                        "потеряна геометрия участка " + seg.getId());
                withGeometry++;
            }
        }
        assertEquals(4, withGeometry, "в сумме 2 дерева по 2 участка");
    }

    @Test
    @DisplayName("M7-MERGE-5: неподключённые ОКС и диагностика собираются со всех частей")
    void mergeCollectsUnconnected() {
        NetworkPlan empty = new NetworkPlan(Collections.<NewNetworkTree>emptyList(),
                Collections.singletonList(new UnconnectedOks("o9", 5.0)), false);
        NetworkPlan merged = VariantPlanMerger.merge(Arrays.asList(planA(), empty));
        assertEquals(1, merged.getTrees().size());
        assertEquals(1, merged.getUnconnectedOks().size());
        assertEquals("o9", merged.getUnconnectedOks().get(0).getOksId());
    }

    @Test
    @DisplayName("M7-MERGE-6: слияние одной части ничего не переименовывает")
    void singlePartUnchanged() {
        NetworkPlan single = planA();
        NetworkPlan merged = VariantPlanMerger.merge(Collections.singletonList(single));
        List<String> before = new ArrayList<>(single.getTrees().get(0).getSegments().keySet());
        List<String> after = new ArrayList<>(merged.getTrees().get(0).getSegments().keySet());
        assertEquals(before, after, "единственная часть не нуждается в префиксах");
        assertFalse(after.isEmpty());
    }
}
