package ru.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;

import ru.heatnet.calc.reference.RulesConfig;

/** Вспомогательные расчёты стоимости для тестов K_угол. */
final class EdgeCostCalculatorTest {

    private EdgeCostCalculatorTest() {
    }

    static double edgeCostWithTurn(RulesConfig rules, double px, double py, double nx, double ny) {
        Coordinate prev = new Coordinate(px, py);
        Coordinate from = new Coordinate(100.0, 100.0);
        Coordinate to = new Coordinate(nx, ny);
        double length = from.distance(to);
        double kAngle = 1.0;
        double turnDeg = RoutingGeometry.turnAngleDeg(prev, from, to);
        if (!RoutingGeometry.isStandardTurn(turnDeg, rules)) {
            kAngle = rules.getKAngle();
        }
        return length * kAngle;
    }
}
