package ru.heatnet.cli;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import ru.heatnet.export.VariantSplitter;
import ru.heatnet.jobs.CalculationPipeline;

/**
 * AUDIT-12 (Claude, 24.09). Расчёт из командной строки без веб-сервера — тот же конвейер, что у
 * {@code POST /api/jobs} (ingest → M7 → [M11] → M8), для воспроизводимой выгрузки на конкурсном наборе
 * (ТЗ разд. 7.2 п. 4). Раньше файлы {@code data/*.geojson} получались только побочным эффектом медленного
 * юнит-теста {@code ContestPipelineDiagTest}.
 *
 * <pre>
 * java -jar app.jar --heatnet.cli.input=dataset/dataset_updated.geojson \
 *                   --heatnet.cli.output=data/result.geojson [--heatnet.cli.depth=true] [--heatnet.cli.split-variants=true]
 * </pre>
 * При {@code split-variants} рядом пишутся файлы на каждый вариант ({@code result_vA.geojson} …) — для
 * просмотра на карте (в общем файле варианты накладываются друг на друга).
 */
@Component
@ConditionalOnProperty(prefix = "heatnet.cli", name = "input")
public class ContestCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ContestCliRunner.class);

    private final CalculationPipeline pipeline;

    @Value("${heatnet.cli.input}")
    private String input;
    @Value("${heatnet.cli.output:result.geojson}")
    private String output;
    @Value("${heatnet.cli.depth:false}")
    private boolean depth;
    @Value("${heatnet.cli.split-variants:false}")
    private boolean splitVariants;

    public ContestCliRunner(CalculationPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path in = Path.of(input);
        Path out = Path.of(output);
        if (out.toAbsolutePath().getParent() != null) {
            Files.createDirectories(out.toAbsolutePath().getParent());
        }
        long t0 = System.currentTimeMillis();
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 16)) {
            pipeline.run(in, os, depth, (stage, progress) -> log.info("CLI {} {}%", stage, progress));
        }
        log.info("CLI: {} → {} ({} байт, {} с)", in, out, Files.size(out), (System.currentTimeMillis() - t0) / 1000);
        if (splitVariants) {
            List<String> ids;
            try (InputStream is = Files.newInputStream(out)) {
                ids = VariantSplitter.variantIds(is);
            }
            String name = out.getFileName().toString();
            String base = name.endsWith(".geojson") ? name.substring(0, name.length() - ".geojson".length()) : name;
            for (String id : ids) {
                Path part = out.resolveSibling(base + "_" + id.replaceAll("[^A-Za-z0-9_.-]", "_") + ".geojson");
                try (InputStream is = Files.newInputStream(out);
                     OutputStream os = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16)) {
                    int n = VariantSplitter.writeVariant(is, os, id);
                    log.info("CLI: вариант {} → {} ({} объектов)", id, part, n);
                }
            }
        }
    }
}
