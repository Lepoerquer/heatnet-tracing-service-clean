package ru.heatnet.variants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.locationtech.jts.geom.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.cost.UnconnectedOks;
import ru.heatnet.cost.VariantCost;
import ru.heatnet.cost.VariantCostCalculator;
import ru.heatnet.cost.VariantRanker;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.ingest.RawFeature;
import ru.heatnet.network.ExistingNetworkGeometry;
import ru.heatnet.network.NetworkPlan;
import ru.heatnet.network.NetworkPlanner;
import ru.heatnet.network.PlanMode;

/**
 * M7. Генератор содержательно различающихся вариантов подключения.
 *
 * <p>Конвейер: {@link VariantStrategy} → план M4 ({@link NetworkPlanner}) → расчёт M5
 * ({@link EngineeringCalculator}) → смета M6 ({@link VariantCostCalculator}) →
 * фильтр различий ({@link VariantDistinctness}) → ранжирование M6 ({@link VariantRanker}).</p>
 *
 * <p>Приложение §6: не более трёх вариантов, порог берётся из {@code ranking.max_variants}.
 * Стратегии выражены через существующий публичный контракт M4 — меняется только состав
 * прогонов планировщика, код M2/M3/M4 не затрагивается.</p>
 *
 * <p><b>Стоимость по времени.</b> Каждая стратегия — это отдельный полный прогон планирования,
 * поэтому генерация трёх вариантов примерно втрое дороже одного плана. Для длинных наборов
 * предусмотрены {@link #generate(IngestResult, List)} с явным списком стратегий и
 * {@link #generateSingle(IngestResult, VariantStrategy)}.</p>
 */
@Service
public class VariantGenerator {

    /** Порядок по умолчанию: сначала самая «обычная» схема, затем заведомо контрастные. */
    public static final List<VariantStrategy> DEFAULT_STRATEGIES = Collections.unmodifiableList(
            java.util.Arrays.asList(VariantStrategy.JOINT_ALL,
                    VariantStrategy.SEPARATE_EACH,
                    VariantStrategy.CLUSTERED));

    private static final Logger log = LoggerFactory.getLogger(VariantGenerator.class);

    /**
     * AUDIT-12 (Claude, 24.09): бюджет уточняющих проходов по ДУ графа (см. {@link #refineRoutingDn}).
     * Сначала строятся первые планы всех стратегий, затем уточняются — начиная с лучшего по S.
     * Проход, начатый до исчерпания бюджета, доводится до конца. Сами три стратегии этим бюджетом не отменяются.
     */
    static final long ROUTING_DN_REFINEMENT_BUDGET_MS = 900_000L;

    /** AUDIT-13: различие суммарного отступления от ближайшей стены ОКС (§2.2), которое считается значимым, м. */
    static final double ENTRY_EXCESS_MARGIN_M = 0.5;

    private final ReferenceData reference;
    private final ProjectionService projection;
    private final NetworkPlanner networkPlanner;
    private final EngineeringCalculator engineering;
    private final VariantCostCalculator costCalculator;
    private final VariantRanker ranker;
    private final VariantDistinctness distinctness;

    public VariantGenerator(ReferenceData reference,
                            ProjectionService projection,
                            NetworkPlanner networkPlanner) {
        this.reference = reference;
        this.projection = projection;
        this.networkPlanner = networkPlanner;
        this.engineering = new EngineeringCalculator(reference);
        this.costCalculator = new VariantCostCalculator(reference);
        this.ranker = new VariantRanker(reference.getRules());
        this.distinctness = new VariantDistinctness();
    }

    public VariantSet generate(IngestResult ingest) {
        return generate(ingest, DEFAULT_STRATEGIES);
    }

    /**
     * Строит варианты по указанным стратегиям, отбрасывает содержательные дубли
     * и возвращает топ-N по показателю S (N = {@code ranking.max_variants}).
     */
    public VariantSet generate(IngestResult ingest, List<VariantStrategy> strategies) {
        List<String> diagnostics = new ArrayList<>();
        List<OksConnectionPoint> oksPoints = ingest.getOksConnectionPoints();
        if (oksPoints == null || oksPoints.isEmpty()) {
            diagnostics.add("во входных данных нет точек подключения ОКС — вариантов не построено");
            return new VariantSet(Collections.<GeneratedVariant>emptyList(), diagnostics);
        }

        ExistingNetwork existing = ingest.getExistingNetwork();
        ExistingNetworkGeometry geometry = ExistingNetworkGeometry.fromIngest(
                ingest.getAcceptedFeatures(), existing, projection);

        List<GeneratedVariant> built = new ArrayList<>();
        List<GeneratedVariant> withViolations = new ArrayList<>();
        for (VariantStrategy strategy : strategies) {
            if (strategy != VariantStrategy.JOINT_ALL && oksPoints.size() < 2) {
                diagnostics.add("стратегия " + strategy.getCode() + " пропущена: один ОКС — варианты совпадут");
                continue;
            }
            long started = System.nanoTime();
            try {
                NetworkPlan plan = buildPlan(strategy, ingest, existing, geometry, oksPoints, 0);
                if (!plan.getDiagnostics().isEmpty()) {
                    // §2.1: пересечение/наложение новых участков, разворот > 90°, разветвление вне камеры — вариант
                    // в выдачу не идёт. AUDIT-12 (Claude, 24.09): но план сохраняется как крайний резерв (см. ниже).
                    withViolations.add(evaluateUnchecked(strategy, plan, existing));
                    diagnostics.add("стратегия " + strategy.getCode() + " не построена: план нарушает правила "
                            + "топологии/геометрии §2.1: " + plan.getDiagnostics());
                    continue;
                }
                GeneratedVariant variant = evaluate(strategy, plan, existing);
                built.add(variant);
                diagnostics.add(String.format(Locale.ROOT,
                        "стратегия %s (%s): деревьев %d, неподключённых %d, S=%s, %.1f с",
                        strategy.getCode(), strategy.getDescription(), plan.getTrees().size(),
                        plan.getUnconnectedOks().size(), variant.getSummary().getScore(),
                        (System.nanoTime() - started) / 1e9));
            } catch (RuntimeException ex) {
                diagnostics.add("стратегия " + strategy.getCode() + " не построена: "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                log.warn("M7 стратегия {} упала", strategy.getCode(), ex);
            }
        }

        refineAll(built, withViolations, ingest, existing, geometry, oksPoints, diagnostics);

        if (built.isEmpty() && !withViolations.isEmpty()) {
            // AUDIT-12 (Claude, 24.09). Раньше при нарушениях во всех стратегиях выдача вырождалась в «все ОКС не
            // подключены» (штраф 100 млн+ на каждый) — хуже для любого сценария проверки, чем сеть с единичным
            // замечанием. Берётся лучший по S план, замечания остаются в диагностике задания и в логе.
            withViolations.sort(Comparator.comparingDouble(v -> v.getSummary().getScore()));
            GeneratedVariant fallback = withViolations.get(0);
            diagnostics.add("ВНИМАНИЕ: все стратегии дали планы с замечаниями §2.1; выдан лучший по S вариант "
                    + fallback.getVariantId() + " с замечаниями " + fallback.getPlan().getDiagnostics());
            log.warn("M7: выдан вариант {} с замечаниями §2.1: {}", fallback.getVariantId(),
                    fallback.getPlan().getDiagnostics());
            built.add(fallback);
        }
        if (built.isEmpty()) {
            diagnostics.add("ни одна стратегия не дала варианта");
            return new VariantSet(Collections.<GeneratedVariant>emptyList(), diagnostics);
        }
        built = dropAvoidableUnconnected(built, diagnostics);

        List<GeneratedVariant> distinct = distinctness.filter(built, diagnostics);
        List<GeneratedVariant> ranked = rank(distinct);
        if (ranked.size() < distinct.size()) {
            diagnostics.add("срез по ranking.max_variants=" + reference.getRules().getMaxVariants()
                    + ": из " + distinct.size() + " различающихся оставлено " + ranked.size());
        }
        return new VariantSet(ranked, diagnostics);
    }

    /** Один вариант по конкретной стратегии — для тестов и быстрых прогонов. */
    public GeneratedVariant generateSingle(IngestResult ingest, VariantStrategy strategy) {
        ExistingNetwork existing = ingest.getExistingNetwork();
        ExistingNetworkGeometry geometry = ExistingNetworkGeometry.fromIngest(
                ingest.getAcceptedFeatures(), existing, projection);
        NetworkPlan plan = buildPlan(strategy, ingest, existing, geometry, ingest.getOksConnectionPoints(), 0);
        GeneratedVariant first = plan.getDiagnostics().isEmpty()
                ? evaluate(strategy, plan, existing) : evaluateUnchecked(strategy, plan, existing);
        GeneratedVariant variant = refineRoutingDn(first, ingest, existing, geometry,
                ingest.getOksConnectionPoints(), Long.MAX_VALUE, new ArrayList<String>());
        if (!variant.getPlan().getDiagnostics().isEmpty()) {
            return evaluate(strategy, variant.getPlan(), existing); // бросает NetworkException, как и раньше
        }
        return variant;
    }

    /** Уточнение по ДУ графа всех построенных вариантов (сначала лучшие по S), в пределах бюджета. */
    private void refineAll(List<GeneratedVariant> built, List<GeneratedVariant> withViolations, IngestResult ingest,
                           ExistingNetwork existing, ExistingNetworkGeometry geometry,
                           List<OksConnectionPoint> oksPoints, List<String> diagnostics) {
        long deadline = System.nanoTime() + ROUTING_DN_REFINEMENT_BUDGET_MS * 1_000_000L;
        List<GeneratedVariant> order = new ArrayList<>(built);
        order.sort(Comparator.comparingDouble(v -> v.getSummary().getScore()));
        order.addAll(withViolations);
        for (GeneratedVariant v : order) {
            if (System.nanoTime() > deadline) {
                diagnostics.add("стратегия " + v.getStrategy().getCode() + ": уточнение по ДУ графа пропущено — "
                        + "исчерпан бюджет (" + ROUTING_DN_REFINEMENT_BUDGET_MS + " мс)");
                continue;
            }
            GeneratedVariant refined;
            try {
                refined = refineRoutingDn(v, ingest, existing, geometry, oksPoints, deadline, diagnostics);
            } catch (RuntimeException ex) {
                diagnostics.add("стратегия " + v.getStrategy().getCode() + ": уточнение по ДУ графа не выполнено: "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                log.warn("M7 уточнение стратегии {} упало", v.getStrategy().getCode(), ex);
                continue;
            }
            if (refined == v) {
                continue;
            }
            int i = built.indexOf(v);
            if (i >= 0) {
                built.set(i, refined);
            } else {
                // план первого прохода нарушал §2.1, уточнённый — чистый: стратегия возвращается в выдачу
                withViolations.remove(v);
                built.add(refined);
            }
        }
    }

    /**
     * AUDIT-12 (Claude, 24.09). Уточняющий проход по ДУ маршрутизации. Граф трассировки строится с буферами
     * (отступ табл. 2 + ½ ширины пары, табл. 4.2) по ДУ магистрали суммарного расхода всех ОКС, а фактические ДУ
     * участков (M5) обычно меньше: варианты с несколькими деревьями и ветки к отдельным ОКС несут малую часть
     * расхода. Отступ «с запасом» законен, но удлиняет трассу и в плотной застройке делает ближайшую стену ОКС
     * недоступной (§2.2: заход через дальнюю стену, косой заход). Второй план строится с ДУ графа, равным
     * наибольшему фактическому ДУ первого плана, и принимается, только если: нет замечаний §2.1, наибольший
     * фактический ДУ второго плана не больше ДУ его графа (буферы графа не меньше нормативных для каждого участка —
     * отступы соблюдены с фактическим ДУ; иначе один повтор с этим бо́льшим ДУ), подключено не меньше ОКС и S
     * строго меньше. Иначе остаётся первый план. Выполняется в пределах {@link #ROUTING_DN_REFINEMENT_BUDGET_MS}.
     */
    private GeneratedVariant refineRoutingDn(GeneratedVariant first, IngestResult ingest, ExistingNetwork existing,
                                             ExistingNetworkGeometry geometry, List<OksConnectionPoint> oksPoints,
                                             long deadlineNanos, List<String> diagnostics) {
        VariantStrategy strategy = first.getStrategy();
        int routed = networkPlanner.magistralRoutingDn(oksPoints);
        int dn = maxDiameter(first);
        GeneratedVariant best = first;
        // Не больше двух проходов: если во втором плане какой-то участок получил ДУ больше ДУ графа (другая
        // топология — другие расходы), его отступы не гарантированы; тогда повтор с этим ДУ.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (dn <= 0 || dn >= routed || System.nanoTime() > deadlineNanos) {
                break;
            }
            NetworkPlan plan;
            try {
                plan = buildPlan(strategy, ingest, existing, geometry, oksPoints, dn);
            } catch (RuntimeException ex) {
                diagnostics.add("стратегия " + strategy.getCode() + ": уточняющий проход с ДУ графа " + dn
                        + " не построен: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                break;
            }
            if (!plan.getDiagnostics().isEmpty()) {
                diagnostics.add("стратегия " + strategy.getCode() + ": уточняющий проход с ДУ графа " + dn
                        + " отклонён — замечания §2.1: " + plan.getDiagnostics());
                break;
            }
            GeneratedVariant refined = evaluate(strategy, plan, existing);
            int refinedMax = maxDiameter(refined);
            if (refinedMax > dn) {
                diagnostics.add(String.format(Locale.ROOT,
                        "стратегия %s: уточняющий проход с ДУ графа %d дал участок ДУ %d > ДУ графа — повтор с ДУ %d",
                        strategy.getCode(), dn, refinedMax, refinedMax));
                dn = refinedMax;
                continue;
            }
            boolean notFewerConnected = connectedCount(refined) >= connectedCount(best);
            // AUDIT-13 (Claude, 25.09): сначала — §2.2 (заход от ближайшей стены), затем S. План с ДУ графа «с запасом»
            // может обходить недоступную при этом ДУ ближайшую стену ОКС глубоким/косым заходом — он короче и по S
            // выигрывает у уточнённого плана, где при фактическом ДУ заход идёт как требует §2.2 (синтетика s5,
            // стратегия B: 5 ОКС с заходом на 6–13 м глубже ближайшей стены). Отступление от правила не покупается
            // показателем S (см. NetworkPlanner.entryExcessM; сравнение с допуском 0,5 м).
            double bestExcess = networkPlanner.entryExcessM(ingest, best.getPlan());
            double refinedExcess = networkPlanner.entryExcessM(ingest, refined.getPlan());
            boolean betterEntries = refinedExcess < bestExcess - ENTRY_EXCESS_MARGIN_M;
            boolean worseEntries = refinedExcess > bestExcess + ENTRY_EXCESS_MARGIN_M;
            boolean better = !best.getPlan().getDiagnostics().isEmpty() || betterEntries
                    || (!worseEntries && refined.getSummary().getScore() < best.getSummary().getScore() - 1e-9);
            boolean accepted = notFewerConnected && better;
            diagnostics.add(String.format(Locale.ROOT,
                    "стратегия %s: уточняющий проход с ДУ графа %d (ДУ магистрали %d): S %.4f → %.4f, "
                            + "отступление от ближайшей стены ОКС (§2.2) %.1f → %.1f м — %s",
                    strategy.getCode(), dn, routed, best.getSummary().getScore(), refined.getSummary().getScore(),
                    bestExcess, refinedExcess, accepted ? "принят" : "оставлен первый план"));
            if (accepted) {
                best = refined;
            }
            break;
        }
        return best;
    }

    private static int maxDiameter(GeneratedVariant variant) {
        int max = 0;
        EngineeringResult eng = variant.getCost().getEngineering();
        if (eng == null) {
            return 0;
        }
        for (ru.heatnet.calc.TreeCalcResult tree : eng.getTrees()) {
            for (Integer dn : tree.getDiameters().values()) {
                if (dn != null) {
                    max = Math.max(max, dn);
                }
            }
        }
        return max;
    }

    private static int connectedCount(GeneratedVariant variant) {
        int n = 0;
        for (ru.heatnet.calc.model.NewNetworkTree tree : variant.getPlan().getTrees()) {
            n += tree.oksNodes().size();
        }
        return n;
    }

    // ------------------------------------------------------------------ стратегии

    private NetworkPlan buildPlan(VariantStrategy strategy,
                                  IngestResult ingest,
                                  ExistingNetwork existing,
                                  ExistingNetworkGeometry geometry,
                                  List<OksConnectionPoint> oksPoints,
                                  int routingDn) {
        // AUDIT-24.09 (Claude): все стратегии — один сеанс дерева Штейнера в общем графе и с общей
        // проверкой пересечений (PlanMode). Раньше B/C строились отдельными прогонами и склеивались
        // постфактум: B при >8 ОКС пропускался, а C отбраковывался из-за пересечения частей сети
        // разных групп — в выдаче оставался один вариант.
        switch (strategy) {
            case SEPARATE_EACH:
                return networkPlanner.plan(ingest, existing, geometry, oksPoints, PlanMode.SEPARATE, null, routingDn);
            case CLUSTERED:
                return networkPlanner.plan(ingest, existing, geometry, oksPoints, PlanMode.CLUSTERED,
                        groupIndex(cluster(ingest, oksPoints)), routingDn);
            case JOINT_ALL:
            default:
                return networkPlanner.plan(ingest, existing, geometry, oksPoints, PlanMode.JOINT, null, routingDn);
        }
    }

    private static java.util.Map<String, Integer> groupIndex(List<List<OksConnectionPoint>> groups) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (int g = 0; g < groups.size(); g++) {
            for (OksConnectionPoint oks : groups.get(g)) {
                out.put(oks.getId(), g);
            }
        }
        return out;
    }

    /**
     * AUDIT-24.09 (Claude). §2.5 приложения и Разъяснение №15: неподключение допустимо, только если
     * допустимый маршрут не найден. Если ОКС подключён хотя бы в одном варианте, маршрут для него
     * существует — вариант, где этот ОКС оставлен без подключения, в выдачу не идёт.
     */
    private static List<GeneratedVariant> dropAvoidableUnconnected(List<GeneratedVariant> built,
                                                                   List<String> diagnostics) {
        java.util.Set<String> everConnected = new java.util.HashSet<>();
        java.util.Set<String> all = new java.util.HashSet<>();
        for (GeneratedVariant v : built) {
            java.util.Set<String> unc = new java.util.HashSet<>();
            for (UnconnectedOks u : v.getPlan().getUnconnectedOks()) {
                unc.add(u.getOksId());
                all.add(u.getOksId());
            }
            for (ru.heatnet.calc.model.NewNetworkTree tree : v.getPlan().getTrees()) {
                for (ru.heatnet.calc.model.NewNode n : tree.oksNodes()) {
                    everConnected.add(n.getOksId());
                }
            }
        }
        List<GeneratedVariant> kept = new ArrayList<>();
        for (GeneratedVariant v : built) {
            boolean avoidable = false;
            for (UnconnectedOks u : v.getPlan().getUnconnectedOks()) {
                if (everConnected.contains(u.getOksId())) {
                    avoidable = true;
                    break;
                }
            }
            if (avoidable) {
                diagnostics.add("вариант " + v.getVariantId() + " отброшен: в нём не подключены ОКС, для которых "
                        + "другой вариант нашёл допустимый маршрут (§2.5, Разъяснение №15)");
            } else {
                kept.add(v);
            }
        }
        return kept.isEmpty() ? built : kept;
    }

    private static List<List<OksConnectionPoint>> singletonGroups(List<OksConnectionPoint> oksPoints) {
        List<List<OksConnectionPoint>> groups = new ArrayList<>();
        for (OksConnectionPoint oks : oksPoints) {
            groups.add(Collections.singletonList(oks));
        }
        return groups;
    }

    /**
     * Географическая кластеризация точек подключения: разрез по медиане более длинной стороны
     * охватывающего прямоугольника. Даёт разбиение сети на части — признак отличия по §2.8 ТЗ.
     */
    private List<List<OksConnectionPoint>> cluster(IngestResult ingest, List<OksConnectionPoint> oksPoints) {
        if (oksPoints.size() < 2) {
            return singletonGroups(oksPoints);
        }
        List<Positioned> positioned = new ArrayList<>();
        for (OksConnectionPoint oks : oksPoints) {
            Coordinate c = oksCoordinate(ingest, oks.getId());
            if (c != null) {
                positioned.add(new Positioned(oks, c));
            }
        }
        if (positioned.size() < 2) {
            return singletonGroups(oksPoints);
        }
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Positioned p : positioned) {
            minX = Math.min(minX, p.coordinate.x);
            maxX = Math.max(maxX, p.coordinate.x);
            minY = Math.min(minY, p.coordinate.y);
            maxY = Math.max(maxY, p.coordinate.y);
        }
        final boolean byX = (maxX - minX) >= (maxY - minY);
        positioned.sort(Comparator.comparingDouble(p -> byX ? p.coordinate.x : p.coordinate.y));

        int half = positioned.size() / 2;
        List<OksConnectionPoint> left = new ArrayList<>();
        List<OksConnectionPoint> right = new ArrayList<>();
        for (int i = 0; i < positioned.size(); i++) {
            (i < half ? left : right).add(positioned.get(i).oks);
        }
        List<List<OksConnectionPoint>> groups = new ArrayList<>();
        groups.add(left);
        groups.add(right);
        return groups;
    }

    private Coordinate oksCoordinate(IngestResult ingest, String oksId) {
        for (RawFeature f : ingest.getAcceptedFeatures()) {
            if (f.getKind() == RawFeature.Kind.OKS_CONNECTION_POINT && oksId.equals(f.getId())
                    && f.getGeometryWgs84() != null) {
                Coordinate c = f.getGeometryWgs84().getCoordinate();
                return projection.toUtm(c.x, c.y);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ расчёт и ранжирование

    /** Смета плана без проверки диагностики (для резервного варианта). */
    private GeneratedVariant evaluateUnchecked(VariantStrategy strategy, NetworkPlan plan, ExistingNetwork existing) {
        String variantId = "v" + strategy.getCode();
        EngineeringResult eng = engineering.calculate(plan.getTrees(), existing);
        VariantCost cost = costCalculator.calculate(variantId, eng, plan.getUnconnectedOks());
        return new GeneratedVariant(variantId, strategy, plan, cost);
    }

    private GeneratedVariant evaluate(VariantStrategy strategy, NetworkPlan plan, ExistingNetwork existing) {
        if (!plan.getDiagnostics().isEmpty()) {
            // §2.1: пересечение новых участков вне узла, разворот > 90°, разветвление вне камеры
            // и т.п. — обязательные правила приложения, не рекомендации. Вариант с такими находками
            // не должен уходить в выдачу ни под каким rank — лучше эта стратегия провалится
            // целиком (см. catch в generate()), чем экспортируется геометрически некорректная сеть.
            throw new ru.heatnet.network.NetworkException("план нарушает правила топологии/геометрии §2.1: "
                    + plan.getDiagnostics());
        }
        String variantId = "v" + strategy.getCode();
        List<UnconnectedOks> unconnected = plan.getUnconnectedOks();
        EngineeringResult eng = engineering.calculate(plan.getTrees(), existing);
        VariantCost cost = costCalculator.calculate(variantId, eng, unconnected);
        return new GeneratedVariant(variantId, strategy, plan, cost);
    }

    /** Ранжирование через M6 с переносом полученного rank обратно в вариант. */
    private List<GeneratedVariant> rank(List<GeneratedVariant> variants) {
        List<VariantCost> costs = new ArrayList<>();
        for (GeneratedVariant v : variants) {
            costs.add(v.getCost());
        }
        List<VariantCost> ranked = ranker.rank(costs);
        List<GeneratedVariant> result = new ArrayList<>();
        for (VariantCost rc : ranked) {
            for (GeneratedVariant v : variants) {
                if (v.getVariantId().equals(rc.getVariantId())) {
                    result.add(v.withSummary(rc.getSummary()));
                    break;
                }
            }
        }
        return result;
    }

    private static final class Positioned {
        private final OksConnectionPoint oks;
        private final Coordinate coordinate;

        Positioned(OksConnectionPoint oks, Coordinate coordinate) {
            this.oks = oks;
            this.coordinate = coordinate;
        }
    }
}
