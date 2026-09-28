package ru.heatnet.jobs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.cost.VariantCost;
import ru.heatnet.cost.VariantCostCalculator;
import ru.heatnet.depth.DepthOutcome;
import ru.heatnet.depth.DepthTracer;
import ru.heatnet.export.ExportIds;
import ru.heatnet.export.ExportVariant;
import ru.heatnet.export.GeoJsonExporter;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;
import ru.heatnet.network.NetworkPlan;
import ru.heatnet.network.NetworkPlanner;
import ru.heatnet.variants.GeneratedVariant;
import ru.heatnet.variants.VariantGenerator;
import ru.heatnet.variants.VariantSet;

/**
 * M9. Конвейер одного расчёта: ingest → M7 (до трёх вариантов: M4 → M5/M6 на каждый) →
 * опционально M11 на каждый вариант → экспорт M8.
 *
 * <p>§6 приложения / п. 2.8 ТЗ: «сервис может вернуть до трёх содержательно отличающихся
 * вариантов». {@link NetworkPlanner#plan} внутри {@link VariantGenerator} держит общие счётчики
 * идентификаторов, поэтому сериализуем весь блок генерации вариантов одного job'а.</p>
 */
@Service
public class CalculationPipeline {

    private static final Logger log = LoggerFactory.getLogger(CalculationPipeline.class);

    private final IngestService ingestService;
    private final NetworkPlanner networkPlanner;
    private final VariantGenerator variantGenerator;
    private final ReferenceData reference;
    private final DepthTracer depthTracer;
    private final GeoJsonExporter exporter;
    private final Object planLock = new Object();

    public CalculationPipeline(IngestService ingestService, NetworkPlanner networkPlanner,
                               VariantGenerator variantGenerator, ReferenceData reference,
                               DepthTracer depthTracer, GeoJsonExporter exporter) {
        this.ingestService = ingestService;
        this.networkPlanner = networkPlanner;
        this.variantGenerator = variantGenerator;
        this.reference = reference;
        this.depthTracer = depthTracer;
        this.exporter = exporter;
    }

    public Map<String, Map<String, Object>> run(InputStream geoJson, OutputStream result, boolean enableDepth,
                                                JobProgress progress) throws IOException {
        progress.update("INGEST", 10);
        return run(ingestService.ingest(geoJson), result, enableDepth, progress);
    }

    /** Расчёт по сохранённому файлу: большие файлы читаются двумя потоковыми проходами (ТЗ 3.2). */
    public Map<String, Map<String, Object>> run(java.nio.file.Path geoJson, OutputStream result, boolean enableDepth,
                                                JobProgress progress) throws IOException {
        progress.update("INGEST", 10);
        return run(ingestService.ingest(geoJson), result, enableDepth, progress);
    }

    private Map<String, Map<String, Object>> run(IngestResult ingest, OutputStream result, boolean enableDepth,
                                                 JobProgress progress) throws IOException {
        progress.update("PLAN", 25);
        List<GeneratedVariant> variants;
        synchronized (planLock) {
            VariantSet variantSet = variantGenerator.generate(ingest);
            variants = variantSet.getVariants();
            for (String diag : variantSet.getDiagnostics()) {
                log.info("M7 {}", diag);
            }
        }
        if (variants.isEmpty()) {
            // М7 не построил ни одного варианта (например, во входных данных нет ОКС вовсе) —
            // §7.1 всё равно требует ровно одну variant_summary на вариант в выходном файле;
            // отдаём вырожденный вариант с нулевой сетью, чтобы выход не остался без summary.
            variants = Collections.singletonList(degenerateVariant(ingest));
        }
        progress.update("COST", 65);
        List<ExportVariant> exportVariants = new ArrayList<>();
        int step = variants.size();
        int i = 0;
        for (GeneratedVariant variant : variants) {
            NetworkPlan plan = variant.getPlan();
            VariantCost cost = variant.getCost();
            EngineeringResult engineering = cost.getEngineering();
            DepthOutcome depth = DepthOutcome.unchanged();
            if (enableDepth) {
                progress.update("DEPTH", 70 + (20 * ++i) / Math.max(1, step));
                depth = depthTracer.trace(plan, engineering, cost, depthTracer.fromIngest(ingest));
            }
            exportVariants.add(new ExportVariant(plan, engineering, cost, depth));
        }
        progress.update("EXPORT", 92);
        Map<String, Map<String, Object>> explanations = exporter.write(result, exportVariants,
                ExportIds.fromIngest(ingest), enableDepth, reference.getDepth().getNormalDepthM());
        GeneratedVariant best = variants.get(0);
        log.info("Расчёт завершён: вариантов {}, лучший {} — деревьев {}, неподключено {}, S={}",
                variants.size(), best.getVariantId(), best.getPlan().getTrees().size(),
                best.getPlan().getUnconnectedOks().size(), best.getSummary().getScore());
        progress.update("DONE", 100);
        return explanations;
    }

    /**
     * Вырожденный вариант, когда M7 не построил ни одного варианта: нет ОКС или все стратегии упали.
     *
     * <p>AUDIT-24.09 (Claude): раньше сюда передавался пустой список неподключённых — при падении всех
     * стратегий выход утверждал «всё подключено, C = 0, S = 0» при непустом наборе ОКС. По §2.9 ТЗ и
     * §2.5/§6 приложения каждый ОКС без найденного маршрута должен попасть в unconnected_oks_ids со штрафом.</p>
     */
    private GeneratedVariant degenerateVariant(IngestResult ingest) {
        List<ru.heatnet.cost.UnconnectedOks> unconnected = new ArrayList<>();
        for (ru.heatnet.ingest.OksConnectionPoint oks : ingest.getOksConnectionPoints()) {
            unconnected.add(new ru.heatnet.cost.UnconnectedOks(oks.getId(), oks.getFlowTph()));
        }
        NetworkPlan empty = new NetworkPlan(Collections.emptyList(), Collections.emptyList(),
                unconnected, false);
        EngineeringResult engineering = new EngineeringCalculator(reference)
                .calculate(empty.getTrees(), ingest.getExistingNetwork());
        VariantCost cost = new VariantCostCalculator(reference)
                .calculate("1", engineering, empty.getUnconnectedOks());
        cost = cost.withSummary(cost.getSummary().withRank(1));
        return new GeneratedVariant("1", ru.heatnet.variants.VariantStrategy.JOINT_ALL, empty, cost);
    }
}
