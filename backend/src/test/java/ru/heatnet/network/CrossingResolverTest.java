package ru.heatnet.network;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.NodeKind;
import ru.heatnet.calc.model.TieInPoint;

class CrossingResolverTest {

    private final GeometryFactory gf = new GeometryFactory();
    private final CrossingResolver resolver = new CrossingResolver(gf, 0.01);

    @Test
    @DisplayName("пересечение ветвей в UTM → камера на магистрали, ветка врезается")
    void insertsChamberAtUtmCrossing() {
        NewNetworkTree tree = new NewNetworkTree(TieInPoint.intoChamber("tie", "c"),
                Arrays.asList(NewNode.tieIn("tie"), NewNode.chamber("ch"), NewNode.technical("tn"),
                        NewNode.oks("p1", null, 5.0), NewNode.oks("p2", null, 5.0)),
                Arrays.asList(NewSegment.base("s1", "tie", "ch", 50),
                        NewSegment.base("s_trunk", "ch", "tn", 100),
                        NewSegment.base("s2", "tn", "p1", 100),
                        NewSegment.base("s_branch", "ch", "p2", 223.6)));

        NetworkTreeLayout layout = new NetworkTreeLayout();
        layout.putNode("tie", new Coordinate(0, 0));
        layout.putNode("ch", new Coordinate(0, 0));
        layout.putNode("tn", new Coordinate(100, 0));
        layout.putNode("p1", new Coordinate(100, 100));
        layout.putNode("p2", new Coordinate(200, 100));
        layout.putSegment("s1", gf.createLineString(new Coordinate[] {new Coordinate(0, 0), new Coordinate(0, 0)}));
        layout.putSegment("s_trunk", gf.createLineString(new Coordinate[] {new Coordinate(0, 0), new Coordinate(100, 0)}));
        layout.putSegment("s2", gf.createLineString(new Coordinate[] {new Coordinate(100, 0), new Coordinate(100, 100)}));
        layout.putSegment("s_branch", gf.createLineString(new Coordinate[] {new Coordinate(0, 0), new Coordinate(200, 100)}));

        BuiltNetworkTree resolved = resolver.resolve(tree, layout);
        boolean hasCrossChamber = false;
        for (NewNode n : resolved.getTree().getNodes().values()) {
            if (n.getKind() == NodeKind.NEW_CHAMBER && n.getId().startsWith("nch_x_")) {
                hasCrossChamber = true;
            }
        }
        assertTrue(hasCrossChamber, "ожидается камера в точке пересечения s2 и s_branch");
        assertTrue(TopologyValidator.validate(Collections.singletonList(resolved.getTree())).isEmpty());
    }

    @Test
    @DisplayName("коллинеарное перекрытие — не зависание и не камера nch_x_")
    void collinearOverlapIsNotACrossing() {
        NewNetworkTree tree = new NewNetworkTree(TieInPoint.intoChamber("tie", "c"),
                Arrays.asList(NewNode.tieIn("tie"), NewNode.chamber("ch"),
                        NewNode.technical("n1"), NewNode.oks("p1", null, 5.0),
                        NewNode.technical("n3"), NewNode.oks("p2", null, 5.0)),
                Arrays.asList(NewSegment.base("s0", "tie", "ch", 5),
                        NewSegment.base("sA", "ch", "n1", 10),
                        NewSegment.base("s1", "n1", "p1", 100),
                        NewSegment.base("sB", "ch", "n3", 20),
                        NewSegment.base("s3", "n3", "p2", 60)));

        NetworkTreeLayout layout = new NetworkTreeLayout();
        layout.putNode("tie", new Coordinate(0, 1));
        layout.putNode("ch", new Coordinate(0, 0));
        layout.putNode("n1", new Coordinate(0, 0));
        layout.putNode("p1", new Coordinate(100, 0));
        layout.putNode("n3", new Coordinate(20, 0));
        layout.putNode("p2", new Coordinate(80, 0));
        layout.putSegment("s0", gf.createLineString(new Coordinate[] {new Coordinate(0, 1), new Coordinate(0, 0)}));
        layout.putSegment("sA", gf.createLineString(new Coordinate[] {new Coordinate(0, 0), new Coordinate(0, 0)}));
        layout.putSegment("s1", gf.createLineString(new Coordinate[] {new Coordinate(0, 0), new Coordinate(100, 0)}));
        layout.putSegment("sB", gf.createLineString(new Coordinate[] {new Coordinate(0, 0), new Coordinate(20, 0)}));
        layout.putSegment("s3", gf.createLineString(new Coordinate[] {new Coordinate(20, 0), new Coordinate(80, 0)}));

        BuiltNetworkTree resolved = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> resolver.resolve(tree, layout));
        boolean hasCrossChamber = false;
        for (NewNode n : resolved.getTree().getNodes().values()) {
            if (n.getKind() == NodeKind.NEW_CHAMBER && n.getId().startsWith("nch_x_")) {
                hasCrossChamber = true;
            }
        }
        assertTrue(!hasCrossChamber, "перекрытие по длине — общий коридор, не X-пересечение");
    }

    @Test
    @DisplayName("ветки камеры переподключены к nch_x_* → камера-тупик удаляется, дерево валидно без отката")
    void chamberLosingAllBranchesIsPruned() {
        CrossingResolver local = new CrossingResolver(gf, 0.01);
        NewNetworkTree tree = new NewNetworkTree(TieInPoint.intoChamber("tie", "c"),
                Arrays.asList(NewNode.tieIn("tie"), NewNode.chamber("ch1"), NewNode.chamber("ch2"),
                        NewNode.oks("p1", null, 5.0), NewNode.oks("p2", null, 5.0), NewNode.oks("p3", null, 5.0)),
                Arrays.asList(NewSegment.base("s0", "tie", "ch1", 10),
                        NewSegment.base("s_trunk", "ch1", "p1", 200),
                        NewSegment.base("s_mid", "ch1", "ch2", 70.71),
                        NewSegment.base("s_b1", "ch2", "p2", 100),
                        NewSegment.base("s_b2", "ch2", "p3", 122.07)));

        NetworkTreeLayout layout = new NetworkTreeLayout();
        layout.putNode("tie", new Coordinate(0, -10));
        layout.putNode("ch1", new Coordinate(0, 0));
        layout.putNode("ch2", new Coordinate(50, -50));
        layout.putNode("p1", new Coordinate(200, 0));
        layout.putNode("p2", new Coordinate(50, 50));
        layout.putNode("p3", new Coordinate(120, 50));
        layout.putSegment("s0", gf.createLineString(new Coordinate[] {new Coordinate(0, -10), new Coordinate(0, 0)}));
        layout.putSegment("s_trunk", gf.createLineString(new Coordinate[] {new Coordinate(0, 0), new Coordinate(200, 0)}));
        layout.putSegment("s_mid", gf.createLineString(new Coordinate[] {new Coordinate(0, 0), new Coordinate(50, -50)}));
        layout.putSegment("s_b1", gf.createLineString(new Coordinate[] {new Coordinate(50, -50), new Coordinate(50, 50)}));
        layout.putSegment("s_b2", gf.createLineString(new Coordinate[] {new Coordinate(50, -50), new Coordinate(120, 50)}));

        BuiltNetworkTree resolved = local.resolve(tree, layout);

        assertTrue(local.getFallbackCount() == 0, "resolve откатился к исходному дереву");
        assertTrue(!resolved.getTree().getNodes().containsKey("ch2"), "камера ch2 без выходов должна быть удалена");
        int crossChambers = 0;
        for (NewNode n : resolved.getTree().getNodes().values()) {
            if (n.getId().startsWith("nch_x_")) {
                crossChambers++;
                assertTrue(!resolved.getTree().childrenOf(n.getId()).isEmpty(), n.getId() + " — тупик");
            }
        }
        assertTrue(crossChambers == 2, "ожидались 2 камеры пересечения, найдено " + crossChambers);
        assertTrue(resolved.getTree().oksNodes().size() == 3, "ОКС потерян");
        assertTrue(TopologyValidator.validate(Collections.singletonList(resolved.getTree())).isEmpty());
    }
}
