package ru.heatnet.variants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.OksConnectionPoint;

/** M7: генерация вариантов end-to-end на синтетической сети (M4 → M5 → M6 → фильтр → ранжирование). */
@SpringBootTest
class VariantGeneratorTest {

    @Autowired
    private VariantGenerator generator;

    @Autowired
    private ReferenceData reference;

    private static Set<String> connectedOks(GeneratedVariant v) {
        Set<String> ids = new HashSet<>();
        for (NewNetworkTree t : v.getPlan().getTrees()) {
            for (NewNode n : t.oksNodes()) {
                ids.add(n.getOksId());
            }
        }
        return ids;
    }

    @Test
    @DisplayName("M7-GEN-1: стратегия «раздельно» даёт по дереву на каждый ОКС")
    void separateStrategyGivesTreePerOks() {
        IngestResult ingest = VariantsFixtures.network();
        GeneratedVariant v = generator.generateSingle(ingest, VariantStrategy.SEPARATE_EACH);
        assertNotNull(v);
        int connected = connectedOks(v).size();
        assertEquals(connected, v.getPlan().getTrees().size(),
                "при раздельной схеме каждое дерево обслуживает ровно один ОКС");
        assertEquals(ingest.getOksConnectionPoints().size(),
                connected + v.getPlan().getUnconnectedOks().size(),
                "каждый ОКС либо подключён, либо в unconnected (п. 2.9 ТЗ)");
    }

    @Test
    @DisplayName("M7-GEN-2: ни один ОКС не теряется ни в одной стратегии")
    void noOksIsLost() {
        IngestResult ingest = VariantsFixtures.network();
        Set<String> all = new HashSet<>();
        for (OksConnectionPoint o : ingest.getOksConnectionPoints()) {
            all.add(o.getId());
        }
        for (VariantStrategy s : VariantStrategy.values()) {
            GeneratedVariant v = generator.generateSingle(ingest, s);
            Set<String> seen = new HashSet<>(connectedOks(v));
            for (ru.heatnet.cost.UnconnectedOks u : v.getPlan().getUnconnectedOks()) {
                seen.add(u.getOksId());
            }
            assertEquals(all, seen, "стратегия " + s.getCode() + " потеряла ОКС: " + v);
        }
    }

    @Test
    @DisplayName("M7-GEN-3: генерация даёт не более ranking.max_variants вариантов с rank 1..N")
    void generateRespectsMaxVariantsAndRanks() {
        VariantSet set = generator.generate(VariantsFixtures.network());
        assertFalse(set.isEmpty(), "на синтетической сети вариант должен строиться: " + set.getDiagnostics());
        assertTrue(set.getVariants().size() <= reference.getRules().getMaxVariants(),
                "приложение §6: не более трёх вариантов");
        for (int i = 0; i < set.getVariants().size(); i++) {
            assertEquals(i + 1, set.getVariants().get(i).getSummary().getRank(),
                    "rank должен идти 1..N без пропусков (§7.2)");
        }
    }

    @Test
    @DisplayName("M7-GEN-4: варианты отсортированы по возрастанию S (меньше — лучше)")
    void variantsAreSortedByScore() {
        List<GeneratedVariant> vs = generator.generate(VariantsFixtures.network()).getVariants();
        for (int i = 1; i < vs.size(); i++) {
            assertTrue(vs.get(i - 1).getSummary().getRawScore() <= vs.get(i).getSummary().getRawScore(),
                    "S должен не убывать: " + vs);
        }
    }

    @Test
    @DisplayName("M7-GEN-5: оставшиеся варианты попарно содержательно различны")
    void keptVariantsAreDistinct() {
        List<GeneratedVariant> vs = generator.generate(VariantsFixtures.network()).getVariants();
        VariantDistinctness d = new VariantDistinctness();
        for (int i = 0; i < vs.size(); i++) {
            for (int j = i + 1; j < vs.size(); j++) {
                assertTrue(d.isDistinct(vs.get(i), vs.get(j)),
                        "варианты " + vs.get(i).getVariantId() + " и " + vs.get(j).getVariantId()
                                + " не различаются содержательно");
            }
        }
    }

    @Test
    @DisplayName("M7-GEN-6: id вариантов уникальны, диагностика заполнена")
    void variantIdsAreUniqueAndDiagnosed() {
        VariantSet set = generator.generate(VariantsFixtures.network());
        Set<String> ids = new HashSet<>();
        for (GeneratedVariant v : set.getVariants()) {
            assertTrue(ids.add(v.getVariantId()), "variant_id не уникален: " + v.getVariantId());
        }
        assertFalse(set.getDiagnostics().isEmpty(), "должна быть диагностика по каждой стратегии");
    }

    @Test
    @DisplayName("M7-GEN-7: пустой список ОКС не роняет генератор")
    void emptyOksIsHandled() {
        IngestResult base = VariantsFixtures.network();
        IngestResult empty = new IngestResult(base.getReport(), base.getExistingNetwork(),
                Collections.<OksConnectionPoint>emptyList(),
                Collections.<String, org.locationtech.jts.geom.Geometry>emptyMap(),
                base.getAcceptedFeatures());
        VariantSet set = generator.generate(empty);
        assertTrue(set.isEmpty());
        assertFalse(set.getDiagnostics().isEmpty());
    }

    @Test
    @DisplayName("M7-GEN-8: подмножество стратегий работает и не падает")
    void subsetOfStrategiesWorks() {
        VariantSet set = generator.generate(VariantsFixtures.network(),
                Arrays.asList(VariantStrategy.JOINT_ALL));
        assertTrue(set.getVariants().size() <= 1);
    }
}
