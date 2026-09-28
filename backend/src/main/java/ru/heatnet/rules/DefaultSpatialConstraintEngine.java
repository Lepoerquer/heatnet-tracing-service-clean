package ru.heatnet.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

import ru.heatnet.calc.reference.RulesConfig;
import ru.heatnet.rules.model.CrossingResult;
import ru.heatnet.rules.model.PortalGate;
import ru.heatnet.rules.model.SpecialSection;

/**
 * Реализация M2: запретные буферы, барьеры road/tram, спецзоны, max(K_спец).
 */
public final class DefaultSpatialConstraintEngine implements SpatialConstraintEngine {

    private final RestrictionSpatialIndex index;
    private final BufferFactory bufferFactory;
    private final PortalGateGenerator portalGateGenerator;
    private final RulesConfig rulesConfig;
    private final List<PortalGate> allPortalGates;
    private final Map<String, IndexedRestriction> byId;
    private final int bundleDn;

    public DefaultSpatialConstraintEngine(RestrictionSpatialIndex index,
                                          BufferFactory bufferFactory,
                                          PortalGateGenerator portalGateGenerator,
                                          RulesConfig rulesConfig,
                                          List<PortalGate> allPortalGates,
                                          Map<String, IndexedRestriction> byId,
                                          int bundleDn) {
        this.index = index;
        this.bufferFactory = bufferFactory;
        this.portalGateGenerator = portalGateGenerator;
        this.rulesConfig = rulesConfig;
        this.allPortalGates = allPortalGates;
        this.byId = byId;
        this.bundleDn = bundleDn;
    }

    @Override
    public boolean isSegmentBlocked(LineString segment, int candidateDn) {
        assertBundleDn(candidateDn);
        if (segment == null || segment.isEmpty()) {
            return false;
        }
        List<IndexedRestriction> candidates = index.queryBlocked(segment.getEnvelopeInternal());
        double tolerance = rulesConfig.getGeometryToleranceM();

        for (IndexedRestriction restriction : candidates) {
            Geometry blocked = restriction.getBlockedZoneShrunk();
            if (blocked.isEmpty() && restriction.getPolygonOffsetCorridorShrunk().isEmpty()) {
                continue;
            }
            if (restriction.isPolygonBarrier()) {
                BarrierEdges edges = new BarrierEdges(segment, restriction, tolerance);
                if (polygonBarrierBlocks(edges)) {
                    return true;
                }
                if (polygonOffsetBlocks(segment, restriction, edges)) {
                    return true;
                }
                continue;
            }
            if (!blocked.isEmpty() && restriction.blockedShrunkIntersects(segment)) {
                return true;
            }
        }
        if (linearNearbyBlocks(segment, tolerance)) {
            return true;
        }
        return false;
    }

    @Override
    public List<CrossingResult> findCrossings(LineString segment, int candidateDn) {
        assertBundleDn(candidateDn);
        if (segment == null || segment.isEmpty()) {
            return Collections.emptyList();
        }
        List<IndexedRestriction> candidates = index.querySpecial(segment.getEnvelopeInternal());
        List<CrossingResult> results = new ArrayList<>();
        double tolerance = rulesConfig.getGeometryToleranceM();

        for (IndexedRestriction restriction : candidates) {
            if (restriction.isProhibited()) {
                continue;
            }
            if (rulesConfig.isProhibitedHasPriority() && prohibitedOverlapBlocksSpecial(segment, restriction)) {
                continue;
            }
            Double kSpec = restriction.getRule().getKSpec();
            if (kSpec == null) {
                continue;
            }
            if (restriction.isPolygonBarrier()) {
                // AUDIT-24.09 (Claude): дорога/трамвай считаются по КАЖДОМУ прямому звену трассы.
                // Раньше угол брался по хорде «первая–последняя точка» всей ломаной, поэтому законное
                // пересечение на ломаной трассе могло отбрасываться (участок уходил в base без Kспец),
                // а при двух пересечениях одной дороги учитывалось только первое (MultiLineString).
                // Кроме того, спецпроходом считается только звено, реально пересекающее полигон
                // дороги: проход в 1,5–3 м вдоль дороги — не пересечение (табл. 2, Разъяснение №7).
                results.addAll(polygonBarrierCrossings(segment, restriction, kSpec, tolerance));
                continue;
            }

            Geometry zone = specialGeometryForCrossing(restriction);
            if (zone.isEmpty() || !segment.intersects(zone)) {
                continue;
            }
            Geometry intersection = segment.intersection(zone);
            if (intersection.isEmpty()) {
                continue;
            }
            Geometry tieIn = tieInExemption(segment, restriction);
            if (tieIn != null) {
                intersection = differenceSafe(intersection, tieIn);
                if (intersection == null || intersection.isEmpty()) {
                    continue;
                }
            }
            results.add(new CrossingResult(restriction.getId(), restriction.getRulesKey(),
                    kSpec, intersection, null));
        }
        return expandPointCrossings(results, segment);
    }

    /** Пересечения площадного барьера (дорога/трамвай) по прямым звеньям трассы. */
    private List<CrossingResult> polygonBarrierCrossings(LineString path, IndexedRestriction restriction,
                                                         double kSpec, double tolerance) {
        List<CrossingResult> out = new ArrayList<>();
        Geometry zone = restriction.getSpecialZone();
        // AUDIT-13 (Claude, 25.09): intersects по подготовленной зоне/полигонам — ответ тот же, без перебора звеньев
        // сложного полигона дороги на каждый вызов.
        if (zone == null || zone.isEmpty() || !restriction.specialZoneIntersects(path)) {
            return out;
        }
        Double minAngle = restriction.getRule().getMinCrossingAngleDeg();
        double margin = restriction.getRule().getZoneMarginM();
        for (int i = 0; i < path.getNumPoints() - 1; i++) {
            LineString edge = path.getFactory().createLineString(new Coordinate[] {
                    path.getCoordinateN(i), path.getCoordinateN(i + 1)});
            if (edge.getLength() < 1e-9 || !restriction.specialZoneIntersects(edge)) {
                continue;
            }
            // AUDIT-12 (Claude, 24.09). §4 приложения: «Длина специального участка за границей полигона
            // отсчитывается вдоль трассы». Раньше спецучастком бралось пересечение звена с буфером полигона
            // (полигон + 3 м по нормали): при косом пересечении под 45° участок за границей выходил 3/sin45 =
            // 4,24 м вдоль трассы вместо 3 м. Теперь: отрезок звена внутри полигона плюс ровно по 3 м вдоль
            // звена до и после (в пределах звена; вершины трассы в зоне спецпрохода запрещены, поэтому звено
            // всегда длиннее).
            List<Polygon> parts = restriction.sourcePolygons();
            for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                Polygon polygon = parts.get(partIndex);
                Geometry inside;
                try {
                    if (!edge.getEnvelopeInternal().intersects(polygon.getEnvelopeInternal())
                            || !restriction.sourcePolygonIntersects(partIndex, edge)) {
                        continue;
                    }
                    inside = edge.intersection(polygon);
                } catch (RuntimeException ex) {
                    continue;
                }
                for (int k = 0; k < inside.getNumGeometries(); k++) {
                    Geometry piece = inside.getGeometryN(k);
                    if (!(piece instanceof LineString) || piece.getLength() <= tolerance) {
                        continue; // звено идёт в полосе 3 м у дороги, но полигон не пересекает — это не спецпроход
                    }
                    double angle = PortalGateGenerator.crossingAngleToBoundaryDeg(edge, polygon);
                    if (minAngle != null && angle + tolerance < minAngle) {
                        continue;
                    }
                    LineString part = alongEdgeExtended(edge, (LineString) piece, margin);
                    out.add(new CrossingResult(restriction.getId(), restriction.getRulesKey(), kSpec, part, angle));
                }
            }
        }
        return out;
    }

    /** Отрезок {@code inside} звена {@code edge}, продлённый на {@code marginM} вдоль звена в обе стороны. */
    private static LineString alongEdgeExtended(LineString edge, LineString inside, double marginM) {
        Coordinate a = edge.getCoordinateN(0);
        Coordinate b = edge.getCoordinateN(1);
        double len = a.distance(b);
        double t0 = Double.POSITIVE_INFINITY;
        double t1 = Double.NEGATIVE_INFINITY;
        for (Coordinate c : inside.getCoordinates()) {
            double t = ((c.x - a.x) * (b.x - a.x) + (c.y - a.y) * (b.y - a.y)) / (len * len) * len;
            t0 = Math.min(t0, t);
            t1 = Math.max(t1, t);
        }
        double from = Math.max(0.0, t0 - marginM);
        double to = Math.min(len, t1 + marginM);
        double ux = (b.x - a.x) / len;
        double uy = (b.y - a.y) / len;
        return edge.getFactory().createLineString(new Coordinate[] {
                new Coordinate(a.x + ux * from, a.y + uy * from), new Coordinate(a.x + ux * to, a.y + uy * to)});
    }

    @Override
    public boolean isInsideSpecialZone(Coordinate point) {
        if (point == null) {
            return false;
        }
        List<IndexedRestriction> candidates = index.querySpecial(new org.locationtech.jts.geom.Envelope(point));
        if (candidates.isEmpty()) {
            return false;
        }
        org.locationtech.jts.geom.Point p = null;
        for (IndexedRestriction restriction : candidates) {
            if (restriction.isProhibited() || restriction.getRule().getKSpec() == null) {
                continue;
            }
            Geometry zone = restriction.getSpecialZone();
            if (zone == null || zone.isEmpty()) {
                continue;
            }
            if (p == null) {
                p = zone.getFactory().createPoint(point);
            }
            try {
                if (zone.covers(p)) {
                    return true;
                }
            } catch (RuntimeException ex) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<SpecialSection> extractSpecialSections(LineString segment, int candidateDn) {
        assertBundleDn(candidateDn);
        List<CrossingResult> crossings = findCrossings(segment, candidateDn);
        if (crossings.isEmpty()) {
            return Collections.emptyList();
        }
        List<SpecialSection> sections = new ArrayList<>();
        for (CrossingResult crossing : crossings) {
            LineString geom = specialSectionGeometry(segment, crossing);
            if (geom == null || geom.isEmpty()) {
                continue;
            }
            Set<String> types = new LinkedHashSet<>();
            types.add(crossing.getRestrictionType());
            sections.add(new SpecialSection(geom, crossing.getKSpec(), types));
        }
        return sections;
    }

    @Override
    public List<PortalGate> portalGates() {
        return allPortalGates;
    }

    @Override
    public boolean isRoadCrossingAngleValid(LineString segment, String restrictionId, int candidateDn) {
        assertBundleDn(candidateDn);
        IndexedRestriction restriction = byId.get(restrictionId);
        if (restriction == null || !restriction.isPolygonBarrier()) {
            return true;
        }
        Polygon polygon = firstPolygon(restriction.getSourceGeometry());
        if (polygon == null) {
            return false;
        }
        return portalGateGenerator.isCrossingAngleToBoundaryValid(segment, polygon, restriction.getRule());
    }

    private boolean prohibitedOverlapBlocksSpecial(LineString segment, IndexedRestriction special) {
        Geometry specialGeom = combinedSpecialGeometry(special);
        for (IndexedRestriction other : index.queryBlocked(segment.getEnvelopeInternal())) {
            if (!other.isProhibited() || other.getId().equals(special.getId())) {
                continue;
            }
            Geometry blocked = other.getBlockedZone();
            if (!blocked.isEmpty() && specialGeom.intersects(blocked)) {
                Geometry overlap = segment.intersection(specialGeom.intersection(blocked));
                if (!overlap.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private Geometry combinedSpecialGeometry(IndexedRestriction restriction) {
        return restriction.getCombinedSpecial();
    }

    /**
     * AUDIT-13 (Claude, 25.09): то же, что {@link #crossesPolygonInterior(LineString, Polygon, double)} для части
     * {@code part} исходной геометрии ограничения, но оверлей JTS выполняется, только если звено вообще задевает
     * полигон (рамка + подготовленная геометрия; пустое пересечение ⇔ нет {@code intersects}).
     */
    private boolean crossesPolygonInterior(LineString segment, IndexedRestriction restriction, int part,
                                           double tolerance) {
        Polygon polygon = restriction.sourcePolygons().get(part);
        if (!segment.getEnvelopeInternal().intersects(polygon.getEnvelopeInternal())) {
            return false;
        }
        try {
            if (!restriction.sourcePolygonIntersects(part, segment)) {
                return false;
            }
        } catch (RuntimeException ex) {
            // решает полный оверлей, как раньше
        }
        return crossesPolygonInterior(segment, polygon, tolerance);
    }

    private boolean crossesPolygonInterior(LineString segment, Polygon polygon, double tolerance) {
        Geometry intersection = segment.intersection(polygon);
        if (intersection.isEmpty()) {
            return false;
        }
        if (intersection instanceof LineString) {
            return ((LineString) intersection).getLength() > tolerance;
        }
        return intersection.getLength() > tolerance;
    }

    private LineString extractSubLine(LineString segment, Geometry zone) {
        Geometry inter = segment.intersection(zone);
        if (inter instanceof LineString) {
            return (LineString) inter;
        }
        if (inter.getNumGeometries() > 0 && inter.getGeometryN(0) instanceof LineString) {
            return (LineString) inter.getGeometryN(0);
        }
        return null;
    }

    /**
     * AUDIT-13 (Claude, 25.09). Звенья отрезка и их пересечения с полигонами площадного барьера (дорога/трамвай) —
     * на один вызов {@link #isSegmentBlocked}. Раньше для звена, пересекающего дорогу, одни и те же оверлеи JTS
     * считались до пяти раз: «звено внутри полигона» (здесь и повторно в проверке полосы отступа), «пересечение с
     * границей» и оно же ещё дважды ради угла пересечения. Теперь каждое — один раз, с теми же формулами, поэтому
     * ответ прежний.
     */
    private final class BarrierEdges {
        /** Состояние (звено, часть): 0 — не вычислено. */
        private static final byte NO_CROSSING = 1;
        private static final byte BOUNDARY_MISSED = 2;
        private static final byte ANGLE_OK = 3;
        private static final byte ANGLE_BAD = 4;

        private final LineString segment;
        private final IndexedRestriction restriction;
        private final double tolerance;
        private final LineString[] edges;
        private final byte[][] state;

        private BarrierEdges(LineString segment, IndexedRestriction restriction, double tolerance) {
            this.segment = segment;
            this.restriction = restriction;
            this.tolerance = tolerance;
            this.edges = new LineString[Math.max(0, segment.getNumPoints() - 1)];
            this.state = new byte[edges.length][];
        }

        int size() {
            return edges.length;
        }

        /** Звено {@code i} или {@code null}, если оно вырождено (длина &lt; 1e-9). */
        LineString edge(int i) {
            LineString e = edges[i];
            if (e == null) {
                e = segment.getFactory().createLineString(new Coordinate[] {
                        segment.getCoordinateN(i), segment.getCoordinateN(i + 1)});
                edges[i] = e;
            }
            return e.getLength() < 1e-9 ? null : e;
        }

        /** Пересечение звена {@code i} с частью {@code part} (исключения оверлея — как раньше, наружу). */
        byte state(int i, int part) {
            byte[] row = state[i];
            if (row == null) {
                row = new byte[restriction.sourcePolygons().size()];
                state[i] = row;
            }
            if (row[part] != 0) {
                return row[part];
            }
            LineString edge = edge(i);
            Polygon polygon = restriction.sourcePolygons().get(part);
            byte value;
            if (edge == null || !crossesPolygonInterior(edge, restriction, part, tolerance)) {
                value = NO_CROSSING;
            } else {
                Geometry boundaryHit = edge.intersection(polygon.getBoundary());
                if (boundaryHit.isEmpty()) {
                    value = BOUNDARY_MISSED;
                } else {
                    Double minAngle = restriction.getRule().getMinCrossingAngleDeg();
                    value = minAngle == null
                            || PortalGateGenerator.crossingAngleToBoundaryDeg(edge, polygon, boundaryHit) + 1e-9
                            >= minAngle ? ANGLE_OK : ANGLE_BAD;
                }
            }
            row[part] = value;
            return value;
        }

        /** Как прежний {@code isCrossingAngleToBoundaryValid} для пересекающего звена. */
        boolean angleValid(int i, int part) {
            byte s = state(i, part);
            if (s == BOUNDARY_MISSED) {
                // пересечения с границей нет — прежняя формула давала угол 90°
                Double minAngle = restriction.getRule().getMinCrossingAngleDeg();
                return minAngle == null || 90.0 + 1e-9 >= minAngle;
            }
            return s == ANGLE_OK;
        }
    }

    private boolean polygonBarrierBlocks(BarrierEdges edges) {
        // AUDIT-24.09 (Claude): проверка по каждому прямому звену — угол к границе у ломаной
        // определяется её звеном, а не хордой «первая–последняя точка».
        int parts = edges.restriction.sourcePolygons().size();
        for (int i = 0; i < edges.size(); i++) {
            if (edges.edge(i) == null) {
                continue;
            }
            for (int part = 0; part < parts; part++) {
                byte s = edges.state(i, part);
                if (s == BarrierEdges.NO_CROSSING) {
                    continue;
                }
                if (s == BarrierEdges.BOUNDARY_MISSED || s == BarrierEdges.ANGLE_BAD) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean linearNearbyBlocks(LineString segment, double tolerance) {
        List<IndexedRestriction> candidates = index.querySpecial(segment.getEnvelopeInternal());
        Set<String> seen = new LinkedHashSet<String>();
        for (IndexedRestriction restriction : candidates) {
            if (!seen.add(restriction.getId())) {
                continue;
            }
            if (restriction.isProhibited() || restriction.isPolygonBarrier()) {
                continue;
            }
            Geometry corridor = restriction.getLinearCorridorShrunk();
            if (corridor.isEmpty() || !restriction.corridorShrunkIntersects(segment)) {
                continue;
            }
            Geometry source = restriction.getSourceGeometry();
            Geometry tieIn = tieInExemption(segment, restriction);
            if (source != null && !source.isEmpty() && segment.intersects(source)) {
                Geometry exempt = exemptZoneAroundCrossings(segment, restriction);
                if (tieIn != null) {
                    exempt = unionSafe(exempt, tieIn);
                }
                Geometry checked = differenceSafe(segment, exempt);
                if (checked == null || checked.isEmpty() || !checked.intersects(corridor)) {
                    continue;
                }
                return true;
            }
            if (tieIn != null) {
                Geometry checked = differenceSafe(segment, tieIn);
                if (checked == null || checked.isEmpty() || !checked.intersects(corridor)) {
                    continue;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Врезка в существующую тепловую сеть — это не «пересечение без врезки» (табл. 2):
     * у конца участка, лежащего на трубе (камера присоединения), отступ и спецпроход
     * к этой трубе не применяются в радиусе коридора + зоны спецучастка.
     * Для остальных типов ограничений освобождения нет.
     */
    private Geometry tieInExemption(LineString segment, IndexedRestriction restriction) {
        if (!EXISTING_HEAT_NETWORK_KEY.equals(restriction.getRulesKey())) {
            return null;
        }
        Geometry source = restriction.getSourceGeometry();
        if (source == null || source.isEmpty() || segment.getNumPoints() < 2) {
            return null;
        }
        double radius = restriction.getRule().getZoneMarginM();
        Geometry corridor = restriction.getLinearCorridor();
        if (corridor != null && !corridor.isEmpty()) {
            try {
                radius += source.distance(corridor.getBoundary());
            } catch (RuntimeException ignored) {
                radius += 2.0;
            }
        }
        Geometry zone = null;
        Coordinate[] ends = {segment.getCoordinateN(0), segment.getCoordinateN(segment.getNumPoints() - 1)};
        for (Coordinate end : ends) {
            org.locationtech.jts.geom.Point p = segment.getFactory().createPoint(end);
            if (source.distance(p) > TIE_IN_ON_PIPE_M) {
                continue;
            }
            Geometry disk = p.buffer(radius);
            zone = zone == null ? disk : unionSafe(zone, disk);
        }
        return zone;
    }

    private static Geometry unionSafe(Geometry a, Geometry b) {
        if (a == null || a.isEmpty()) {
            return b;
        }
        if (b == null || b.isEmpty()) {
            return a;
        }
        try {
            return a.union(b);
        } catch (RuntimeException ex) {
            return a;
        }
    }

    private static final String EXISTING_HEAT_NETWORK_KEY = IngestRestrictionsLoader.EXISTING_HEAT_NETWORK_KEY;
    /** Конец участка считается лежащим на трубе (камера присоединения) при таком удалении, м. */
    private static final double TIE_IN_ON_PIPE_M = 0.5;

    private Geometry exemptZoneAroundCrossings(LineString segment, IndexedRestriction restriction) {
        Geometry source = restriction.getSourceGeometry();
        if (source == null || source.isEmpty()) {
            return segment.getFactory().createEmpty(2);
        }
        try {
            Geometry inter = segment.intersection(source);
            if (inter.isEmpty()) {
                return segment.getFactory().createEmpty(2);
            }
            double margin = restriction.getRule().getZoneMarginM();
            if (margin <= 0) {
                margin = 2.0;
            }
            Geometry corridor = restriction.getLinearCorridor();
            double radius = margin;
            if (corridor != null && !corridor.isEmpty()) {
                try {
                    double toBoundary = source.distance(corridor.getBoundary());
                    if (toBoundary > radius) {
                        radius = toBoundary;
                    }
                } catch (RuntimeException ignored) {
                    // оставляем zone_margin
                }
            }
            return inter.buffer(radius + rulesConfig.getGeometryToleranceM());
        } catch (RuntimeException ex) {
            return segment.getFactory().createEmpty(2);
        }
    }

    private boolean polygonOffsetBlocks(LineString segment, IndexedRestriction restriction, BarrierEdges edges) {
        Geometry ring = restriction.getPolygonOffsetCorridorShrunk();
        if (ring == null || ring.isEmpty()) {
            return false;
        }
        try {
            if (!restriction.offsetShrunkIntersects(segment)) {
                return false;
            }
        } catch (RuntimeException ex) {
            return true;
        }
        // AUDIT-24.09 (Claude): по звеньям. Звено, которое заходит в полосу отступа (1,5 м + W/2)
        // и НЕ пересекает дорогу под допустимым углом, — нарушение отступа. У пересекающего звена
        // отступ внутри зоны спецпрохода (полигон + 3 м) не проверяется (Разъяснение №7).
        Geometry exempt = restriction.getSpecialZone();
        for (int i = 0; i < edges.size(); i++) {
            LineString edge = edges.edge(i);
            if (edge == null) {
                continue;
            }
            try {
                // AUDIT-13: подготовленная полоса отступа (та же геометрия, тот же ответ, что edge.intersects(ring))
                if (!restriction.offsetShrunkIntersects(edge)) {
                    continue;
                }
            } catch (RuntimeException ex) {
                return true;
            }
            if (!isPermittedPolygonCrossing(edges, i)) {
                return true;
            }
            // AUDIT-13: звено, не задевающее зону спецпрохода, целиком «нарушающее» (edge \ zone = edge), и оно уже
            // задевает полосу отступа — оверлей difference не нужен. Если полоса отступа строго внутри зоны
            // спецпрохода, часть звена вне зоны её не задевает — тоже без оверлея.
            boolean touchesExempt;
            try {
                touchesExempt = restriction.specialZoneIntersects(edge);
            } catch (RuntimeException ex) {
                touchesExempt = true;
            }
            if (!touchesExempt) {
                return true;
            }
            if (restriction.offsetInsideSpecial()) {
                continue;
            }
            Geometry violating = differenceSafe(edge, exempt);
            if (violating == null || violating.isEmpty()) {
                continue;
            }
            try {
                if (violating.intersects(ring)) {
                    return true;
                }
            } catch (RuntimeException ex) {
                return true;
            }
        }
        return false;
    }

    private boolean isPermittedPolygonCrossing(BarrierEdges edges, int i) {
        int parts = edges.restriction.sourcePolygons().size();
        for (int part = 0; part < parts; part++) {
            if (edges.state(i, part) == BarrierEdges.NO_CROSSING) {
                continue;
            }
            if (edges.angleValid(i, part)) {
                return true;
            }
        }
        return false;
    }

    private static Geometry differenceSafe(Geometry segment, Geometry exempt) {
        if (exempt == null || exempt.isEmpty()) {
            return segment;
        }
        try {
            return segment.difference(exempt);
        } catch (RuntimeException ex) {
            return segment;
        }
    }

    private void assertBundleDn(int candidateDn) {
        if (candidateDn > 0 && bundleDn > 0 && candidateDn != bundleDn) {
            throw new RulesException("Бандл собран под ДУ " + bundleDn
                    + ", запрошен ДУ " + candidateDn
                    + ": пересоберите бандл через RestrictionEngineFactory");
        }
    }

    private Geometry specialGeometryForCrossing(IndexedRestriction restriction) {
        if (restriction.isPolygonBarrier()) {
            return restriction.getSpecialZone();
        }
        Geometry source = restriction.getSourceGeometry();
        if (source != null && !source.isEmpty()) {
            return source;
        }
        return restriction.getSpecialZone();
    }

    private List<CrossingResult> expandPointCrossings(List<CrossingResult> raw, LineString segment) {
        List<CrossingResult> expanded = new ArrayList<>();
        for (CrossingResult crossing : raw) {
            IndexedRestriction restriction = byId.get(crossing.getRestrictionId());
            if (restriction == null || restriction.isPolygonBarrier()
                    || restriction.getRule().getZoneKind() != ru.heatnet.calc.reference.RestrictionRule.ZoneKind.POINT_MARGIN) {
                expanded.add(crossing);
                continue;
            }
            Geometry inter = crossing.getIntersection();
            if (inter == null || inter.isEmpty()) {
                expanded.add(crossing);
                continue;
            }
            double margin = restriction.getRule().getZoneMarginM();
            Coordinate[] pts = inter.getCoordinates();
            for (int i = 0; i < pts.length; i++) {
                LineString piece = clipAround(segment, pts[i], margin);
                if (piece != null) {
                    expanded.add(new CrossingResult(crossing.getRestrictionId(), crossing.getRestrictionType(),
                            crossing.getKSpec(), piece, crossing.getCrossingAngleDeg()));
                }
            }
        }
        return expanded;
    }

    private LineString specialSectionGeometry(LineString segment, CrossingResult crossing) {
        Geometry inter = crossing.getIntersection();
        if (inter instanceof LineString) {
            LineString ls = (LineString) inter;
            if (ls.getNumPoints() > 2) {
                Coordinate a = ls.getCoordinateN(0);
                Coordinate b = ls.getCoordinateN(ls.getNumPoints() - 1);
                return segment.getFactory().createLineString(new org.locationtech.jts.geom.Coordinate[] {a, b});
            }
            return ls;
        }
        LineString fromZone = extractSubLine(segment, inter);
        if (fromZone != null) {
            return fromZone;
        }
        return segment;
    }

    private LineString clipAround(LineString segment, org.locationtech.jts.geom.Coordinate point, double marginM) {
        double pos = positionAlong(segment, point);
        double from = Math.max(0.0, pos - marginM);
        double to = Math.min(segment.getLength(), pos + marginM);
        if (to - from < 1e-6) {
            return null;
        }
        org.locationtech.jts.geom.Coordinate a = pointAt(segment, from);
        org.locationtech.jts.geom.Coordinate b = pointAt(segment, to);
        return segment.getFactory().createLineString(new org.locationtech.jts.geom.Coordinate[] {a, b});
    }

    private static double positionAlong(LineString path, org.locationtech.jts.geom.Coordinate point) {
        double best = 0;
        double bestDist = Double.MAX_VALUE;
        double acc = 0;
        for (int i = 0; i < path.getNumPoints() - 1; i++) {
            org.locationtech.jts.geom.Coordinate a = path.getCoordinateN(i);
            org.locationtech.jts.geom.Coordinate b = path.getCoordinateN(i + 1);
            double segLen = a.distance(b);
            double abx = b.x - a.x;
            double aby = b.y - a.y;
            double len2 = abx * abx + aby * aby;
            double t = len2 < 1e-12 ? 0 : ((point.x - a.x) * abx + (point.y - a.y) * aby) / len2;
            t = Math.max(0.0, Math.min(1.0, t));
            org.locationtech.jts.geom.Coordinate proj = new org.locationtech.jts.geom.Coordinate(a.x + t * abx, a.y + t * aby);
            double d = point.distance(proj);
            if (d < bestDist) {
                bestDist = d;
                best = acc + t * segLen;
            }
            acc += segLen;
        }
        return best;
    }

    private static org.locationtech.jts.geom.Coordinate pointAt(LineString path, double targetM) {
        double acc = 0;
        for (int i = 0; i < path.getNumPoints() - 1; i++) {
            org.locationtech.jts.geom.Coordinate a = path.getCoordinateN(i);
            org.locationtech.jts.geom.Coordinate b = path.getCoordinateN(i + 1);
            double segLen = a.distance(b);
            if (acc + segLen >= targetM - 1e-9) {
                if (segLen < 1e-9) {
                    return a;
                }
                double t = (targetM - acc) / segLen;
                return new org.locationtech.jts.geom.Coordinate(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
            }
            acc += segLen;
        }
        return path.getCoordinateN(path.getNumPoints() - 1);
    }

    private static Polygon firstPolygon(Geometry geometry) {
        List<Polygon> polygons = PortalGateGenerator.polygonsOf(geometry);
        return polygons.isEmpty() ? null : polygons.get(0);
    }
}
