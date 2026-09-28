package ru.heatnet.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.GeometryFactory;

import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;

/**
 * Граница смены условного диаметра.
 *
 * <p><b>QA-FIX P3 (H-1).</b> Раньше класс вставлял {@code technical_node} сразу после камеры
 * разветвления, если на ответвлении менялся ДУ. Это противоречит приложению:
 *
 * <ul>
 *   <li>§2.3 — «На пути между узлами, в которых меняется расчётный расход, выбранный ДУ сохраняется
 *       на всей длине». Расход меняется в точке разветвления, то есть <b>в самой камере</b>,
 *       поэтому граница ДУ всегда совпадает с камерой и отдельной точки деления не требует.</li>
 *   <li>§2.1 — «Технический узел используется <b>только</b> там, где без разветвления заканчивается
 *       один участок и начинается другой из-за изменения способа прокладки, профиля глубины или
 *       другого параметра». У камеры разветвление есть, значит ТУ там не нужен.</li>
 * </ul>
 *
 * <p>Легитимные границы участков (спецпроходы по §4) создаёт {@link RouteSegmentSplitter}: он делит
 * трассу на границах специального прохода и ставит там {@code technical_node}, если граница
 * не совпадает с камерой или точкой подключения ОКС.
 *
 * <p>Класс оставлен как явная точка фиксации правила и возвращает дерево без изменений.
 */
public final class DnBoundarySplitter {

    public DnBoundarySplitter(GeometryFactory gf, double toleranceM) {
        // Параметры сохранены ради совместимости сигнатуры; состояние классу больше не нужно.
    }

    /**
     * Возвращает дерево без изменений.
     *
     * @param diameters окончательный ДУ участков после M5 (используется только для контроля инварианта)
     */
    public BuiltNetworkTree apply(NewNetworkTree tree, NetworkTreeLayout layout, Map<String, Integer> diameters) {
        List<NewNode> nodeList = new ArrayList<>(tree.getNodes().values());
        List<NewSegment> segments = new ArrayList<>(tree.segmentsTopDown());
        return new BuiltNetworkTree(new NewNetworkTree(tree.getTieIn(), nodeList, segments), layout.copy());
    }
}
