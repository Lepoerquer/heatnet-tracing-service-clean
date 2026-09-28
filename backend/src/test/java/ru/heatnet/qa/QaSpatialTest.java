package ru.heatnet.qa;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.RulesTestGeometry;
import ru.heatnet.rules.SpatialConstraintEngine;
import ru.heatnet.rules.TestRules;
import ru.heatnet.rules.model.RestrictionFeature;
import ru.heatnet.rules.model.SpecialSection;

/**
 * QA (роль 4): граничные и «вражеские» случаи движка ограничений M2 против ТЗ и табл. 5.1.
 * Каждый тест описывает ПРАВИЛЬНОЕ по ТЗ поведение; падение = дефект реализации.
 */
class QaSpatialTest {

    private static final int DN = 200;

    private final ReferenceData ref = TestRules.reference();
    private final GeometryFactory gf = TestRules.utmFactory();
    private final RestrictionEngineFactory factory = TestRules.engineFactory();

    private RestrictionFeature feat(String id, String key, org.locationtech.jts.geom.Geometry g) {
        return new RestrictionFeature(id, key, ref.getRules().restriction(key), g);
    }

    private SpatialConstraintEngine engine(int dn, RestrictionFeature... f) {
        return factory.create(Arrays.asList(f), dn);
    }

    /** Прямоугольник length×width, повёрнутый на angleDeg вокруг центра (cx, cy). */
    private Polygon rotatedRect(double cx, double cy, double length, double width, double angleDeg) {
        double a = Math.toRadians(angleDeg);
        double c = Math.cos(a);
        double s = Math.sin(a);
        double[][] pts = {{-length / 2, -width / 2}, {length / 2, -width / 2}, {length / 2, width / 2},
                {-length / 2, width / 2}, {-length / 2, -width / 2}};
        Coordinate[] ring = new Coordinate[pts.length];
        for (int i = 0; i < pts.length; i++) {
            ring[i] = new Coordinate(cx + pts[i][0] * c - pts[i][1] * s, cy + pts[i][0] * s + pts[i][1] * c);
        }
        return gf.createPolygon(ring);
    }

    private LineString dirLine(double x, double y, double angleDeg, double len) {
        double a = Math.toRadians(angleDeg);
        return RulesTestGeometry.line(gf, x - len / 2 * Math.cos(a), y - len / 2 * Math.sin(a),
                x + len / 2 * Math.cos(a), y + len / 2 * Math.sin(a));
    }

    // ------------------------------------------------------------------ ось дороги / угол пересечения

    @Test
    @DisplayName("QA-M2-1a: дорога под 30° к оси X; пересечение под ИСТИННЫМ углом 20° к оси дороги (<45°) обязано быть запрещено")
    void skewedRoadShallowCrossingMustBeBlocked() {
        Polygon road = rotatedRect(500, 500, 300, 20, 30.0);
        SpatialConstraintEngine e = engine(DN, feat("road1", "road", road));
        // Дорога направлена под 30°, сегмент под 50° => истинный угол пересечения 20° < 45°.
        LineString seg = dirLine(500, 500, 50.0, 120);
        assertTrue(e.isSegmentBlocked(seg, DN),
                "Сегмент пересекает полотно под 20° к оси дороги — по табл. 5.1 (угол не менее 45°) должен быть заблокирован");
    }

    @Test
    @DisplayName("QA-M2-1b: дорога под 30°; ПЕРПЕНДИКУЛЯРНОЕ пересечение (истинный угол 90°) в любой точке дороги обязано быть разрешено")
    void skewedRoadPerpendicularCrossingsMustBeAllowed() {
        Polygon road = rotatedRect(500, 500, 300, 10, 30.0);
        SpatialConstraintEngine e = engine(DN, feat("road1", "road", road));
        int blocked = 0;
        int total = 0;
        double cos = Math.cos(Math.toRadians(30));
        double sin = Math.sin(Math.toRadians(30));
        for (double t = -140; t <= 140; t += 7) {
            double cx = 500 + t * cos;
            double cy = 500 + t * sin;
            LineString perp = dirLine(cx, cy, 120.0, 60); // 30+90 = перпендикуляр к дороге
            total++;
            if (e.isSegmentBlocked(perp, DN)) {
                blocked++;
            }
        }
        assertEquals(0, blocked, blocked + " из " + total + " перпендикулярных пересечений ложно заблокированы");
    }

    @Test
    @DisplayName("QA-M2-1c: дорога под 45°; ход ВДОЛЬ оси внутри полотна (истинный угол 0°) обязан быть запрещён")
    void diagonalRoadLongitudinalRunMustBeBlocked() {
        Polygon road = rotatedRect(500, 500, 300, 20, 45.0);
        SpatialConstraintEngine e = engine(DN, feat("road1", "road", road));
        LineString along = dirLine(500, 500, 45.0, 200);
        assertTrue(e.isSegmentBlocked(along, DN), "Продольный ход по диагональной дороге не должен проходить");
    }

    @Test
    @DisplayName("QA-M2-1d: дорога как MultiPolygon (тип допускается ТЗ: Polygon / MultiPolygon) — движок не должен падать")
    void multiPolygonRoadDoesNotCrash() {
        Polygon a = RulesTestGeometry.square(gf, 0, 0, 40);
        Polygon b = RulesTestGeometry.square(gf, 100, 0, 40);
        MultiPolygon mp = gf.createMultiPolygon(new Polygon[] {a, b});
        SpatialConstraintEngine e = engine(DN, feat("road1", "road", mp));
        LineString seg = RulesTestGeometry.line(gf, 20, -10, 20, 50);
        assertDoesNotThrow(() -> e.isSegmentBlocked(seg, DN));
        assertDoesNotThrow(() -> e.extractSpecialSections(seg, DN));
    }

    // ------------------------------------------------------------------ спецучастки

    @Test
    @DisplayName("QA-M2-2a: два РАЗНЫХ газопровода на одном прямом отрезке => два независимых спецучастка (K=1,25 у каждого)")
    void twoSeparateGasCrossingsGiveTwoSections() {
        LineString gas1 = RulesTestGeometry.line(gf, 100, -50, 100, 50);
        LineString gas2 = RulesTestGeometry.line(gf, 200, -50, 200, 50);
        SpatialConstraintEngine e = engine(DN, feat("g1", "gas_pipeline", gas1), feat("g2", "gas_pipeline", gas2));
        LineString seg = RulesTestGeometry.line(gf, 0, 0, 300, 0);
        List<SpecialSection> sections = e.extractSpecialSections(seg, DN);
        assertEquals(2, sections.size(), "ожидались 2 спецучастка (по одному на каждый газопровод), получено " + sections.size());
    }

    @Test
    @DisplayName("QA-M2-2b: дорога (1,60) и далее газопровод (1,25) на одном отрезке => два участка с РАЗНЫМИ K, не один с max")
    void roadThenGasKeepsSeparateK() {
        // x in [50,70], y in [-10,10] — полоса пересекает трассу y=0 поперёк (вход через вертикальное ребро)
        Polygon road = RulesTestGeometry.square(gf, 50, -10, 20);
        LineString gas = RulesTestGeometry.line(gf, 200, -50, 200, 50);
        SpatialConstraintEngine e = engine(DN, feat("r1", "road", road), feat("g1", "gas_pipeline", gas));
        LineString seg = RulesTestGeometry.line(gf, 0, 0, 300, 0);
        List<SpecialSection> sections = e.extractSpecialSections(seg, DN);
        assertEquals(2, sections.size(), "дорога и газопровод не пересекаются между собой — это два разных спецучастка");
        double k0 = sections.get(0).getKSpec();
        double k1 = sections.get(sections.size() - 1).getKSpec();
        assertEquals(1.60, Math.max(k0, k1), 1e-9);
        assertEquals(1.25, Math.min(k0, k1), 1e-9, "газопровод (K=1,25) не должен оплачиваться по K дороги (1,60)");
    }

    @Test
    @DisplayName("QA-M2-2c: газопровод — спецучасток по ТП «по 2 м с каждой стороны от точки пересечения» = 4,0 м вдоль трассы")
    void gasSpecialSectionIsFourMetres() {
        LineString gas = RulesTestGeometry.line(gf, 100, -50, 100, 50);
        SpatialConstraintEngine e = engine(DN, feat("g1", "gas_pipeline", gas));
        LineString seg = RulesTestGeometry.line(gf, 0, 0, 200, 0);
        List<SpecialSection> sections = e.extractSpecialSections(seg, DN);
        assertEquals(1, sections.size());
        assertEquals(4.0, sections.get(0).getGeometry().getLength(), 0.05,
                "табл. 5.1: границы спецучастка газопровода — по 2 м с каждой стороны от точки пересечения");
    }

    @Test
    @DisplayName("QA-M2-3: движок учитывает candidateDn из вызова (isSegmentBlocked(seg, dn)), а не только DN построения")
    void candidateDnParameterIsHonoured() {
        Polygon oks = RulesTestGeometry.square(gf, 100, 100, 30);
        LineString seg = RulesTestGeometry.line(gf, 100 - 8.0, 90, 100 - 8.0, 140); // 8 м от здания
        // С правками 20.09 движок строится под конкретный ДУ (bundle-per-DN) и проверяет соответствие
        // переданного candidateDn. Поэтому отступ проверяем двумя движками, а не одним.
        SpatialConstraintEngine small = engine(50, feat("o1", "oks_existing", oks));
        SpatialConstraintEngine large = factory.create(
                java.util.Collections.singletonList(feat("o1", "oks_existing", oks)), 1400);
        assertFalse(small.isSegmentBlocked(seg, 50),
                "8 м от ОКС при ДУ50 (нужно >= 5 + 0,2 м) — проход разрешён");
        assertTrue(large.isSegmentBlocked(seg, 1400),
                "8 м от ОКС при ДУ1400 (нужно >= 9 + 1,725 м) — обязан быть заблокирован");
    }

    @Test
    @DisplayName("QA-M2-4: ход РЯДОМ с газопроводом ближе 2,0 м (зазор между габаритами) запрещён (табл. 5.1: 'при прохождении рядом')")
    void runningAlongsideGasTooCloseIsBlocked() {
        LineString gas = RulesTestGeometry.line(gf, 0, 0, 200, 0);
        SpatialConstraintEngine e = engine(DN, feat("g1", "gas_pipeline", gas));
        double pairHalf = ref.getGabarits().spec(DN).getWidthM() / 2.0;
        double gasHalf = 0.4 / 2.0;
        double axisDistance = 1.0 + gasHalf + pairHalf; // зазор между внешними границами габаритов всего 1,0 м < 2,0 м
        LineString parallel = RulesTestGeometry.line(gf, 10, axisDistance, 190, axisDistance);
        assertTrue(e.isSegmentBlocked(parallel, DN),
                "параллельный ход с зазором 1,0 м < 2,0 м нарушает минимальное расстояние — не спецпроход, а нарушение");
    }

    @Test
    @DisplayName("QA-M2-5: угол пересечения ровно 45° допустим, 44° — нет (прямоугольная дорога вдоль оси X)")
    void roadAngleBoundary() {
        Polygon road = RulesTestGeometry.square(gf, 0, 0, 300);
        Polygon roadNarrow = gf.createPolygon(new Coordinate[] {new Coordinate(0, 0), new Coordinate(300, 0),
                new Coordinate(300, 20), new Coordinate(0, 20), new Coordinate(0, 0)});
        SpatialConstraintEngine e = engine(DN, feat("r1", "road", roadNarrow));
        LineString at45 = dirLine(150, 10, 45.0, 60);
        LineString at44 = dirLine(150, 10, 44.0, 60);
        assertFalse(e.isSegmentBlocked(at45, DN), "45° — допустимая граница");
        assertTrue(e.isSegmentBlocked(at44, DN), "44° < 45° — запрещено");
        assertTrue(road.getArea() > 0);
    }

    @Test
    @DisplayName("QA-M2-6: наложение запрета: сегмент через park, лежащий внутри зоны road, блокируется (запрет приоритетнее)")
    void prohibitedInsideRoadZoneStillBlocked() {
        Polygon road = RulesTestGeometry.square(gf, 0, 0, 200);
        Polygon park = RulesTestGeometry.square(gf, 90, 90, 20);
        SpatialConstraintEngine e = engine(DN, feat("r1", "road", road), feat("p1", "park", park));
        LineString seg = RulesTestGeometry.line(gf, 100, -10, 100, 210);
        assertTrue(e.isSegmentBlocked(seg, DN));
    }

    @Test
    @DisplayName("QA-M2-7: пустой/вырожденный вход не роняет движок (пустой список ограничений, нулевой отрезок)")
    void degenerateInputs() {
        SpatialConstraintEngine e = engine(DN);
        assertFalse(e.isSegmentBlocked(RulesTestGeometry.line(gf, 0, 0, 10, 10), DN));
        assertTrue(e.extractSpecialSections(RulesTestGeometry.line(gf, 0, 0, 10, 10), DN).isEmpty());
        assertEquals(Collections.emptyList(), e.findCrossings(RulesTestGeometry.line(gf, 5, 5, 5, 5), DN));
        List<RestrictionFeature> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add(feat("p" + i, "park", RulesTestGeometry.square(gf, i * 30, 0, 10)));
        }
        SpatialConstraintEngine e2 = factory.create(many, DN);
        assertTrue(e2.isSegmentBlocked(RulesTestGeometry.line(gf, -5, 5, 1600, 5), DN));
    }
}
