package ru.heatnet.routing;

import org.locationtech.jts.geom.Point;

/**
 * Поиск маршрута от точки врезки до точки подключения ОКС (M3).
 */
public interface RouteFinder {

    /**
     * @param fromUtm точка врезки, EPSG:32637
     * @param toUtm   oks_connection_point, EPSG:32637
     * @param dnHint  DN для буферов (листовой или магистральный — см. трёхшаговый цикл M3/M5)
     */
    RouteResult findRoute(Point fromUtm, Point toUtm, int dnHint);
}
