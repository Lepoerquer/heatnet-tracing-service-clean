package ru.heatnet.routing;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;

import ru.heatnet.rules.SpatialConstraintEngine;

/**
 * M3. Устранение изломов трассы больше допустимого угла поворота.
 *
 * <p>§2.1 обновлённого приложения: «Угол поворота определяется как изменение направления
 * относительно продолжения предыдущего участка: 0° соответствует движению по прямой.
 * <b>Допускается произвольный угол поворота до 90° включительно.</b> Трасса не должна
 * содержать необоснованных мелких изломов, зигзагов и ступенчатых фрагментов.»</p>
 *
 * <p>Основной источник изломов — стыки, где маршрут приходит в точку на границе препятствия,
 * а затем разворачивается: заход к точке подключения внутри своего полигона (§2.2) и выход
 * из зоны, накрывающей точку врезки. Обе склейки дают вершину с отклонением до 180°.</p>
 *
 * <p>Починка — только <b>удаление</b> нарушающей вершины при условии, что спрямление не
 * блокируется движком ограничений. Новых точек не добавляется, концы не меняются, длина не
 * растёт — значит ни одно пространственное правило не может быть нарушено спрямлением,
 * которое движок признал свободным.</p>
 */
public final class TurnRepair {

    /** Допустимый угол поворота по §2.1, градусы. */
    public static final double MAX_TURN_DEG = 90.0;

    private TurnRepair() {
    }

    /**
     * Убирает вершины с отклонением больше {@code maxTurnDeg}, если спрямление свободно.
     *
     * @return исправленная линия; та же линия, если чинить нечего или спрямление невозможно
     */
    public static LineString repair(LineString path, SpatialConstraintEngine engine, int dn, double maxTurnDeg) {
        return repair(path, engine, dn, maxTurnDeg, null);
    }

    /**
     * @param keepOut свой полигон ОКС: спрямление не должно прорезать его насквозь.
     *                 Финальный короткий заход добавляется отдельно, не этой починкой.
     */
    public static LineString repair(LineString path, SpatialConstraintEngine engine, int dn,
                                    double maxTurnDeg, Geometry keepOut) {
        if (path == null || path.getNumPoints() < 3) {
            return path;
        }
        List<Coordinate> pts = new ArrayList<>();
        for (Coordinate c : path.getCoordinates()) {
            pts.add(RoutingGeometry.copy(c));
        }
        boolean changed = true;
        int guard = 0;
        while (changed && pts.size() > 2 && guard++ < pts.size() * 4) {
            changed = false;
            for (int i = 1; i < pts.size() - 1; i++) {
                if (deviationDeg(pts.get(i - 1), pts.get(i), pts.get(i + 1)) <= maxTurnDeg + 1e-6) {
                    continue;
                }
                LineString shortcut = RoutingGeometry.line(pts.get(i - 1), pts.get(i + 1));
                if (shortcut.getLength() < 1e-9 || engine.isSegmentBlocked(shortcut, dn)
                        || crossesKeepOut(shortcut, keepOut)) {
                    continue;
                }
                pts.remove(i);
                changed = true;
                break;
            }
        }
        return pts.size() < 2 ? path : RoutingGeometry.polyline(pts);
    }

    public static LineString repair(LineString path, SpatialConstraintEngine engine, int dn) {
        return repair(path, engine, dn, MAX_TURN_DEG, null);
    }

    public static LineString repair(LineString path, SpatialConstraintEngine engine, int dn, Geometry keepOut) {
        return repair(path, engine, dn, MAX_TURN_DEG, keepOut);
    }

    private static boolean crossesKeepOut(LineString shortcut, Geometry keepOut) {
        if (keepOut == null || keepOut.isEmpty() || shortcut == null) {
            return false;
        }
        try {
            Geometry inter = shortcut.intersection(keepOut);
            return inter != null && !inter.isEmpty() && inter.getLength() > 0.5;
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** Остались ли изломы больше допустимого — для диагностики и тестов. */
    public static int violations(LineString path, double maxTurnDeg) {
        if (path == null || path.getNumPoints() < 3) {
            return 0;
        }
        int count = 0;
        for (int i = 1; i < path.getNumPoints() - 1; i++) {
            if (deviationDeg(path.getCoordinateN(i - 1), path.getCoordinateN(i),
                    path.getCoordinateN(i + 1)) > maxTurnDeg + 1e-6) {
                count++;
            }
        }
        return count;
    }

    /**
     * Отклонение от продолжения предыдущего участка, градусы: 0° — прямо, 180° — разворот.
     * Та же формула, что в {@code TopologyValidator.deviationDeg}.
     */
    public static double deviationDeg(Coordinate prev, Coordinate at, Coordinate next) {
        double ax = at.x - prev.x;
        double ay = at.y - prev.y;
        double bx = next.x - at.x;
        double by = next.y - at.y;
        double la = Math.hypot(ax, ay);
        double lb = Math.hypot(bx, by);
        if (la < 1e-9 || lb < 1e-9) {
            return 0.0;
        }
        double cos = (ax * bx + ay * by) / (la * lb);
        cos = Math.max(-1.0, Math.min(1.0, cos));
        return Math.toDegrees(Math.acos(cos));
    }
}
