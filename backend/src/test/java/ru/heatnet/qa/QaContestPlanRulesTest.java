package ru.heatnet.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.heatnet.ContestDatasetPaths;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;
import ru.heatnet.network.NetworkPlan;
import ru.heatnet.network.NetworkPlanner;

/**
 * QA (роль 4): построенный план на конкурсном наборе обязан соблюдать правила приложения,
 * а не только «подключить 17 из 17».
 *
 * <p>{@code NetworkPlanner} уже собирает нарушения в {@link NetworkPlan#getDiagnostics()}
 * (механизм H-7), но ни один тест репозитория на них не смотрит: план возвращается с
 * предупреждением в лог и считается успешным. Этот тест превращает предупреждение в
 * проверяемое условие.</p>
 *
 * <p>Проверяются требования §2.1 обновлённого приложения:</p>
 * <ul>
 *   <li>«Новые участки не должны пересекаться между собой вне общего узла»;</li>
 *   <li>«Допускается произвольный угол поворота до 90° включительно».</li>
 * </ul>
 *
 * <p>Тест помечен {@code slow} — полный план на конкурсном наборе считается около 12 минут,
 * поэтому он выполняется профилем {@code contest-full}, а не в обычной сборке.</p>
 */
@SpringBootTest
@Tag("slow")
class QaContestPlanRulesTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private NetworkPlanner networkPlanner;

    @Test
    @EnabledIf("ru.heatnet.ContestDatasetPaths#isPresent")
    @DisplayName("QA-CONTEST-RULES: план на конкурсном наборе не нарушает §2.1 (пересечения вне узлов, поворот > 90°)")
    void contestPlanHasNoRuleViolations() throws Exception {
        IngestResult ingest;
        try (InputStream in = Files.newInputStream(ContestDatasetPaths.require())) {
            ingest = ingestService.ingest(in);
        }
        NetworkPlan plan = networkPlanner.plan(ingest);

        assertTrue(plan.getTrees().size() > 0, "план должен содержать хотя бы одно дерево");

        List<String> crossings = new ArrayList<>();
        List<String> turns = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (String d : plan.getDiagnostics()) {
            if (d.startsWith("пересечение вне общего узла")) {
                crossings.add(d);
            } else if (d.startsWith("поворот")) {
                turns.add(d);
            } else {
                other.add(d);
            }
        }

        assertEquals(0, crossings.size(),
                "§2.1: новые участки не должны пересекаться вне общего узла. Найдено "
                        + crossings.size() + ":\n  " + String.join("\n  ", crossings));
        assertEquals(0, turns.size(),
                "§2.1: допускается угол поворота до 90° включительно. Найдено "
                        + turns.size() + ":\n  " + String.join("\n  ", turns));
        assertEquals(0, other.size(),
                "прочие нарушения плана:\n  " + String.join("\n  ", other));
    }
}
