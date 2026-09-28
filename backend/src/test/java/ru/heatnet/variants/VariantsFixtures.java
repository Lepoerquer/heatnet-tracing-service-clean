package ru.heatnet.variants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;

import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.cost.UnconnectedOks;
import ru.heatnet.geo.GeoUtils;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestReport;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.ingest.RawFeature;
import ru.heatnet.network.NetworkPlan;
import ru.heatnet.network.NetworkTreeLayout;

/** Фикстуры M7: синтетическая сеть и готовые планы без запуска тяжёлой маршрутизации. */
final class VariantsFixtures {

    static final ProjectionService PROJECTION = new ProjectionService();

    private VariantsFixtures() {
    }

    /** Источник → камера, плюс ветка и четыре точки подключения по двум краям площадки. */
    static IngestResult network() {
        Coordinate src = new Coordinate(37.6200, 55.7000);
        Coordinate ch = new Coordinate(37.6205, 55.7000);
        Coordinate end = new Coordinate(37.6230, 55.7000);

        List<RawFeature> features = new ArrayList<>();
        features.add(point("src", RawFeature.Kind.SOURCE, src, null));
        features.add(point("ch_main", RawFeature.Kind.HEAT_CHAMBER, ch, null));
        features.add(line("net_main", src, ch, 300));
        features.add(line("net_branch", ch, end, 200));

        List<OksConnectionPoint> oks = new ArrayList<>();
        double[][] at = {{37.6206, 55.7004}, {37.6208, 55.7004}, {37.6228, 55.7004}, {37.6229, 55.7003}};
        double[] flow = {12.0, 9.0, 11.0, 7.0};
        for (int i = 0; i < at.length; i++) {
            String id = "o" + (i + 1);
            features.add(point(id, RawFeature.Kind.OKS_CONNECTION_POINT, new Coordinate(at[i][0], at[i][1]), flow[i]));
            oks.add(new OksConnectionPoint(id, flow[i]));
        }

        IngestReport report = new IngestReport();
        ExistingNetwork existing = new ru.heatnet.ingest.NetworkTreeBuilder(PROJECTION)
                .build(features, 15.0, report);
        return new IngestResult(report, existing, oks, Collections.<String, Geometry>emptyMap(), features);
    }

    /** Дерево из одной врезки к одному ОКС; узлы намеренно названы так же, как их называет M4. */
    static NetworkPlan planWithTree(String tieId, String existingChamberId, String oksId,
                                    double flowTph, double lengthM, Coordinate from, Coordinate to) {
        List<NewNode> nodes = Arrays.asList(
                NewNode.tieIn(tieId),
                NewNode.technical("tn_1"),
                NewNode.oks("oks_" + oksId, oksId, flowTph));
        List<NewSegment> segments = Arrays.asList(
                NewSegment.base("seg_1", tieId, "tn_1", lengthM / 2.0),
                NewSegment.base("seg_2", "tn_1", "oks_" + oksId, lengthM / 2.0));
        NewNetworkTree tree = new NewNetworkTree(TieInPoint.intoChamber(tieId, existingChamberId), nodes, segments);

        Coordinate mid = new Coordinate((from.x + to.x) / 2.0, (from.y + to.y) / 2.0);
        NetworkTreeLayout layout = new NetworkTreeLayout();
        layout.putNode(tieId, from);
        layout.putNode("tn_1", mid);
        layout.putNode("oks_" + oksId, to);
        layout.putSegment("seg_1", lineUtm(from, mid));
        layout.putSegment("seg_2", lineUtm(mid, to));

        return new NetworkPlan(Collections.singletonList(tree), Collections.singletonList(layout),
                Collections.<UnconnectedOks>emptyList(), false);
    }

    static LineString lineUtm(Coordinate a, Coordinate b) {
        return PROJECTION.utmFactory().createLineString(new Coordinate[] {a, b});
    }

    private static RawFeature point(String id, RawFeature.Kind kind, Coordinate wgs, Double flow) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (flow != null) {
            props.put("flow_tph", flow);
        }
        return RawFeature.of(id, kind, props, GeoUtils.pointWgs84(wgs.x, wgs.y), null);
    }

    private static RawFeature line(String id, Coordinate a, Coordinate b, int dn) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("diameter", dn);
        Coordinate[] cs = {a, b};
        return RawFeature.of(id, RawFeature.Kind.HEAT_NETWORK, props,
                GeoUtils.wgs84Factory().createLineString(cs), cs);
    }
}
