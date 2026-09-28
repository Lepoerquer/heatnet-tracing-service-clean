package ru.heatnet.routing;

import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;

import ru.heatnet.ingest.IngestResult;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.SpatialConstraintEngine;

/**
 * M3 шаг 3: после M5 проверка {@code isBlocked(seg, actual_DN)} и ровно одна попытка перетрассировки.
 */
@Service
public class RouteM5Verification {

    private final RestrictionEngineFactory engineFactory;
    private final RouteFinderFactory routeFinderFactory;

    public RouteM5Verification(RestrictionEngineFactory engineFactory, RouteFinderFactory routeFinderFactory) {
        this.engineFactory = engineFactory;
        this.routeFinderFactory = routeFinderFactory;
    }

    /**
     * Если маршрут, построенный с {@code routingDn}, нарушает отступы при {@code actualDn} —
     * одна перетрассировка с буферами actualDn. Иначе исходный результат.
     */
    public RouteResult verifyAndRerouteOnce(IngestResult ingest,
                                            Point fromUtm,
                                            Point toUtm,
                                            RouteResult initial,
                                            int routingDn,
                                            int actualDn) {
        if (initial == null || !initial.isFound()) {
            return initial == null ? RouteResult.notFound() : initial;
        }
        if (actualDn <= 0) {
            actualDn = routingDn;
        }
        if (!hasBlockedSegment(ingest, initial, actualDn, toUtm)) {
            return initial;
        }
        // AUDIT-24.09 (Claude): тот же маршрутизатор ввода, что и на шаге 1 (с заходом §2.2), но с буферами
        // actualDn. Раньше здесь был только прежний маршрутизатор без точки цели.
        RouteFinder finder = routeFinderFactory.createForDn(ingest, actualDn, toUtm);
        RouteResult retry = finder.findRoute(fromUtm, toUtm, actualDn);
        return retry.isFound() ? retry : RouteResult.notFound();
    }

    /** true, если хотя бы один сегмент полилинии заблокирован при actualDn. */
    public boolean hasBlockedSegment(IngestResult ingest, RouteResult route, int actualDn) {
        return hasBlockedSegment(ingest, route, actualDn, null);
    }

    public boolean hasBlockedSegment(IngestResult ingest, RouteResult route, int actualDn, Point toUtm) {
        if (route == null || !route.isFound() || route.getPathUtm() == null) {
            return false;
        }
        SpatialConstraintEngine engine = engineFactory.createBundleForTarget(ingest, actualDn, toUtm).getEngine();
        LineString path = route.getPathUtm();
        for (int i = 0; i < path.getNumPoints() - 1; i++) {
            LineString seg = RoutingGeometry.line(
                    path.getCoordinateN(i),
                    path.getCoordinateN(i + 1));
            if (engine.isSegmentBlocked(seg, actualDn)) {
                return true;
            }
        }
        return false;
    }
}
