package ru.heatnet.qa;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;
import ru.heatnet.rules.RestrictionEngineFactory;

/**
 * QA (роль 4): устойчивость M1 (ТЗ п. 2.14, разд. 8 «Техническая проработка: стабильность расчёта, диагностика данных»)
 * и соответствие входного контракта табл. 2.1 / 2.2 приложения.
 */
@SpringBootTest
class QaIngestRobustnessTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private RestrictionEngineFactory engineFactory;

    private static final String SRC = "{\"type\":\"Feature\",\"properties\":{\"id\":\"src\",\"object_type\":\"source\"},"
            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.62,55.70]}}";

    private static String fc(String... features) {
        return "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", features) + "]}";
    }

    private IngestResult ingest(String json) throws Exception {
        try (InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
            return ingestService.ingest(in);
        }
    }

    private static String poly(String id, String objectType, String extraProps) {
        return "{\"type\":\"Feature\",\"properties\":{\"id\":\"" + id + "\",\"object_type\":\"" + objectType + "\""
                + extraProps + "},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[37.6200,55.7000],[37.6201,55.7000],"
                + "[37.6201,55.7001],[37.6200,55.7001],[37.6200,55.7000]]]}}";
    }

    // ------------------------------------------------------------------ табл. 2.1: нормативные типы

    @Test
    @DisplayName("QA-M1-1: object_type=oks_existing (нормативный тип табл. 2.1) должен стать препятствием, а не игнорироваться как UNKNOWN")
    void oksExistingObjectTypeIsHonoured() throws Exception {
        IngestResult r = ingest(fc(SRC, poly("bld1", "oks_existing", "")));
        boolean treatedAsObstacle = r.getRestrictionsWgs84().containsKey("bld1");
        assertTrue(treatedAsObstacle,
                "здание object_type=oks_existing проигнорировано (отчёт: " + r.getReport().getCountsByType()
                        + ") — трасса пойдёт сквозь существующее здание");
    }

    @Test
    @DisplayName("QA-M1-2: oks_future = Polygon/MultiPolygon по табл. 2.1 — не должен давать ERROR в отчёте")
    void oksFutureAsPolygonIsValid() throws Exception {
        IngestResult r = ingest(fc(SRC, poly("f1", "oks_future", ",\"flow_tph\":12.5,\"heat_load\":0.9")));
        assertEquals(0, r.getReport().errorCount(),
                "валидный по табл. 2.1 oks_future (Polygon) отвергнут с ошибкой");
    }

    @Test
    @DisplayName("QA-M1-3: линейное ограничение MultiLineString (газопровод) принимается, а не теряется с ошибкой")
    void multiLineRestrictionAccepted() throws Exception {
        String gas = "{\"type\":\"Feature\",\"properties\":{\"id\":\"g1\",\"object_type\":\"restriction\","
                + "\"restriction_type\":\"gas_pipeline\"},\"geometry\":{\"type\":\"MultiLineString\","
                + "\"coordinates\":[[[37.6200,55.7000],[37.6210,55.7000]],[[37.6210,55.7000],[37.6220,55.7005]]]}}";
        IngestResult r = ingest(fc(SRC, gas));
        assertTrue(r.getRestrictionsWgs84().containsKey("g1"), "газопровод MultiLineString отброшен: " + r.getReport().errorCount() + " ошибок");
    }

    @Test
    @DisplayName("QA-M1-4: неизвестный restriction_type (метро, водопровод — перечислены в ТЗ 2.1) не должен ронять M2 целиком")
    void unknownRestrictionTypeDoesNotCrashRules() throws Exception {
        IngestResult r = ingest(fc(SRC, poly("m1", "restriction", ",\"restriction_type\":\"metro\"")));
        assertDoesNotThrow(() -> engineFactory.create(r, 200),
                "движок ограничений упал на неизвестном типе 'metro': весь расчёт на скрытом наборе завершится ошибкой");
    }

    // ------------------------------------------------------------------ устойчивость

    private static final String[] MALFORMED = {
            "", "   ", "[]", "{}", "null", "\"str\"", "123",
            "{\"type\":\"FeatureCollection\"}",
            "{\"type\":\"FeatureCollection\",\"features\":null}",
            "{\"type\":\"FeatureCollection\",\"features\":{}}",
            "{\"type\":\"FeatureCollection\",\"features\":[",
            "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"id\":1",
            fc("null"), fc("[]"), fc("{}"), fc("{\"type\":\"Feature\"}"),
            // RFC 7946 §3.2: properties — объект ИЛИ null; geometry — геометрия ИЛИ null
            fc("{\"type\":\"Feature\",\"properties\":null,\"geometry\":null}"),
            fc("{\"type\":\"Feature\",\"properties\":null,\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]}}"),
            fc("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]},\"properties\":null}"),
            fc("{\"type\":\"Feature\",\"properties\":[],\"geometry\":[]}"),
            fc("{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"source\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[]}}"),
            fc("{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"source\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[\"a\",\"b\"]}}"),
            fc("{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"source\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6]}}"),
            fc("{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"source\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[1e999,55]}}"),
            fc("{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"heat_network\",\"diameter\":200},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.6,55.7]]}}"),
            fc("{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"oks_connection_point\",\"flow_tph\":\"abc\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]}}"),
            fc("{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"oks_connection_point\",\"flow_tph\":-5},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]}}"),
            fc("{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"restriction\",\"restriction_type\":\"park\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[37.6,55.7],[37.6,55.7],[37.6,55.7],[37.6,55.7]]]}}"),
            fc("{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"restriction\",\"restriction_type\":\"park\"},\"geometry\":{\"type\":\"GeometryCollection\",\"geometries\":[]}}"),
            fc("{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"restriction\",\"restriction_type\":\"park\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[]}}"),
            fc("{\"type\":\"Feature\",\"properties\":{\"id\":{\"a\":1},\"object_type\":\"source\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]}}"),
            fc("{\"type\":\"Feature\",\"properties\":{\"id\":\"x\",\"object_type\":\"source\",\"nested\":{\"a\":[1,2,{\"b\":null}]}},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]}}"),
            fc(SRC, SRC), // дубликат id
    };

    @org.junit.jupiter.api.TestFactory
    @DisplayName("QA-M1-5: матрица некорректных входов — ingest не зависает и не бросает необработанное RuntimeException")
    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> malformedInputs() {
        java.util.List<org.junit.jupiter.api.DynamicTest> tests = new java.util.ArrayList<>();
        for (int i = 0; i < MALFORMED.length; i++) {
            final String json = MALFORMED[i];
            tests.add(org.junit.jupiter.api.DynamicTest.dynamicTest(
                    "QA-M1-5#" + i + " " + abbreviate(json), () -> assertNoHangNoCrash(json)));
        }
        return tests.stream();
    }

    /** Запуск ingest в отдельном потоке; зависший поток останавливается (Thread.stop — только для QA-харнесса). */
    @SuppressWarnings("deprecation")
    private void assertNoHangNoCrash(String json) throws Exception {
        final Throwable[] err = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                ingest(json);
            } catch (Throwable e) {
                err[0] = e;
            }
        }, "qa-ingest");
        t.setDaemon(true);
        t.start();
        t.join(5000);
        if (t.isAlive()) {
            t.stop();
            org.junit.jupiter.api.Assertions.fail("ЗАВИСАНИЕ: ingest не завершился за 5 с (бесконечный цикл парсера) на входе: " + abbreviate(json));
        }
        if (err[0] != null && !(err[0] instanceof java.io.IOException)) {
            org.junit.jupiter.api.Assertions.fail("ingest бросил " + err[0].getClass().getName() + ": " + err[0].getMessage());
        }
    }
    @Test
    @DisplayName("QA-M1-6: дубликаты id heat_network не должны ронять ingest целиком (диагностика в отчёте)")
    void duplicateNetworkIds() {
        String line = "{\"type\":\"Feature\",\"properties\":{\"id\":\"n1\",\"object_type\":\"heat_network\",\"diameter\":200},"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.6200,55.7000],[37.6210,55.7000]]}}";
        assertDoesNotThrow(() -> ingest(fc(SRC, line, line)));
    }

    @Test
    @DisplayName("QA-M1-7: перепутанные lon/lat дают понятную ошибку в отчёте (COORD_ORDER), объект пропущен")
    void swappedCoordinatesReported() throws Exception {
        String pt = "{\"type\":\"Feature\",\"properties\":{\"id\":\"o1\",\"object_type\":\"oks_connection_point\",\"flow_tph\":5},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[55.70,37.62]}}";
        IngestResult r = ingest(fc(SRC, pt));
        assertTrue(r.getReport().hasErrors());
        assertTrue(r.getOksConnectionPoints().isEmpty());
    }

    @Test
    @DisplayName("QA-M1-8: 3D-координаты [lon, lat, z] допустимы (RFC 7946) и не портят геометрию")
    void threeDimensionalCoordinates() throws Exception {
        String pt = "{\"type\":\"Feature\",\"properties\":{\"id\":\"o1\",\"object_type\":\"oks_connection_point\",\"flow_tph\":5},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.62,55.70,150.0]}}";
        IngestResult r = ingest(fc(SRC, pt));
        assertEquals(1, r.getOksConnectionPoints().size());
        assertEquals(0, r.getReport().errorCount());
    }

    // ------------------------------------------------------------------ конкурсный набор — контрольные числа из аудита

    @Test
    @DisplayName("QA-M1-9: конкурсный набор — 29 труб, 9 камер, 17 ОКС, 88 ограничений, 0 ошибок (03-dataset-audit)")
    void contestDatasetCounts() throws Exception {
        Path p = ru.heatnet.ContestDatasetPaths.require();
        assertTrue(Files.isRegularFile(p), "конкурсный dataset не найден");
        IngestResult r;
        try (InputStream in = Files.newInputStream(p)) {
            r = ingestService.ingest(in);
        }
        assertEquals(29, r.getExistingNetwork().getSegments().size());
        assertEquals(9, r.getExistingNetwork().getChambers().size());
        assertEquals(17, r.getOksConnectionPoints().size());
        assertEquals(88, r.getRestrictionsWgs84().size());
        assertEquals(0, r.getReport().errorCount(), "конкурсный набор не должен давать ошибок ingest");
        double total = 0;
        for (ru.heatnet.ingest.OksConnectionPoint o : r.getOksConnectionPoints()) {
            total += o.getFlowTph();
        }
        assertEquals(488.72, total, 0.01, "суммарный расход ОКС по аудиту 488,72 т/ч");
        assertFalse(r.getReport().hasErrors());
    }

    private static String abbreviate(String s) {
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }
}
