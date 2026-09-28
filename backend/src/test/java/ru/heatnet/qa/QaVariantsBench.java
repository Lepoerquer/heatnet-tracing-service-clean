package ru.heatnet.qa;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import ru.heatnet.ContestDatasetPaths;
import ru.heatnet.HeatnetApplication;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.cost.UnconnectedOks;
import ru.heatnet.cost.VariantSummary;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;
import ru.heatnet.variants.GeneratedVariant;
import ru.heatnet.variants.VariantDistinctness;
import ru.heatnet.variants.VariantGenerator;
import ru.heatnet.variants.VariantSet;
import ru.heatnet.variants.VariantStrategy;

/**
 * QA (роль 4): прогон M7 на конкурсном наборе с замером времени каждой стратегии
 * и проверкой инвариантов (ни один ОКС не потерян, варианты различны, rank 1..N).
 *
 * <p>Запуск: {@code java -cp ... ru.heatnet.qa.QaVariantsBench [strategyCode...]}</p>
 */
public final class QaVariantsBench {

    private QaVariantsBench() {
    }

    public static void main(String[] args) throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HeatnetApplication.class)
                .web(WebApplicationType.NONE).run("--spring.profiles.active=local");
        try {
            IngestService ingestService = ctx.getBean(IngestService.class);
            VariantGenerator generator = ctx.getBean(VariantGenerator.class);

            IngestResult ingest;
            try (InputStream in = Files.newInputStream(ContestDatasetPaths.require())) {
                ingest = ingestService.ingest(in);
            }
            int totalOks = ingest.getOksConnectionPoints().size();
            System.out.println("ingest: ОКС=" + totalOks
                    + " ограничений=" + ingest.getRestrictionsWgs84().size()
                    + " ошибок=" + ingest.getReport().errorCount());

            if (args.length > 0) {
                for (String code : args) {
                    VariantStrategy s = byCode(code);
                    long t = System.nanoTime();
                    GeneratedVariant v = generator.generateSingle(ingest, s);
                    report(s, v, totalOks, (System.nanoTime() - t) / 1e9);
                }
                return;
            }

            long t0 = System.nanoTime();
            VariantSet set = generator.generate(ingest);
            double sec = (System.nanoTime() - t0) / 1e9;

            System.out.println();
            System.out.println("=== ДИАГНОСТИКА ГЕНЕРАТОРА ===");
            for (String d : set.getDiagnostics()) {
                System.out.println("  " + d);
            }
            System.out.printf(Locale.ROOT, "%nВСЕГО %.1f с, вариантов в выдаче: %d%n", sec, set.getVariants().size());

            List<GeneratedVariant> vs = set.getVariants();
            for (GeneratedVariant v : vs) {
                report(v.getStrategy(), v, totalOks, Double.NaN);
            }

            System.out.println();
            System.out.println("=== ПОПАРНЫЕ ОТЛИЧИЯ ===");
            VariantDistinctness d = new VariantDistinctness();
            for (int i = 0; i < vs.size(); i++) {
                for (int j = i + 1; j < vs.size(); j++) {
                    System.out.println("  " + vs.get(i).getVariantId() + " vs " + vs.get(j).getVariantId()
                            + ": различны=" + d.isDistinct(vs.get(i), vs.get(j)));
                    for (String r : VariantDistinctness.explainDifference(vs.get(i), vs.get(j))) {
                        System.out.println("      " + r);
                    }
                }
            }

            System.out.println();
            System.out.println("=== ИНВАРИАНТЫ ===");
            boolean ok = true;
            for (int i = 0; i < vs.size(); i++) {
                int rank = vs.get(i).getSummary().getRank();
                if (rank != i + 1) {
                    System.out.println("  НАРУШЕНИЕ: rank=" + rank + " на позиции " + (i + 1));
                    ok = false;
                }
            }
            for (GeneratedVariant v : vs) {
                int seen = connected(v) + v.getPlan().getUnconnectedOks().size();
                if (seen != totalOks) {
                    System.out.println("  НАРУШЕНИЕ: " + v.getVariantId() + " учитывает " + seen
                            + " ОКС из " + totalOks);
                    ok = false;
                }
            }
            System.out.println(ok ? "  все инварианты выполнены" : "  ЕСТЬ НАРУШЕНИЯ");
        } finally {
            ctx.close();
        }
    }

    private static void report(VariantStrategy s, GeneratedVariant v, int totalOks, double sec) {
        VariantSummary sum = v.getSummary();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "%n-- %s (%s) rank=%d%n   деревьев=%d подключено=%d неподключено=%d%n"
                        + "   C=%d  L=%.1f  S=%s  врезок в существующие камеры=%d%n"
                        + "   участки=%d камеры=%d врезки=%d штраф=%d",
                v.getVariantId(), s.getDescription(), sum.getRank(),
                v.getPlan().getTrees().size(), connected(v), v.getPlan().getUnconnectedOks().size(),
                sum.getCalculatedCost(), sum.getNewNetworkLength(), sum.getScore(),
                sum.getExistingChamberTieInCount(),
                sum.getSegmentCost(), sum.getChamberConstructionCost(), sum.getTieInCost(),
                sum.getUnconnectedPenalty()));
        if (!Double.isNaN(sec)) {
            sb.append(String.format(Locale.ROOT, "%n   время=%.1f с", sec));
        }
        if (connected(v) != totalOks) {
            sb.append("%n   неподключённые: ");
            for (UnconnectedOks u : v.getPlan().getUnconnectedOks()) {
                sb.append(u.getOksId()).append(' ');
            }
        }
        System.out.println(sb);
        System.out.flush();
    }

    private static int connected(GeneratedVariant v) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (NewNetworkTree t : v.getPlan().getTrees()) {
            for (NewNode n : t.oksNodes()) {
                ids.add(n.getOksId());
            }
        }
        return ids.size();
    }

    private static VariantStrategy byCode(String code) {
        for (VariantStrategy s : VariantStrategy.values()) {
            if (s.getCode().equalsIgnoreCase(code) || s.name().equalsIgnoreCase(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("неизвестная стратегия: " + code);
    }
}
