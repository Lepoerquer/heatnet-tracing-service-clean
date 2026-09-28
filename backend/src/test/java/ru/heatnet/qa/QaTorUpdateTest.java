package ru.heatnet.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import ru.heatnet.calc.DiameterSelector;
import ru.heatnet.calc.EngineeringCalculator;
import ru.heatnet.calc.EngineeringResult;
import ru.heatnet.calc.FlowAggregator;
import ru.heatnet.calc.LengthLimitResult;
import ru.heatnet.calc.LengthLimitValidator;
import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.model.ExistingChamber;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingSegment;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.cost.ScoreCalculator;
import ru.heatnet.cost.UnconnectedOks;
import ru.heatnet.cost.VariantCost;
import ru.heatnet.cost.VariantCostCalculator;
import ru.heatnet.cost.VariantSummary;
import ru.heatnet.geo.ProjectionService;

/**
 * QA (роль 4): сверка кода с ОБНОВЛЁННЫМ Техническим приложением ЛЦТ (19.09.2026)
 * и «Разъяснениями по вопросам участников» (17 Q&amp;A).
 *
 * <p>Каждый тест описывает поведение, требуемое НОВЫМ текстом ТЗ; красный тест = расхождение
 * реализации с действующим ТЗ. Ссылки на разделы — по обновлённому приложению.</p>
 */
class QaTorUpdateTest {

    private final ReferenceData ref = TestReference.get();
    private final ScoreCalculator score = new ScoreCalculator(ref.getRules());
    private final FlowAggregator flows = new FlowAggregator();
    private final DiameterSelector selector = new DiameterSelector(ref.getDiameters());

    // ---------------------------------------------------------------- §3.2 экономика камер и врезок

    /** Существующая сеть: source → net1 (DN200, 100 м) → ch1. */
    private ExistingNetwork network() {
        return new ExistingNetwork(
                Collections.singletonList(new ExistingSegment("net1", 200, null, "src", 100.0)),
                Collections.singletonList(new ExistingChamber("ch1", 200, "net1")),
                Collections.singletonList("src"));
    }

    /** Дерево из одной врезки к одному ОКС. */
    private NewNetworkTree tree(TieInPoint tie, double flowTph, double lengthM) {
        List<NewNode> nodes = Arrays.asList(NewNode.tieIn(tie.getId()), NewNode.oks("oks1", "oks1", flowTph));
        List<NewSegment> segs = Collections.singletonList(
                NewSegment.base("s1", tie.getId(), "oks1", lengthM));
        return new NewNetworkTree(tie, nodes, segs);
    }

    private VariantSummary summaryFor(TieInPoint tie, double flowTph, double lengthM) {
        NewNetworkTree t = tree(tie, flowTph, lengthM);
        EngineeringResult eng = new EngineeringCalculator(ref)
                .calculate(Collections.singletonList(t), network());
        VariantCost cost = new VariantCostCalculator(ref)
                .calculate("v1", eng, Collections.<UnconnectedOks>emptyList());
        return cost.getSummary();
    }

    @Test
    @DisplayName("TOR-1 (§3.2, F23): врезка в ТРУБУ создаёт новую камеру — доплата 5 млн НЕ добавляется")
    void newChamberHasNoSeparateTieInCost() {
        // §3.2: «Стоимость новой камеры включает присоединение к существующей сети.
        //        Отдельная стоимость врезки при строительстве новой камеры не добавляется.»
        VariantSummary s = summaryFor(TieInPoint.intoPipe("tie1", "net1", 50.0, "nch1"), 20.0, 100.0);
        assertEquals(0L, s.getTieInCost(),
                "новая камера: по §3.2 доплата за врезку не начисляется, а код добавил "
                        + s.getTieInCost() + " ₽");
    }

    @Test
    @DisplayName("TOR-2 (§3.2): врезка в СУЩЕСТВУЮЩУЮ камеру — ровно 5 млн за врезку")
    void existingChamberTieInCostsFiveMillion() {
        VariantSummary s = summaryFor(TieInPoint.intoChamber("tie1", "ch1"), 20.0, 100.0);
        assertEquals(5_000_000L, s.getTieInCost());
    }

    // ---------------------------------------------------------------- §2.3 предельная длина

    /**
     * tie → ch (камера) → две параллельные ветви одного ДУ.
     * Каждая ветвь по отдельности в пределах лимита; сумма — нет.
     */
    private NewNetworkTree branched(double trunkLen, double branchLen, double oksFlow) {
        List<NewNode> nodes = Arrays.asList(
                NewNode.tieIn("tie"), NewNode.chamber("ch"),
                NewNode.oks("a", "a", oksFlow), NewNode.oks("b", "b", oksFlow));
        List<NewSegment> segs = Arrays.asList(
                NewSegment.base("trunk", "tie", "ch", trunkLen),
                NewSegment.base("brA", "ch", "a", branchLen),
                NewSegment.base("brB", "ch", "b", branchLen));
        return new NewNetworkTree(TieInPoint.intoChamber("tie", "ch1"), nodes, segs);
    }

    @Test
    @DisplayName("TOR-3 (§2.3 / Разъяснения №2): длины ПАРАЛЛЕЛЬНЫХ ветвей не суммируются")
    void parallelBranchLengthsAreNotSummed() {
        // §2.3: «Предельная длина проверяется отдельно по каждому непрерывному пути…
        //        Длины параллельных ветвей между собой не суммируются.»
        // ДУ50 лимит 181 м. Две ветви по 100 м: каждый путь 100 м ≤ 181 → подъём ДУ НЕ нужен.
        NewNetworkTree t = branched(10.0, 100.0, 3.0);
        Map<String, Double> f = flows.aggregate(t);
        Map<String, Integer> hyd = selector.select(t, f);
        assertEquals(50, (int) hyd.get("brA"), "предусловие: гидравлический ДУ ветви = 50");

        LengthLimitResult r = new LengthLimitValidator(ref.getDiameters(),
                ref.getRules().getLengthLimitMaxDnSteps(), ref.getRules().getGeometryToleranceM())
                .apply(t, hyd);

        assertEquals(50, (int) r.getDiameters().get("brA"),
                "ветвь A: 100 м ≤ 181 м (лимит ДУ50) — ДУ подниматься не должен, код дал ДУ"
                        + r.getDiameters().get("brA") + " (сложил 100+100=200 м параллельных ветвей)");
        assertEquals(50, (int) r.getDiameters().get("brB"), "ветвь B — то же самое");
    }

    @Test
    @DisplayName("TOR-4 (§2.3 / Разъяснения №1): выбирается следующий минимальный ДУ, удовлетворяющий ОБОИМ условиям (не «+1 ступень»)")
    void lengthLimitPicksNextDnSatisfyingBothConditions() {
        // §2.3: «Если минимальный по расходу ДУ не удовлетворяет предельной длине, выбирается
        //        следующий минимальный ДУ, удовлетворяющий обоим условиям.»
        // 3 т/ч → ДУ50 (лимит 181). Плеть 400 м: ДУ65=245 мало, ДУ80=327 мало, ДУ100=419 подходит.
        List<NewNode> nodes = Arrays.asList(NewNode.tieIn("tie"), NewNode.oks("o", "o", 3.0));
        List<NewSegment> segs = Collections.singletonList(NewSegment.base("s", "tie", "o", 400.0));
        NewNetworkTree t = new NewNetworkTree(TieInPoint.intoChamber("tie", "ch1"), nodes, segs);
        Map<String, Integer> hyd = selector.select(t, flows.aggregate(t));
        LengthLimitResult r = new LengthLimitValidator(ref.getDiameters(),
                ref.getRules().getLengthLimitMaxDnSteps(), ref.getRules().getGeometryToleranceM())
                .apply(t, hyd);
        assertEquals(100, (int) r.getDiameters().get("s"),
                "400 м требуют ДУ100 (лимит 419 м); код ограничен «+1 ступень» и дал ДУ"
                        + r.getDiameters().get("s"));
        assertTrue(r.isWithinLimits(), "после выбора корректного ДУ превышения быть не должно");
    }

    // ---------------------------------------------------------------- §6 / §7.2 стоимость и состав полей

    @Test
    @DisplayName("TOR-5 (§7.2): construction_cost = участки + новые камеры + врезки (в примере §7.3 — 13 974 800)")
    void constructionCostIncludesChambersAndTieIns() {
        // §6: «Стоимость строительства (construction_cost) равна сумме стоимости новых участков
        //      тепловой сети, новых тепловых камер и врезок в существующие тепловые камеры.»
        // §7.2: chamber_construction_cost и existing_chamber_tie_in_cost — «входящая в construction_cost».
        VariantSummary s = VariantSummary.builder("v1")
                .addNewSegment(100.0, 8_974_800L)
                .addTieIn(5_000_000L)
                .build(score);
        assertEquals(13_974_800L, s.getConstructionCost(),
                "по новому §6 construction_cost — это итог строительства (участки+камеры+врезки); "
                        + "код вернул только стоимость участков");
    }

    @Test
    @DisplayName("TOR-6 (§7.3): опорный пример — calculated_cost 13 974 800, L 100,0, S = 0,6913")
    void newReferenceExample() {
        // 100 м × 89 748 ₽/м (ДУ100) = 8 974 800 — совпадает с "cost" в примере
        assertEquals(8_974_800L, new ru.heatnet.cost.SegmentCostCalculator(ref.getDiameters())
                .cost(100.0, 100, 1.0, 1.0));
        VariantSummary s = VariantSummary.builder("v1")
                .addNewSegment(100.0, 8_974_800L)
                .addTieIn(5_000_000L)
                .build(score);
        assertEquals(13_974_800L, s.getCalculatedCost());
        assertEquals(100.0, s.getNewNetworkLength(), 1e-9);
        assertEquals(100.0, s.getLength(), 1e-9);
        // S = 0,7·(13 974 800/25 000 000) + 0,3·(100/100) = 0,6912944
        BigDecimal exact = new BigDecimal("0.6912944");
        assertEquals(exact.setScale(4, RoundingMode.HALF_UP).doubleValue(),
                BigDecimal.valueOf(s.getRawScore()).setScale(4, RoundingMode.HALF_UP).doubleValue(), 0.0);
        assertEquals(0.6913, s.getScore(), 0.0,
                "пример §7.3 печатает score с 4 знаками (0.6913); config score_scale="
                        + ref.getRules().getScoreScale() + " даёт " + s.getScore());
    }

    @Test
    @DisplayName("TOR-7 (§7.3): геометрия примера действительно даёт 100 м в EPSG:32637")
    void referenceExampleGeometryIsHundredMetres() {
        ProjectionService p = new ProjectionService();
        Coordinate a = p.toUtm(37.600000000, 55.750000000);
        Coordinate b = p.toUtm(37.599967825, 55.750898263);
        double len = a.distance(b);
        assertEquals(100.0, len, 0.1,
                "в отличие от старого примера 10.8, координаты §7.3 должны быть согласованы с length; получено " + len);
    }

    @Test
    @DisplayName("TOR-8 (§6 / Разъяснения №14): реконструкция исключена — calculated_cost = construction + штраф")
    void reconstructionIsOutOfScope() {
        // «Разъяснения» №14: «в актуальной расчётной модели реконструкция существующей тепловой
        //  сети и существующих камер не выполняется.»
        VariantSummary s = VariantSummary.builder("v1")
                .addNewSegment(100.0, 10_000_000L)
                .addReconstruction(50.0, 7_000_000L)   // не должно попасть ни в C, ни в L
                .build(score);
        assertEquals(10_000_000L, s.getCalculatedCost(),
                "стоимость реконструкции не входит в calculated_cost по обновлённому §6");
        assertEquals(100.0, s.getLength(), 1e-9,
                "L для S = new_network_length; длина реконструкции в неё не входит (§6)");
    }

    @Test
    @DisplayName("TOR-9 (§6): L для показателя S — только новые участки (new_network_length)")
    void lengthForScoreIsNewNetworkOnly() {
        VariantSummary withRecon = VariantSummary.builder("a")
                .addNewSegment(200.0, 1_000_000L).addReconstruction(500.0, 0L).build(score);
        VariantSummary withoutRecon = VariantSummary.builder("b")
                .addNewSegment(200.0, 1_000_000L).build(score);
        assertEquals(withoutRecon.getScore(), withRecon.getScore(), 0.0,
                "реконструкция не должна влиять на S");
    }

    // ---------------------------------------------------------------- §2.1 угол поворота

    @Test
    @DisplayName("TOR-10 (§2.1): отдельного удорожания поворота нет — k_angle в конфиге должен быть 1.0")
    void noTurnSurcharge() {
        // §2.1: «Допускается произвольный угол поворота до 90° включительно.
        //        Отдельное удорожание поворота не применяется.»
        assertEquals(1.0, ref.getRules().getKAngle(), 0.0,
                "config/rules.yaml angle.k_angle=" + ref.getRules().getKAngle()
                        + " — правило K_угол отменено обновлённым приложением");
    }
}
