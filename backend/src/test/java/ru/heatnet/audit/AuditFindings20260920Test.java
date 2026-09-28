package ru.heatnet.audit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.calc.reference.RestrictionRule;
import ru.heatnet.geo.GeoUtils;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestReport;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.ingest.RawFeature;
import ru.heatnet.network.ExistingNetworkGeometry;
import ru.heatnet.network.TieInCandidate;
import ru.heatnet.network.TieInCandidates;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.RulesException;
import ru.heatnet.rules.SpatialConstraintEngine;
import ru.heatnet.rules.model.RestrictionFeature;

/**
 * Аудит 20.09.2026. Каждый тест кодирует требование обновлённого Технического приложения
 * (19.09.2026) или «Разъяснений». Находки аудита закрыты, если все шесть зелёные.
 *
 * <p>Тег {@code slow} — запуск: {@code mvn test -Dtest=AuditFindings20260920Test -DexcludedGroups=}</p>
 */
@Tag("slow")
class AuditFindings20260920Test {

    private static final ProjectionService PROJECTION = new ProjectionService();

    private static ReferenceData ref() {
        return TestReference.get();
    }

    private static GeometryFactory utm() {
        return PROJECTION.utmFactory();
    }

    private static Polygon square(double minX, double minY, double size) {
        GeometryFactory gf = utm();
        return gf.createPolygon(gf.createLinearRing(new Coordinate[] {
                new Coordinate(minX, minY),
                new Coordinate(minX + size, minY),
                new Coordinate(minX + size, minY + size),
                new Coordinate(minX, minY + size),
                new Coordinate(minX, minY)
        }));
    }

    private static LineString seg(double x1, double y1, double x2, double y2) {
        return utm().createLineString(new Coordinate[] {new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }

    private static RestrictionFeature feature(String id, String rulesKey, Geometry geom) {
        RestrictionRule rule = ref().getRules().restriction(rulesKey);
        return new RestrictionFeature(id, rulesKey, rule, geom);
    }

    private static SpatialConstraintEngine engine(List<RestrictionFeature> features, int referenceDn) {
        RestrictionEngineFactory factory = new RestrictionEngineFactory(null, ref(), PROJECTION);
        return factory.create(features, referenceDn);
    }

    private static double pairHalf(int dn) {
        return ref().getGabarits().spec(dn).getWidthM() / 2.0;
    }

    // ------------------------------------------------------------------ A-1

    /**
     * Табл. 2 приложения: для {@code road} мин. горизонтальное расстояние 1,5 м.
     * «Разъяснения» №7: при проходе РЯДОМ расстояние измеряется от границы полигона
     * до внешней границы габарита новой сети. Внутрь спецпересечения правило не применяется,
     * но продольный проход вдоль дороги спецпересечением не является.
     */
    @Test
    void roadMinOffsetIsEnforcedWhenRunningAlongside() {
        int dn = 100;
        double required = 1.5 + pairHalf(dn);
        Polygon road = square(0, 0, 100);
        SpatialConstraintEngine engine = engine(
                Collections.singletonList(feature("road_1", "road", road)), dn);

        // трасса идёт вдоль западной границы дороги в 0,2 м от неё — грубое нарушение 1,5 м
        LineString alongside = seg(-0.2, 10, -0.2, 90);
        assertTrue(alongside.distance(road) < required,
                "setup: отрезок должен быть ближе нормативного отступа");
        assertTrue(engine.isSegmentBlocked(alongside, dn),
                "Проход вдоль дороги в " + alongside.distance(road) + " м от границы полигона должен быть "
                        + "запрещён: требуется >= " + required + " м (1,5 м + W_пары/2)");
    }

    /** То же для трамвайных путей (табл. 2: 1,5 м). */
    @Test
    void tramMinOffsetIsEnforcedWhenRunningAlongside() {
        int dn = 100;
        Polygon tram = square(0, 0, 100);
        SpatialConstraintEngine engine = engine(
                Collections.singletonList(feature("tram_1", "tram_tracks", tram)), dn);
        LineString alongside = seg(-0.2, 10, -0.2, 90);
        assertTrue(engine.isSegmentBlocked(alongside, dn),
                "Проход вдоль трамвайных путей в 0,2 м от границы должен быть запрещён (1,5 м + W_пары/2)");
    }

    // ------------------------------------------------------------------ A-2

    /**
     * «Разъяснения» №3 / табл. 2: отступ от полигона ОКС зависит от ДУ новой сети.
     * Бандл заморожен под referenceDn; чужой candidateDn — ошибка контракта (пересборка через фабрику).
     */
    @Test
    void isSegmentBlockedHonoursCandidateDn() {
        Polygon oks = square(0, 0, 100);
        SpatialConstraintEngine engine = engine(
                Collections.singletonList(feature("oks_1", "oks_existing", oks)), 100);

        double offsetFor100 = 5.0 + pairHalf(100);
        double offsetFor1000 = 9.0 + pairHalf(1000);
        double x = -(offsetFor100 + 0.5);
        assertTrue(Math.abs(x) < offsetFor1000,
                "setup: отрезок должен проходить для ДУ 100 и нарушать отступ для ДУ 1000");

        LineString probe = seg(x, 10, x, 90);
        assertTrue(!engine.isSegmentBlocked(probe, 100),
                "для ДУ 100 отрезок в " + Math.abs(x) + " м допустим");
        RulesException ex = assertThrows(RulesException.class, () -> engine.isSegmentBlocked(probe, 1000),
                "чужой candidateDn обязан отказать: бандл собран под ДУ 100");
        assertTrue(ex.getMessage().contains("1000"), ex.getMessage());
    }

    // ------------------------------------------------------------------ A-3

    /**
     * Табл. 2: {@code gas_pipeline} — мин. расстояние 2,0 м. Одно точечное пересечение
     * не отменяет требование отступа на остальной длине отрезка («Разъяснения» №7:
     * не проверяется только ВНУТРИ разрешённого специального пересечения).
     */
    @Test
    void crossingLinearObstacleOnceDoesNotWaiveOffsetAlongTheRest() {
        int dn = 100;
        GeometryFactory gf = utm();
        // газопровод: Г-образная линия — вертикальный участок и длинный горизонтальный
        LineString gas = gf.createLineString(new Coordinate[] {
                new Coordinate(0, -50),
                new Coordinate(0, 0),
                new Coordinate(200, 0)
        });
        SpatialConstraintEngine engine = engine(
                Collections.singletonList(feature("gas_1", "gas_pipeline", gas)), dn);

        // трасса пересекает вертикальную часть газопровода один раз (в точке (0,-25)),
        // затем идёт вдоль горизонтальной части в 0,3 м над ней — нарушение 2,0 м
        LineString path = gf.createLineString(new Coordinate[] {
                new Coordinate(-10, -25),
                new Coordinate(10, -25),
                new Coordinate(10, 0.3),
                new Coordinate(190, 0.3)
        });
        assertTrue(path.intersects(gas), "setup: трасса пересекает газопровод");
        assertTrue(engine.isSegmentBlocked(path, dn),
                "Одно пересечение газопровода не должно отменять отступ 2,0 м на участке, идущем вдоль него");
    }

    // ------------------------------------------------------------------ A-4

    /**
     * §2.4 приложения и «Разъяснения» №11: правило 10 м измеряется от ВЫБРАННОЙ ТОЧКИ
     * ПРИСОЕДИНЕНИЯ на существующей сети до существующей камеры, а не от точки ОКС.
     * Если точка присоединения ближе 10 м к камере с числом примыканий &lt;= 4 —
     * «используется эта камера» (обязательное правило, врезка 5 000 000 руб.).
     */
    @Test
    void tenMetreChamberRuleIsMeasuredFromTieInPointNotFromOks() {
        Coordinate srcWgs = new Coordinate(37.6200, 55.7000);
        Coordinate chWgs = new Coordinate(37.6216, 55.7000);
        Coordinate farEndWgs = new Coordinate(37.6248, 55.7000);
        // ОКС: ~5 м восточнее камеры по долготе, но ~60 м севернее по широте
        Coordinate oksWgs = new Coordinate(37.62168, 55.70054);

        List<RawFeature> features = new ArrayList<>();
        features.add(point("src", RawFeature.Kind.SOURCE, srcWgs, null));
        features.add(point("ch1", RawFeature.Kind.HEAT_CHAMBER, chWgs, null));
        features.add(line("net1", srcWgs, chWgs, 200));
        features.add(line("net2", chWgs, farEndWgs, 200));
        features.add(point("oks1", RawFeature.Kind.OKS_CONNECTION_POINT, oksWgs, 20.0));

        IngestReport report = new IngestReport();
        ExistingNetwork network = new ru.heatnet.ingest.NetworkTreeBuilder(PROJECTION)
                .build(features, 15.0, report);
        IngestResult ingest = new IngestResult(report, network,
                Arrays.asList(new OksConnectionPoint("oks1", 20.0)),
                Collections.<String, Geometry>emptyMap(), features);
        ExistingNetworkGeometry geometry = ExistingNetworkGeometry.fromIngest(
                ingest.getAcceptedFeatures(), network, PROJECTION);

        Coordinate oksUtm = PROJECTION.pointToUtm(oksWgs.x, oksWgs.y).getCoordinate();
        Coordinate chUtm = geometry.chamberPoint("ch1");

        double oksToChamber = oksUtm.distance(chUtm);
        ExistingNetworkGeometry.PipeProjection proj = geometry.projectOnSegment("net2", oksUtm, 2.0);
        assertNotNull(proj, "setup: должна быть проекция на net2");
        double tieToChamber = proj.getPoint().distance(chUtm);

        assertTrue(oksToChamber > 10.0,
                "setup: ОКС должен быть дальше 10 м от камеры, факт " + oksToChamber);
        assertTrue(tieToChamber <= 10.0,
                "setup: точка присоединения должна быть не далее 10 м от камеры, факт " + tieToChamber);

        TieInCandidate best = TieInCandidates.bestNear(network, geometry, ref().getRules(), oksUtm, 200);
        assertNotNull(best, "кандидат врезки должен быть найден");
        assertEquals(TieInCandidate.Kind.EXISTING_CHAMBER, best.getKind(),
                "точка присоединения в " + String.format("%.1f", tieToChamber)
                        + " м от камеры ch1 → §2.4 требует использовать существующую камеру "
                        + "(врезка 5 000 000 руб.), а не строить новую. Расстояние ОКС→камера "
                        + String.format("%.1f", oksToChamber) + " м к правилу не относится");
    }

    // ------------------------------------------------------------------ A-5

    /**
     * §7 приложения: идентификаторы могут быть строковыми или числовыми.
     * Строковый ключ остаётся для Map; исходный JSON-тип едет в {@link RawFeature#exportId()}.
     * Повтор id (число и строка с одним значением) — ошибка ingest, не молчаливая перезапись.
     */
    @Test
    void numericAndStringIdsMustNotCollideAfterIngest() throws Exception {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", Integer.valueOf(7));
        a.put("object_type", "heat_chamber");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", "7");
        b.put("object_type", "heat_chamber");

        RawFeature fa = RawFeature.of("7", RawFeature.Kind.HEAT_CHAMBER, a,
                GeoUtils.pointWgs84(37.62, 55.70), null, true);
        RawFeature fb = RawFeature.of("7", RawFeature.Kind.HEAT_CHAMBER, b,
                GeoUtils.pointWgs84(37.63, 55.70), null, false);

        assertEquals(Long.valueOf(7L), fa.exportId());
        assertEquals("7", fb.exportId());
        assertTrue(fa.exportId() instanceof Long);
        assertTrue(fb.exportId() instanceof String);

        String json = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"id\":7,\"object_type\":\"heat_chamber\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.62,55.70]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"id\":\"7\",\"object_type\":\"heat_chamber\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.63,55.70]}}"
                + "]}";
        IngestReport report = new IngestReport();
        java.util.List<RawFeature> features = new ru.heatnet.ingest.StreamingGeoJsonReader(PROJECTION)
                .read(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        report);
        assertEquals(1, features.size(), "второй объект с тем же ключом id должен быть пропущен");
        assertTrue(features.get(0).isNumericId());
        assertEquals(Long.valueOf(7L), features.get(0).exportId());
        assertTrue(report.hasErrors(), "ожидается DUPLICATE_ID");
    }

    // ------------------------------------------------------------------ helpers

    private static RawFeature point(String id, RawFeature.Kind kind, Coordinate wgs, Double flow) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (flow != null) {
            props.put("flow_tph", flow);
        }
        return RawFeature.of(id, kind, props, GeoUtils.pointWgs84(wgs.x, wgs.y), null);
    }

    private static RawFeature line(String id, Coordinate a, Coordinate b, int diameter) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("diameter", diameter);
        Coordinate[] coords = new Coordinate[] {a, b};
        return RawFeature.of(id, RawFeature.Kind.HEAT_NETWORK, props,
                GeoUtils.wgs84Factory().createLineString(coords), coords);
    }
}
