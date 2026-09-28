package ru.heatnet.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;

import ru.heatnet.calc.model.ExistingChamber;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingSegment;
import ru.heatnet.geo.GeoUtils;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestReport;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.ingest.RawFeature;

/** Синтетическая сеть для тестов M4. */
final class TestNetworkFixtures {

    private static final ProjectionService PROJECTION = new ProjectionService();

    private TestNetworkFixtures() {
    }

    static ProjectionService projection() {
        return PROJECTION;
    }

    /**
     * Простая магистраль: source —(net_main)— ch_main —(net_branch)— дальний конец.
     * ОКС расположены рядом с камерой и на ветке.
     */
    static IngestResult simpleMagistral() {
        Coordinate srcWgs = new Coordinate(37.62, 55.70);
        Coordinate chWgs = new Coordinate(37.6205, 55.70);
        Coordinate branchEndWgs = new Coordinate(37.6215, 55.70);
        Coordinate oksNearWgs = new Coordinate(37.6206, 55.7003);
        Coordinate oksFarWgs = new Coordinate(37.6220, 55.7002);
        Coordinate oksSeparateWgs = new Coordinate(37.6190, 55.6990);

        List<RawFeature> features = new ArrayList<>();
        features.add(pointFeature("src", RawFeature.Kind.SOURCE, srcWgs));
        features.add(pointFeature("ch_main", RawFeature.Kind.HEAT_CHAMBER, chWgs));
        features.add(lineFeature("net_main", srcWgs, chWgs, 200, null));
        features.add(lineFeature("net_branch", chWgs, branchEndWgs, 150, null));
        features.add(pointFeature("oks_near", RawFeature.Kind.OKS_CONNECTION_POINT, oksNearWgs, 20.0));
        features.add(pointFeature("oks_far", RawFeature.Kind.OKS_CONNECTION_POINT, oksFarWgs, 25.0));
        features.add(pointFeature("oks_sep", RawFeature.Kind.OKS_CONNECTION_POINT, oksSeparateWgs, 15.0));

        IngestReport report = new IngestReport();
        ExistingNetwork network = new ru.heatnet.ingest.NetworkTreeBuilder(PROJECTION)
                .build(features, 15.0, report);

        List<OksConnectionPoint> oks = Arrays.asList(
                new OksConnectionPoint("oks_near", 20.0),
                new OksConnectionPoint("oks_far", 25.0),
                new OksConnectionPoint("oks_sep", 15.0));

        return new IngestResult(report, network, oks, Collections.<String, org.locationtech.jts.geom.Geometry>emptyMap(),
                features);
    }

    /** Три ОКС рядом с камерой (для совместного коллектора). */
    static IngestResult threeOksNearChamber() {
        Coordinate srcWgs = new Coordinate(37.63, 55.71);
        Coordinate chWgs = new Coordinate(37.6305, 55.71);
        // ≤10 м от ch2 (§2.4): ~0.00005° ≈ 5–8 м в UTM
        Coordinate oks1 = new Coordinate(37.63052, 55.71005);
        Coordinate oks2 = new Coordinate(37.63052, 55.70995);
        Coordinate oks3 = new Coordinate(37.63058, 55.71000);

        List<RawFeature> features = new ArrayList<>();
        features.add(pointFeature("src2", RawFeature.Kind.SOURCE, srcWgs));
        features.add(pointFeature("ch2", RawFeature.Kind.HEAT_CHAMBER, chWgs));
        features.add(lineFeature("net2", srcWgs, chWgs, 200, null));
        features.add(pointFeature("o1", RawFeature.Kind.OKS_CONNECTION_POINT, oks1, 10.0));
        features.add(pointFeature("o2", RawFeature.Kind.OKS_CONNECTION_POINT, oks2, 12.0));
        features.add(pointFeature("o3", RawFeature.Kind.OKS_CONNECTION_POINT, oks3, 8.0));

        IngestReport report = new IngestReport();
        ExistingNetwork network = new ru.heatnet.ingest.NetworkTreeBuilder(PROJECTION)
                .build(features, 15.0, report);

        List<OksConnectionPoint> oks = Arrays.asList(
                new OksConnectionPoint("o1", 10.0),
                new OksConnectionPoint("o2", 12.0),
                new OksConnectionPoint("o3", 8.0));

        return new IngestResult(report, network, oks, Collections.<String, org.locationtech.jts.geom.Geometry>emptyMap(),
                features);
    }

    private static RawFeature pointFeature(String id, RawFeature.Kind kind, Coordinate wgs) {
        return pointFeature(id, kind, wgs, null);
    }

    private static RawFeature pointFeature(String id, RawFeature.Kind kind, Coordinate wgs, Double flow) {
        java.util.Map<String, Object> props = new java.util.LinkedHashMap<>();
        if (flow != null) {
            props.put("flow_tph", flow);
        }
        return RawFeature.of(id, kind, props, GeoUtils.pointWgs84(wgs.x, wgs.y), null);
    }

    private static RawFeature lineFeature(String id, Coordinate a, Coordinate b, int diameter, Double flow) {
        java.util.Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("diameter", diameter);
        if (flow != null) {
            props.put("flow_tph", flow);
        }
        Coordinate[] line = new Coordinate[] {a, b};
        org.locationtech.jts.geom.LineString ls = GeoUtils.wgs84Factory().createLineString(line);
        return RawFeature.of(id, RawFeature.Kind.HEAT_NETWORK, props, ls, line);
    }
}
