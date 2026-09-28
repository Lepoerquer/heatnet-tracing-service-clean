package ru.heatnet.depth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.reference.DepthRules;
import ru.heatnet.cost.DepthCoefficient;

/**
 * Профиль глубины. Контрольные числа — вручную из config/depth-rules.yaml и табл. 4.2 (DN200, высота 0,315 м).
 */
class DepthProfileSolverTest {

    private final GeometryFactory gf = new GeometryFactory();
    private final DepthProfileSolver solver = new DepthProfileSolver(
            TestReference.get().getDepth(), TestReference.get().getGabarits());

    @Test
    @DisplayName("газ поперёк: проход выше, площадка 4 м, уклон ровно 0,10")
    void gasCrossingGoesAboveWithPlateau() {
        // AUDIT-24.09 (Claude): зазор — по актуальной табл. 2 приложения (газ: вертикальный просвет 0,2 м);
        // «0,7 м» из протокола 16.09 в актуальном приложении нет (0,7 м — минимальная глубина, Разъяснение №16).
        // Газ: верх 2,8 м, высота 0,4 м. Зазор 0,2 м.
        // Выше: низ нашей трубы = 2,8 − 0,2 = 2,6; верх = 2,6 − 0,315 = 2,285 м, Kгл = 1.
        // Ниже: верх = 2,8 + 0,4 + 0,2 = 3,4 м, Kгл = 1 + 0,1·0,4 = 1,04. Дешевле пройти выше.
        // Спуск |3 − 2,285| / 0,10 = 7,15 м с каждой стороны площадки.
        LineString route = line(0, 0, 100, 0);
        LineString gas = line(50, -5, 50, 5);
        List<DepthSpan> spans = solver.solve(route, 200,
                Collections.singletonList(DepthObstacle.utility("gas1", "gas_pipeline", gas, 0)));
        assertNotNull(spans);

        DepthSpan plateau = plateauNear(spans, 2.285);
        assertNotNull(plateau, "нет площадки постоянной глубины");
        assertEquals(4.0, plateau.lengthM(), 0.05);
        assertSlope(spans);
        assertTrue(spans.get(0).getDepthStartM() > 2.9);
        assertTrue(spans.get(spans.size() - 1).getDepthEndM() > 2.9);
    }

    @Test
    @DisplayName("два близких газопровода: один транзитный коридор, без возврата на 3 м")
    void closeCrossingsStayInOneCorridor() {
        LineString route = line(0, 0, 100, 0);
        List<DepthObstacle> gases = Arrays.asList(
                DepthObstacle.utility("g1", "gas_pipeline", line(40, -5, 40, 5), 0),
                DepthObstacle.utility("g2", "gas_pipeline", line(50, -5, 50, 5), 0));
        List<DepthSpan> spans = solver.solve(route, 200, gases);
        assertNotNull(spans);
        boolean returned = false;
        for (DepthSpan span : spans) {
            double mid = (span.getChainageStartM() + span.getChainageEndM()) / 2.0;
            if (mid > 39 && mid < 51 && span.getDepthStartM() > 2.9 && span.getDepthEndM() > 2.9) {
                returned = true;
            }
        }
        assertTrue(!returned, "между близкими пересечениями трасса вынырнула на 3 м");
        assertSlope(spans);
    }

    @Test
    @DisplayName("только дорога: обычные 3,0 м уже глубже 1,0 м, профиль не меняется")
    void roadDoesNotLeaveNormalDepth() {
        LineString route = line(0, 0, 100, 0);
        org.locationtech.jts.geom.Polygon road = gf.createPolygon(new Coordinate[] {
                new Coordinate(40, -10),
                new Coordinate(60, -10),
                new Coordinate(60, 10),
                new Coordinate(40, 10),
                new Coordinate(40, -10)
        });
        List<DepthSpan> spans = solver.solve(route, 200,
                Collections.singletonList(DepthObstacle.surface("road1", "road", road, 1.0)));
        assertNull(spans);
    }

    @Test
    @DisplayName("мелкое препятствие: проход сверху невозможен, уходим ниже 3 м, Kгл = 1,03")
    void shallowObstacleForcesBelow() {
        // AUDIT-24.09 (Claude): зазор 0,2 м (табл. 2), верх препятствия поднят до 1,1 м, чтобы сохранить смысл теста.
        // Верх препятствия 1,1 м, высота 2,0 м, зазор 0,2 м, наша высота DN200 = 0,315 м.
        // Выше: 1,1 − 0,2 − 0,315 = 0,585 < 0,7 (мин. глубина) — нельзя.
        // Ниже: 1,1 + 2,0 + 0,2 = 3,3 м. Kгл = 1 + 0,10·(3,3 − 3) = 1,03.
        DepthRules rules = customGasRules();
        DepthProfileSolver custom = new DepthProfileSolver(rules, TestReference.get().getGabarits());
        List<DepthSpan> spans = custom.solve(line(0, 0, 80, 0), 200,
                Collections.singletonList(DepthObstacle.utility("box", "gas_pipeline", line(40, -5, 40, 5), 0)));
        assertNotNull(spans);
        DepthSpan plateau = plateauNear(spans, 3.3);
        assertNotNull(plateau);
        assertEquals(4.0, plateau.lengthM(), 0.05);
        DepthCoefficient k = new DepthCoefficient(rules);
        assertEquals(1.03, k.of(3.3), 1e-9);
        assertEquals(1.015, k.average(3.0, 3.3), 1e-9);
        assertSlope(spans, rules.getMaxSlopeMPerM());
    }

    private DepthRules customGasRules() {
        DepthRules.Utility gas = new DepthRules.Utility(0.4, 2.0, false, 1.1, 0.2);
        Map<String, DepthRules.Utility> utilities = new LinkedHashMap<>();
        utilities.put("gas_pipeline", gas);
        return new DepthRules(3.0, 0.7, 0.7, null, 0.10, 4.0, 3.0, 0.10,
                utilities, Collections.<String, DepthRules.SurfaceCrossing>emptyMap());
    }

    private LineString line(double x1, double y1, double x2, double y2) {
        return gf.createLineString(new Coordinate[] {
                new Coordinate(x1, y1),
                new Coordinate(x2, y2)
        });
    }

    private static DepthSpan plateauNear(List<DepthSpan> spans, double depth) {
        DepthSpan best = null;
        for (DepthSpan span : spans) {
            if (Math.abs(span.getDepthStartM() - span.getDepthEndM()) > 1e-6) {
                continue;
            }
            if (Math.abs(span.getDepthStartM() - depth) > 0.05) {
                continue;
            }
            if (best == null || span.lengthM() > best.lengthM()) {
                best = span;
            }
        }
        return best;
    }

    private static void assertSlope(List<DepthSpan> spans) {
        assertSlope(spans, 0.10);
    }

    private static void assertSlope(List<DepthSpan> spans, double maxSlope) {
        for (DepthSpan span : spans) {
            double run = span.lengthM();
            if (run <= 1e-6) {
                continue;
            }
            double slope = Math.abs(span.getDepthEndM() - span.getDepthStartM()) / run;
            assertTrue(slope <= maxSlope + 1e-6, "уклон " + slope + " на " + span.getChainageStartM());
        }
    }
}
