package ru.heatnet.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NodeKind;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.geo.GeoUtils;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestReport;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.OksConnectionPoint;
import ru.heatnet.ingest.RawFeature;
import ru.heatnet.routing.RouteFinderFactory;
import ru.heatnet.routing.RouteM5Verification;
import ru.heatnet.routing.RouteResult;
import ru.heatnet.routing.RoutingPipeline;
import ru.heatnet.rules.IngestRestrictionsLoader;
import ru.heatnet.rules.RestrictionEngineFactory;

/**
 * QA (роль 4): топология M4 против п. 2.3 ТЗ / табл. разд. 3 приложения и контракта «частичный результат» (п. 2.9).
 */
class QaNetworkTest {

    private final ProjectionService projection = TestNetworkFixtures.projection();
    private final GeometryFactory gf = projection.utmFactory();
    private final ReferenceData ref = TestReference.get();

    private static int degree(NewNetworkTree t, NewNode n) {
        return t.childrenOf(n.getId()).size() + (t.incomingOf(n.getId()) != null ? 1 : 0);
    }

    @Test
    @DisplayName("QA-M4-1a: новая камера с 7 участками (1 вход + 6 ветвей) — TopologyValidator обязан отвергнуть (лимит ≤4)")
    void validatorMustRejectNewChamberWithMoreThanFourSegments() {
        List<NewNode> nodes = new ArrayList<>();
        List<ru.heatnet.calc.model.NewSegment> segs = new ArrayList<>();
        nodes.add(NewNode.tieIn("tie_x"));
        nodes.add(NewNode.chamber("nch"));
        segs.add(ru.heatnet.calc.model.NewSegment.base("trunk", "tie_x", "nch", 20));
        for (int i = 0; i < 6; i++) {
            nodes.add(NewNode.oks("o" + i, null, 5.0));
            segs.add(ru.heatnet.calc.model.NewSegment.base("b" + i, "nch", "o" + i, 30 + i));
        }
        NewNetworkTree tree = new NewNetworkTree(TieInPoint.intoChamber("tie_x", "ch_x"), nodes, segs);
        assertTrue(degree(tree, tree.node("nch")) > 4, "предусловие: степень камеры 7");
        List<String> errors = TopologyValidator.validate(Collections.singletonList(tree));
        assertFalse(errors.isEmpty(),
                "п. 2.3 ТЗ: к камере — не более четырёх участков; камера со степенью 7 прошла валидацию топологии");
    }

    @Test
    @DisplayName("QA-M4-1c: buildMerged — маршруты с общим началом и разной концовкой: ветви начинаются В КАМЕРЕ ветвления (непрерывность)")
    void mergedTreeIsGeometricallyContinuous() {
        NetworkTreeBuilder builder = new NetworkTreeBuilder(gf, 0.01);
        double[] ends = {20, -20, 60};
        List<NetworkTreeBuilder.OksRoute> routes = new ArrayList<>();
        for (int i = 0; i < ends.length; i++) {
            LineString path = gf.createLineString(new Coordinate[] {
                    new Coordinate(0, 0), new Coordinate(50, 0), new Coordinate(100, ends[i])});
            routes.add(new NetworkTreeBuilder.OksRoute(new OksConnectionPoint("o" + i, 5.0),
                    RouteResult.found(path, Collections.emptyList())));
        }
        BuiltNetworkTree built = builder.buildMerged(TieInPoint.intoChamber("tie_x", "ch_x"), routes);
        NewNetworkTree tree = built.getTree();
        NetworkTreeLayout layout = built.getLayout();
        for (ru.heatnet.calc.model.NewSegment s : tree.getSegments().values()) {
            Coordinate from = layout.nodeCoordinate(s.getFromNodeId());
            LineString g = layout.segmentLine(s.getId());
            assertTrue(from != null && g != null, "нет геометрии/узла для " + s.getId());
            assertTrue(from.distance(g.getCoordinateN(0)) < 0.5,
                    "участок " + s.getId() + " начинается в " + g.getCoordinateN(0) + ", а его начальный узел " + s.getFromNodeId()
                            + " лежит в " + from + " — разрыв сети");
        }
    }

    @Test
    @DisplayName("QA-M4-4: геометрия построенного участка сохраняет вершины маршрута M3 (обход препятствия), а не «срезается» хордой")
    void builtSegmentsKeepRouteVertices() {
        NetworkTreeBuilder builder = new NetworkTreeBuilder(gf, 0.01);
        // маршрут огибает препятствие в точке (50,0): (0,0) -> (50,60) -> (100,0); длина ≈ 156,2 м, хорда — 100 м
        LineString path = gf.createLineString(new Coordinate[] {
                new Coordinate(0, 0), new Coordinate(50, 60), new Coordinate(100, 0)});
        BuiltNetworkTree built = builder.buildSingle(TieInPoint.intoChamber("t", "c"),
                RouteResult.found(path, Collections.emptyList()), new OksConnectionPoint("o", 5.0));
        double layoutLen = 0;
        int minVertices = Integer.MAX_VALUE;
        for (LineString g : built.getLayout().getSegmentGeometries().values()) {
            layoutLen += g.getLength();
            minVertices = Math.min(minVertices, g.getNumPoints());
        }
        assertTrue(Math.abs(layoutLen - path.getLength()) < 0.5,
                "длина сети в плане " + layoutLen + " м, длина маршрута M3 — " + path.getLength() + " м: вершины маршрута потеряны");
        assertTrue(Math.abs(built.getTree().totalLengthM() - path.getLength()) < 0.5,
                "L дерева (для стоимости и S) = " + built.getTree().totalLengthM() + " м вместо " + path.getLength());
        assertTrue(minVertices >= 3, "участок содержит " + minVertices + " вершин — обход препятствия потерян");
    }

    @Test
    @DisplayName("QA-M4-5: специальный проход в середине ломаного маршрута — куски base/special/base сохраняют вершины")
    void specialSplitKeepsVertices() {
        NetworkTreeBuilder builder = new NetworkTreeBuilder(gf, 0.01);
        LineString path = gf.createLineString(new Coordinate[] {
                new Coordinate(0, 0), new Coordinate(100, 0), new Coordinate(100, 100), new Coordinate(200, 100)});
        ru.heatnet.rules.model.SpecialSection sec = new ru.heatnet.rules.model.SpecialSection(
                gf.createLineString(new Coordinate[] {new Coordinate(100, 20), new Coordinate(100, 60)}), 1.6,
                Collections.singleton("road"));
        BuiltNetworkTree built = builder.buildSingle(TieInPoint.intoChamber("t", "c"),
                RouteResult.found(path, Collections.singletonList(sec)), new OksConnectionPoint("o", 5.0));
        double layoutLen = 0;
        for (LineString g : built.getLayout().getSegmentGeometries().values()) {
            layoutLen += g.getLength();
        }
        assertTrue(Math.abs(layoutLen - path.getLength()) < 0.5,
                "суммарная длина участков " + layoutLen + " м != длине маршрута " + path.getLength() + " м (потеряны изгибы)");
    }

    @Test
    @DisplayName("QA-M4-6 (E2E): парк-«стена» между врезкой и ОКС — построенная сеть НЕ должна проходить сквозь парк")
    void plannedNetworkDoesNotCrossPark() {
        Coordinate src = new Coordinate(37.6200, 55.7000);
        Coordinate ch = new Coordinate(37.6210, 55.7000);
        Coordinate oksC = new Coordinate(37.6205, 55.7006);
        List<RawFeature> features = new ArrayList<>();
        features.add(pt("src", RawFeature.Kind.SOURCE, src, null));
        features.add(pt("ch1", RawFeature.Kind.HEAT_CHAMBER, ch, null));
        features.add(line("net1", src, ch, 200));
        features.add(pt("o1", RawFeature.Kind.OKS_CONNECTION_POINT, oksC, 10.0));
        // парк 27 м шириной точно между трубой и ОКС (x 37.6202..37.6208, y 55.7002..55.7004)
        Coordinate[] ring = {new Coordinate(37.6202, 55.7002), new Coordinate(37.6208, 55.7002),
                new Coordinate(37.6208, 55.7004), new Coordinate(37.6202, 55.7004), new Coordinate(37.6202, 55.7002)};
        org.locationtech.jts.geom.Polygon parkWgs = GeoUtils.wgs84Factory().createPolygon(ring);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("restriction_type", "park");
        features.add(RawFeature.of("park1", RawFeature.Kind.RESTRICTION, props, parkWgs, null));
        Map<String, org.locationtech.jts.geom.Geometry> restr = new LinkedHashMap<>();
        restr.put("park1", parkWgs);

        IngestReport report = new IngestReport();
        ExistingNetwork network = new ru.heatnet.ingest.NetworkTreeBuilder(projection).build(features, 15.0, report);
        IngestResult ingest = new IngestResult(report, network,
                Collections.singletonList(new OksConnectionPoint("o1", 10.0)), restr, features);

        NetworkPlan plan = realPlanner().plan(ingest);
        assertFalse(plan.getTrees().isEmpty(), "предусловие: ОКС должен быть подключён");

        // парк в UTM
        Coordinate[] ringUtm = new Coordinate[ring.length];
        for (int i = 0; i < ring.length; i++) {
            ringUtm[i] = projection.toUtm(ring[i].x, ring[i].y);
        }
        org.locationtech.jts.geom.Polygon parkUtm = gf.createPolygon(ringUtm);
        double inside = 0;
        for (NetworkTreeLayout layout : plan.getLayouts()) {
            for (LineString g : layout.getSegmentGeometries().values()) {
                inside += g.intersection(parkUtm).getLength();
            }
        }
        assertTrue(inside < 0.01, "построенная сеть проходит сквозь запретный парк: " + inside + " м внутри полигона");
    }

    /** 5 ОКС по 2 т/ч в нескольких метрах от существующей камеры. */
    private IngestResult clusterAroundChamber() {
        Coordinate src = new Coordinate(37.6200, 55.7000);
        Coordinate ch = new Coordinate(37.6205, 55.7000);
        Coordinate end = new Coordinate(37.6215, 55.7000);
        List<RawFeature> features = new ArrayList<>();
        features.add(pt("src", RawFeature.Kind.SOURCE, src, null));
        features.add(pt("ch_main", RawFeature.Kind.HEAT_CHAMBER, ch, null));
        features.add(line("net_main", src, ch, 200));
        features.add(line("net_branch", ch, end, 150));
        double d = 0.00006;
        double[][] off = {{d, 0}, {-d, 0}, {0, d}, {0, -d}, {d, d}};
        List<OksConnectionPoint> oks = new ArrayList<>();
        for (int i = 0; i < off.length; i++) {
            String id = "o" + i;
            features.add(pt(id, RawFeature.Kind.OKS_CONNECTION_POINT,
                    new Coordinate(ch.x + off[i][0], ch.y + off[i][1]), 2.0));
            oks.add(new OksConnectionPoint(id, 2.0));
        }
        IngestReport report = new IngestReport();
        ExistingNetwork network = new ru.heatnet.ingest.NetworkTreeBuilder(projection).build(features, 15.0, report);
        return new IngestResult(report, network, oks, Collections.<String, org.locationtech.jts.geom.Geometry>emptyMap(),
                features);
    }

    private RawFeature pt(String id, RawFeature.Kind kind, Coordinate c, Double flow) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (flow != null) {
            props.put("flow_tph", flow);
        }
        return RawFeature.of(id, kind, props, GeoUtils.pointWgs84(c.x, c.y), null);
    }

    private RawFeature line(String id, Coordinate a, Coordinate b, int dn) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("diameter", dn);
        Coordinate[] cs = {a, b};
        return RawFeature.of(id, RawFeature.Kind.HEAT_NETWORK, props, GeoUtils.wgs84Factory().createLineString(cs), cs);
    }

    private NetworkPlanner realPlanner() {
        RestrictionEngineFactory ef = new RestrictionEngineFactory(new IngestRestrictionsLoader(projection), ref, projection);
        RouteFinderFactory rff = new RouteFinderFactory(ef, ref);
        RoutingPipeline pipe = new RoutingPipeline(rff, new RouteM5Verification(ef, rff));
        return new NetworkPlanner(ref, projection, pipe, rff);
    }

    @Test
    @DisplayName("QA-M4-1b (E2E): 5 ОКС у одной камеры — итоговый план не содержит камер с >4 примыкающими участками")
    void plannerResultRespectsChamberLimit() {
        IngestResult ingest = clusterAroundChamber();
        NetworkPlan plan = realPlanner().plan(ingest);
        int worst = 0;
        for (NewNetworkTree t : plan.getTrees()) {
            for (NewNode n : t.getNodes().values()) {
                if (n.getKind() == NodeKind.NEW_CHAMBER) {
                    worst = Math.max(worst, degree(t, n));
                }
            }
        }
        assertTrue(worst <= 4, "в плане есть новая камера со степенью " + worst + " (>4) — нарушение п. 2.3 ТЗ");
    }

    @Test
    @DisplayName("QA-M4-2: если перетрассировка шага 3 не удалась — ОКС не должен исчезать: он обязан попасть в unconnected_oks_ids")
    void failedRerouteMustNotSilentlyDropOks() {
        IngestResult ingest = TestNetworkFixtures.simpleMagistral();
        RoutingPipeline pipeline = mock(RoutingPipeline.class);
        RouteFinderFactory rff = mock(RouteFinderFactory.class);
        when(rff.leafDn(anyDouble())).thenReturn(25); // «маршрут строили под DN25», фактический DN будет больше
        when(rff.magistralDn(anyDouble())).thenReturn(25);
        when(pipeline.findLeafRoute(any(), any(), any(), anyDouble())).thenAnswer(inv -> {
            Point from = inv.getArgument(1);
            Point to = inv.getArgument(2);
            return RouteResult.found(gf.createLineString(new Coordinate[] {from.getCoordinate(), to.getCoordinate()}),
                    Collections.emptyList());
        });
        when(pipeline.verifyAfterM5(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(RouteResult.notFound());

        NetworkPlanner planner = new NetworkPlanner(ref, projection, pipeline, rff);
        List<OksConnectionPoint> one = Collections.singletonList(new OksConnectionPoint("oks_near", 20.0));
        NetworkPlan plan = planner.plan(ingest, ingest.getExistingNetwork(),
                ExistingNetworkGeometry.fromIngest(ingest.getAcceptedFeatures(), ingest.getExistingNetwork(), projection),
                one);

        boolean connected = false;
        for (NewNetworkTree t : plan.getTrees()) {
            for (NewNode n : t.oksNodes()) {
                connected |= "oks_near".equals(n.getOksId());
            }
        }
        boolean listed = false;
        for (ru.heatnet.cost.UnconnectedOks u : plan.getUnconnectedOks()) {
            listed |= "oks_near".equals(u.getOksId());
        }
        assertTrue(connected || listed,
                "ОКС oks_near потерян: нет ни в деревьях, ни в unconnected_oks_ids (нет штрафа 100 млн — S занижен)");
    }

    @Test
    @DisplayName("QA-M4-3: раздельные вводы не перегружают существующую камеру сверх 4 участков")
    void separateTieInsRespectExistingChamberDegree() {
        IngestResult ingest = clusterAroundChamber();
        NetworkPlan plan = realPlanner().plan(ingest);
        Map<String, Integer> intoChamber = new LinkedHashMap<>();
        for (NewNetworkTree t : plan.getTrees()) {
            if (t.getTieIn().getExistingObjectType() == ru.heatnet.calc.model.ExistingObjectType.HEAT_CHAMBER) {
                intoChamber.merge(t.getTieIn().getExistingObjectId(), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : intoChamber.entrySet()) {
            int existing = ChamberDegreeCalculator.currentDegree(ingest.getExistingNetwork(), e.getKey());
            assertTrue(existing + e.getValue() <= 4,
                    "камера " + e.getKey() + ": существующих " + existing + " + новых врезок " + e.getValue() + " > 4");
        }
        assertTrue(Arrays.asList(1).size() == 1);
    }
}
