package ru.heatnet.rules;

import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.geo.ProjectionService;

import org.locationtech.jts.geom.GeometryFactory;

/** Общие зависимости M2 для тестов. */
public final class TestRules {

    private static final ProjectionService PROJECTION = new ProjectionService();
    private static final GeometryFactory UTM_FACTORY = PROJECTION.utmFactory();

    private TestRules() {
    }

    public static ReferenceData reference() {
        return TestReference.get();
    }

    public static ProjectionService projection() {
        return PROJECTION;
    }

    public static GeometryFactory utmFactory() {
        return UTM_FACTORY;
    }

    public static RestrictionEngineFactory engineFactory() {
        return new RestrictionEngineFactory(
                new IngestRestrictionsLoader(PROJECTION),
                reference(),
                PROJECTION);
    }
}
