package ru.heatnet.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.NodeKind;
import ru.heatnet.calc.model.TieInPoint;

/**
 * QA-FIX P3 (H-1). Тест переписан: прежняя версия кодировала правило, отменённое приложением.
 *
 * <p>§2.3: «На пути между узлами, в которых меняется расчётный расход, выбранный ДУ сохраняется
 * на всей длине» — расход меняется в точке разветвления, то есть в самой камере, поэтому граница
 * ДУ совпадает с камерой. §2.1: «Технический узел используется только там, где <b>без
 * разветвления</b> заканчивается один участок и начинается другой из-за изменения способа
 * прокладки, профиля глубины или другого параметра». У камеры разветвление есть — ТУ не нужен.
 */
class DnBoundarySplitterTest {

    private final GeometryFactory gf = new GeometryFactory();
    private final DnBoundarySplitter splitter = new DnBoundarySplitter(gf, 0.01);

    @Test
    @DisplayName("§2.1/§2.3: смена ДУ на ответвлении из камеры НЕ создаёт technical_node")
    void noTechnicalNodeAtBranchChamber() {
        NewNetworkTree tree = new NewNetworkTree(TieInPoint.intoChamber("tie", "c"),
                Arrays.asList(NewNode.tieIn("tie"), NewNode.chamber("ch"),
                        NewNode.oks("p1", null, 3.0), NewNode.oks("p2", null, 10.0)),
                Arrays.asList(NewSegment.base("s1", "tie", "ch", 150),
                        NewSegment.base("s2", "ch", "p1", 150),
                        NewSegment.base("s3", "ch", "p2", 200)));

        NetworkTreeLayout layout = new NetworkTreeLayout();
        layout.putNode("tie", new Coordinate(0, 0));
        layout.putNode("ch", new Coordinate(150, 0));
        layout.putNode("p1", new Coordinate(150, 150));
        layout.putNode("p2", new Coordinate(350, 0));
        layout.putSegment("s1", gf.createLineString(new Coordinate[] {new Coordinate(0, 0), new Coordinate(150, 0)}));
        layout.putSegment("s2", gf.createLineString(new Coordinate[] {new Coordinate(150, 0), new Coordinate(150, 150)}));
        layout.putSegment("s3", gf.createLineString(new Coordinate[] {new Coordinate(150, 0), new Coordinate(350, 0)}));

        Map<String, Integer> dn = new HashMap<>();
        dn.put("s1", 100);
        dn.put("s2", 50);
        dn.put("s3", 100);

        BuiltNetworkTree result = splitter.apply(tree, layout, dn);

        int tnCount = 0;
        for (NewNode n : result.getTree().getNodes().values()) {
            if (n.getKind() == NodeKind.TECHNICAL_NODE) {
                tnCount++;
            }
        }
        assertEquals(0, tnCount, "ТУ у камеры разветвления запрещён §2.1");
        assertEquals(3, result.getTree().segmentsTopDown().size(),
                "участки не должны дробиться: граница ДУ совпадает с камерой");
    }
}
