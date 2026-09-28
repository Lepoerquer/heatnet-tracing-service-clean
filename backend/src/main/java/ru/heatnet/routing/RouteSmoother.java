package ru.heatnet.routing;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;

import ru.heatnet.rules.SpatialConstraintEngine;

/**
 * String-pulling: спрямление с проверкой isSegmentBlocked на каждом отрезке.
 */
final class RouteSmoother {

    private final SpatialConstraintEngine engine;
    private final int dn;
    private final Geometry keepOut;

    RouteSmoother(SpatialConstraintEngine engine, int dn) {
        this(engine, dn, null);
    }

    /**
     * @param keepOut §2.2: свой полигон ОКС. Спрямление (string-pulling) не должно «срезать»
     *                через него законный подход «двор -> ворота -> точка», превращая его в прямую
     *                хорду, если только эта хорда не является тем самым законным финальным
     *                участком — эту разницу спрямление отличить не может, поэтому просто не
     *                предлагает шорткаты через keepOut вообще (сам законный финальный отрезок и
     *                так уже последний в пути и не нуждается в спрямлении).
     */
    RouteSmoother(SpatialConstraintEngine engine, int dn, Geometry keepOut) {
        this.engine = engine;
        this.dn = dn;
        this.keepOut = keepOut;
    }

    LineString smooth(List<Coordinate> path) {
        if (path == null || path.size() < 2) {
            return RoutingGeometry.polyline(path);
        }
        List<Coordinate> work = new ArrayList<>(path);
        int anchor = 0;
        List<Coordinate> result = new ArrayList<>();
        result.add(work.get(0));
        // §2.2: string-pulling не смеет пересекать keepOut ни при одном шорткате — БЕЗ исключения
        // для хорды к самому концу пути. Это безопасно: если такой шорткат отвергнут, цикл ниже
        // всё равно гарантированно включит «следующую по порядку» точку исходного пути (см.
        // инициализацию `farthest = anchor + 1` перед циклом) — то есть законные «ворота»,
        // которые построил граф видимости или грид, не потеряются, даже если разворот к ним
        // не найден как «более длинный» шорткат. Раньше отсутствие этой проверки давало
        // string-pulling молча срезать «двор -> ворота -> точка» напрямую через здание всякий
        // раз, когда движок (не знающий о keepOut) не находил в этой прямой других препятствий.
        while (anchor < work.size() - 1) {
            int farthest = anchor + 1;
            Coordinate prev = result.size() >= 2 ? result.get(result.size() - 2) : null;
            Coordinate at = work.get(anchor);
            for (int candidate = work.size() - 1; candidate > anchor; candidate--) {
                if (!visible(at, work.get(candidate))) {
                    continue;
                }
                if (prev != null
                        && RoutingGeometry.deviationDeg(prev, at, work.get(candidate))
                        > TurnRepair.MAX_TURN_DEG + 1e-6) {
                    continue;
                }
                farthest = candidate;
                break;
            }
            if (farthest != anchor) {
                result.add(work.get(farthest));
            }
            anchor = farthest;
        }
        return RoutingGeometry.polyline(result);
    }

    private boolean visible(Coordinate from, Coordinate to) {
        if (from.distance(to) < 1e-6) {
            return false;
        }
        LineString segment = RoutingGeometry.line(from, to);
        if (engine.isSegmentBlocked(segment, dn)) {
            return false;
        }
        return !crossesKeepOut(segment);
    }

    private boolean crossesKeepOut(LineString segment) {
        if (keepOut == null || keepOut.isEmpty()) {
            return false;
        }
        try {
            Geometry inter = segment.intersection(keepOut);
            return inter != null && !inter.isEmpty() && inter.getLength() > 1e-6;
        } catch (RuntimeException ex) {
            return true;
        }
    }
}
