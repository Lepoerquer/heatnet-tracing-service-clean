package ru.heatnet.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.rules.model.RestrictionFeature;

class BufferFactoryTest {

    private ReferenceData reference;
    private GeometryFactory gf;
    private BufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        reference = TestRules.reference();
        gf = TestRules.utmFactory();
        bufferFactory = new BufferFactory(reference, gf);
    }

    @Test
    @DisplayName("park: отступ 1,0 м + полуширина пары DN200 (0,44 м)")
    void parkBufferDn200() {
        Polygon park = RulesTestGeometry.square(gf, 0, 0, 20);
        RestrictionFeature feature = RulesTestGeometry.feature(reference, gf, "p1", "park", park);
        double minY = bufferFactory.buildBlockedZone(feature, 200).getEnvelopeInternal().getMinY();
        assertEquals(-1.44, minY, 0.15);
    }

    @Test
    @DisplayName("oks_existing: DN400 → 5 м, DN500 → 7 м")
    void oksOffsetByDn() {
        Polygon oks = RulesTestGeometry.square(gf, 100, 100, 30);
        RestrictionFeature feature = RulesTestGeometry.feature(reference, gf, "o1", "oks_existing", oks);
        double pair400 = reference.getGabarits().spec(400).getWidthM() / 2.0;
        double pair500 = reference.getGabarits().spec(500).getWidthM() / 2.0;
        double minX400 = bufferFactory.buildBlockedZone(feature, 400).getEnvelopeInternal().getMinX();
        double minX500 = bufferFactory.buildBlockedZone(feature, 500).getEnvelopeInternal().getMinX();
        assertEquals(100.0 - 5.0 - pair400, minX400, 0.2);
        assertEquals(100.0 - 7.0 - pair500, minX500, 0.2);
    }

    @Test
    @DisplayName("road: барьер = исходный полигон, спецзона = полигон + 3 м")
    void roadBarrierAndSpecialZone() {
        Polygon road = RulesTestGeometry.square(gf, 0, 0, 40);
        RestrictionFeature feature = RulesTestGeometry.feature(reference, gf, "r1", "road", road);
        assertEquals(road, bufferFactory.buildBlockedZone(feature, 200));
        assertEquals(46.0, bufferFactory.buildSpecialZone(feature).getEnvelopeInternal().getWidth(), 0.15);
    }
}
