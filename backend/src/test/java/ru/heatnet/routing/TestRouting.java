package ru.heatnet.routing;

import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.SpatialConstraintBundle;

import org.locationtech.jts.geom.GeometryFactory;

/** Общие зависимости M3 для тестов. */
public final class TestRouting {

    private static final ProjectionService PROJECTION = new ProjectionService();
    private static final GeometryFactory UTM_FACTORY = PROJECTION.utmFactory();

    private TestRouting() {
    }

    public static ReferenceData reference() {
        return TestReference.get();
    }

    public static GeometryFactory utmFactory() {
        return UTM_FACTORY;
    }

    public static RestrictionEngineFactory engineFactory() {
        return new RestrictionEngineFactory(
                new ru.heatnet.rules.IngestRestrictionsLoader(PROJECTION),
                reference(),
                PROJECTION);
    }

    public static RouteFinderFactory routeFinderFactory() {
        return new RouteFinderFactory(engineFactory(), reference());
    }

    public static RouteFinder routeFinder(SpatialConstraintBundle bundle, int dn) {
        return routeFinderFactory().create(bundle, dn);
    }
}
