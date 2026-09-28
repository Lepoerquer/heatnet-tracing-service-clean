package ru.heatnet.routing;

import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;

import ru.heatnet.ingest.IngestResult;

/**
 * M3: трёхшаговый цикл DN↔буфер (шаги 1–2 — до M5, шаг 3 — после M5).
 */
@Service
public class RoutingPipeline {

    private final RouteFinderFactory routeFinderFactory;
    private final RouteM5Verification m5Verification;

    public RoutingPipeline(RouteFinderFactory routeFinderFactory, RouteM5Verification m5Verification) {
        this.routeFinderFactory = routeFinderFactory;
        this.m5Verification = m5Verification;
    }

    /** Полный набор ограничений с буферами по {@code dn} (кэшируется на запрос). */
    public ru.heatnet.rules.SpatialConstraintBundle bundle(IngestResult ingest, int dn) {
        return routeFinderFactory.bundle(ingest, dn);
    }

    /** Шаг 1: ввод к ОКС — буфер по DN_leaf = minDN(flow_tph этого ОКС). */
    public RouteResult findLeafRoute(IngestResult ingest, Point fromUtm, Point toUtm, double oksFlowTph) {
        RouteFinder finder = routeFinderFactory.createForLeaf(ingest, oksFlowTph, fromUtm, toUtm);
        int dnLeaf = routeFinderFactory.leafDn(oksFlowTph);
        return finder.findRoute(fromUtm, toUtm, dnLeaf);
    }

    /** Шаг 2: магистраль — буфер по сумме расходов фиксированного множества ОКС прогона. */
    public RouteResult findMagistralRoute(IngestResult ingest, Point fromUtm, Point toUtm, double totalFlowTph) {
        RouteFinder finder = routeFinderFactory.createForMagistral(ingest, totalFlowTph, fromUtm, toUtm);
        int dnMag = routeFinderFactory.magistralDn(totalFlowTph);
        return finder.findRoute(fromUtm, toUtm, dnMag);
    }

    /**
     * Шаг 3: после M5 — проверка actualDn и не более одной перетрассировки.
     */
    public RouteResult verifyAfterM5(IngestResult ingest,
                                     Point fromUtm,
                                     Point toUtm,
                                     RouteResult initial,
                                     int routingDn,
                                     int actualDn) {
        return m5Verification.verifyAndRerouteOnce(ingest, fromUtm, toUtm, initial, routingDn, actualDn);
    }
}
