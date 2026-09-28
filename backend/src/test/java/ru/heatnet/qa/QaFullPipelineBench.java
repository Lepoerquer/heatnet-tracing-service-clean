package ru.heatnet.qa;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import ru.heatnet.HeatnetApplication;
import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.TreeCalcResult;
import ru.heatnet.calc.model.CalcDiagnostic;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.NodeKind;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.cost.VariantCost;
import ru.heatnet.cost.VariantCostCalculator;
import ru.heatnet.cost.VariantSummary;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.network.NetworkPlan;
import ru.heatnet.network.NetworkPlanner;
import ru.heatnet.network.NetworkTreeLayout;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.SpatialConstraintEngine;

/**
 * QA (роль 4): E2E-бенчмарк и ПОСТ-ВАЛИДАЦИЯ результата M0..M6 на конкурсном наборе.
 * Запуск вне Maven: java -cp ... ru.heatnet.qa.QaFullPipelineBench dataset.geojson [maxOks]
 * Печатает метрики времени и нарушения правил ТЗ в готовом плане.
 */
public final class QaFullPipelineBench {

    private QaFullPipelineBench() {
    }

    public static void main(String[] args) throws Exception {
        Path dataset = args.length > 0 ? Paths.get(args[0]) : ru.heatnet.ContestDatasetPaths.require();
        int maxOks = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;
        long t0 = System.nanoTime();
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HeatnetApplication.class)
                .web(WebApplicationType.NONE).run("--spring.profiles.active=local");
        log("context up", t0);

        IngestService ingestService = ctx.getBean(IngestService.class);
        NetworkPlanner planner = ctx.getBean(NetworkPlanner.class);
        ReferenceData ref = ctx.getBean(ReferenceData.class);
        RestrictionEngineFactory engineFactory = ctx.getBean(RestrictionEngineFactory.class);

        long ti = System.nanoTime();
        IngestResult ingest;
        try (InputStream in = Files.newInputStream(dataset)) {
            ingest = ingestService.ingest(in);
        }
        log("ingest done: oks=" + ingest.getOksConnectionPoints().size()
                + " restrictions=" + ingest.getRestrictionsWgs84().size()
                + " errors=" + ingest.getReport().errorCount(), ti);

        List<OksConnectionPoint> oks = new ArrayList<>(ingest.getOksConnectionPoints());
        if (oks.size() > maxOks) {
            oks = new ArrayList<>(oks.subList(0, maxOks));
        }
        final int total = oks.size();
        Thread ticker = new Thread(() -> {
            long s = System.nanoTime();
            try {
                while (true) {
                    Thread.sleep(60_000);
                    System.out.println("[tick] planning still running, " + ((System.nanoTime() - s) / 1_000_000_000L) + " s");
                    System.out.flush();
                }
            } catch (InterruptedException ignored) {
                // stop
            }
        });
        ticker.setDaemon(true);
        ticker.start();

        long tp = System.nanoTime();
        NetworkPlan plan = planner.plan(ingest, ingest.getExistingNetwork(),
                ru.heatnet.network.ExistingNetworkGeometry.fromIngest(ingest.getAcceptedFeatures(),
                        ingest.getExistingNetwork(), ctx.getBean(ru.heatnet.geo.ProjectionService.class)),
                oks);
        double planSec = (System.nanoTime() - tp) / 1e9;
        ticker.interrupt();
        System.out.printf(Locale.ROOT, "PLAN_SECONDS=%.1f for %d OKS%n", planSec, total);

        postValidate(plan, ingest, ref, engineFactory, oks);
        ctx.close();
    }

    private static void postValidate(NetworkPlan plan, IngestResult ingest, ReferenceData ref,
                                     RestrictionEngineFactory engineFactory, List<OksConnectionPoint> requested) {
        System.out.println("== POST-VALIDATION ==");
        System.out.println("joint=" + plan.isJointConnection() + " trees=" + plan.getTrees().size()
                + " unconnected=" + plan.getUnconnectedOks().size());

        // 1. Каждый запрошенный ОКС либо подключён, либо в unconnected (иначе потерян -> нарушение п.2.9)
        java.util.Set<String> connected = new java.util.HashSet<>();
        for (NewNetworkTree t : plan.getTrees()) {
            for (NewNode n : t.oksNodes()) {
                connected.add(n.getOksId());
            }
        }
        java.util.Set<String> unconnected = new java.util.HashSet<>();
        for (ru.heatnet.cost.UnconnectedOks u : plan.getUnconnectedOks()) {
            unconnected.add(u.getOksId());
        }
        int lost = 0;
        for (OksConnectionPoint o : requested) {
            if (!connected.contains(o.getId()) && !unconnected.contains(o.getId())) {
                lost++;
                System.out.println("VIOLATION lost-oks: " + o.getId() + " ни подключён, ни в unconnected");
            }
        }
        System.out.println("connected=" + connected.size() + " unconnectedListed=" + unconnected.size() + " LOST=" + lost);

        // 2. Лимит камеры <= 4 участков (включая входящий) для новых камер
        int chamberViolations = 0;
        for (NewNetworkTree t : plan.getTrees()) {
            for (NewNode n : t.getNodes().values()) {
                if (n.getKind() == NodeKind.NEW_CHAMBER) {
                    int deg = t.childrenOf(n.getId()).size() + (t.incomingOf(n.getId()) != null ? 1 : 0);
                    if (deg > ref.getRules().getMaxSegmentsPerChamber()) {
                        chamberViolations++;
                        System.out.println("VIOLATION chamber-degree: " + n.getId() + " deg=" + deg);
                    }
                }
            }
        }
        System.out.println("chamberDegreeViolations=" + chamberViolations);

        // 3. Инженерный расчёт и стоимость
        EngineeringCalculator eng = new EngineeringCalculator(ref);
        EngineeringResult er = eng.calculate(plan.getTrees(), ingest.getExistingNetwork());
        int errs = 0;
        for (CalcDiagnostic d : er.getDiagnostics()) {
            if (d.getSeverity() == CalcDiagnostic.Severity.ERROR) {
                errs++;
                System.out.println("DIAG-ERROR " + d.getCode() + " " + d.getMessage());
            }
        }
        VariantCost vc = new VariantCostCalculator(ref).calculate("qa", er, plan.getUnconnectedOks());
        VariantSummary s = vc.getSummary();
        System.out.printf(Locale.ROOT, "SUMMARY construction=%d chambers=%d tieIn=%d recon=%d chRecon=%d penalty=%d C=%d Lnew=%.1f Lrecon=%.1f S=%.3f%n",
                s.getConstructionCost(), s.getChamberConstructionCost(), s.getTieInCost(), s.getReconstructionCost(),
                s.getChamberReconstructionCost(), s.getUnconnectedPenalty(), s.getCalculatedCost(),
                s.getNewNetworkLength(), s.getReconstructionLength(), s.getScore());
        System.out.println("engineeringErrors=" + errs);

        // 4. Геометрическая проверка: каждый сегмент не блокируется движком M2 при СВОЁМ DN
        Map<Integer, SpatialConstraintEngine> engines = new HashMap<>();
        int blocked = 0;
        int segCount = 0;
        for (int i = 0; i < plan.getTrees().size(); i++) {
            NewNetworkTree tree = plan.getTrees().get(i);
            if (i >= plan.getLayouts().size()) {
                continue;
            }
            NetworkTreeLayout layout = plan.getLayouts().get(i);
            TreeCalcResult tr = er.getTrees().get(i);
            for (NewSegment seg : tree.getSegments().values()) {
                LineString line = layout.segmentLine(seg.getId());
                if (line == null) {
                    System.out.println("VIOLATION no-geometry for segment " + seg.getId());
                    continue;
                }
                segCount++;
                int dn = tr.getDiameters().get(seg.getId());
                SpatialConstraintEngine e = engines.computeIfAbsent(dn, d -> engineFactory.create(ingest, d));
                for (int k = 0; k < line.getNumPoints() - 1; k++) {
                    LineString piece = line.getFactory().createLineString(
                            new org.locationtech.jts.geom.Coordinate[] {line.getCoordinateN(k), line.getCoordinateN(k + 1)});
                    if (e.isSegmentBlocked(piece, dn)) {
                        blocked++;
                        System.out.println("VIOLATION blocked-segment: " + seg.getId() + " DN" + dn + " piece " + k);
                        break;
                    }
                }
            }
        }
        System.out.println("segments=" + segCount + " blockedAtOwnDn=" + blocked);

        // 5. Пересечения новых участков между собой вне общего узла
        List<LineString> all = new ArrayList<>();
        List<String> owner = new ArrayList<>();
        for (int i = 0; i < plan.getLayouts().size(); i++) {
            for (Map.Entry<String, LineString> en : plan.getLayouts().get(i).getSegmentGeometries().entrySet()) {
                all.add(en.getValue());
                owner.add(i + ":" + en.getKey());
            }
        }
        int crossings = 0;
        for (int a = 0; a < all.size(); a++) {
            for (int b = a + 1; b < all.size(); b++) {
                Geometry inter = all.get(a).intersection(all.get(b));
                if (inter.isEmpty()) {
                    continue;
                }
                boolean sharedEndpoint = inter.getDimension() == 0 && inter.getNumGeometries() == 1
                        && isEndpoint(all.get(a), inter) && isEndpoint(all.get(b), inter);
                if (!sharedEndpoint) {
                    crossings++;
                    if (crossings <= 15) {
                        System.out.println("VIOLATION overlap/crossing: " + owner.get(a) + " x " + owner.get(b)
                                + " -> " + inter.getGeometryType() + " len=" + inter.getLength());
                    }
                }
            }
        }
        System.out.println("segmentCrossingsOutsideNodes=" + crossings);
    }

    private static boolean isEndpoint(LineString line, Geometry point) {
        org.locationtech.jts.geom.Coordinate c = point.getCoordinate();
        return line.getCoordinateN(0).distance(c) < 0.05
                || line.getCoordinateN(line.getNumPoints() - 1).distance(c) < 0.05;
    }

    private static void log(String msg, long startNanos) {
        System.out.printf(Locale.ROOT, "[%.1fs] %s%n", (System.nanoTime() - startNanos) / 1e9, msg);
        System.out.flush();
    }
}
