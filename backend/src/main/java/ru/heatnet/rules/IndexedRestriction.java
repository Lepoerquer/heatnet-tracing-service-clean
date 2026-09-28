package ru.heatnet.rules;

import java.util.Collections;
import java.util.List;

import org.locationtech.jts.geom.Geometry;

import ru.heatnet.calc.reference.RestrictionRule;
import ru.heatnet.rules.model.PortalGate;
import ru.heatnet.rules.model.RestrictionFeature;

/** Подготовленное ограничение с зонами для STRtree и порталами. */
final class IndexedRestriction {

    private final RestrictionFeature feature;
    private final Geometry blockedZone;
    private final Geometry blockedZoneShrunk;
    private final Geometry specialZone;
    private final Geometry linearCorridor;
    private final Geometry linearCorridorShrunk;
    private final Geometry polygonOffsetCorridor;
    private final Geometry polygonOffsetCorridorShrunk;
    private final List<PortalGate> portalGates;

    IndexedRestriction(RestrictionFeature feature, Geometry blockedZone, Geometry specialZone,
                       Geometry linearCorridor, Geometry polygonOffsetCorridor,
                       List<PortalGate> portalGates, double toleranceM) {
        this.feature = feature;
        this.blockedZone = blockedZone;
        this.blockedZoneShrunk = shrink(blockedZone, toleranceM);
        this.specialZone = specialZone;
        this.linearCorridor = linearCorridor;
        this.linearCorridorShrunk = shrink(linearCorridor, toleranceM);
        this.polygonOffsetCorridor = polygonOffsetCorridor == null
                ? feature.getGeometryUtm().getFactory().createEmpty(2) : polygonOffsetCorridor;
        this.polygonOffsetCorridorShrunk = shrink(this.polygonOffsetCorridor, toleranceM);
        this.portalGates = portalGates == null ? Collections.<PortalGate>emptyList() : portalGates;
    }

    RestrictionFeature getFeature() {
        return feature;
    }

    RestrictionRule getRule() {
        return feature.getRule();
    }

    String getId() {
        return feature.getId();
    }

    String getRulesKey() {
        return feature.getRulesKey();
    }

    Geometry getSourceGeometry() {
        return feature.getGeometryUtm();
    }

    Geometry getBlockedZone() {
        return blockedZone;
    }

    Geometry getBlockedZoneShrunk() {
        return blockedZoneShrunk;
    }

    Geometry getSpecialZone() {
        return specialZone;
    }

    Geometry getLinearCorridor() {
        return linearCorridor;
    }

    Geometry getLinearCorridorShrunk() {
        return linearCorridorShrunk;
    }

    Geometry getPolygonOffsetCorridor() {
        return polygonOffsetCorridor;
    }

    Geometry getPolygonOffsetCorridorShrunk() {
        return polygonOffsetCorridorShrunk;
    }

    List<PortalGate> getPortalGates() {
        return portalGates;
    }

    // AUDIT-12 (Claude, 24.09): подготовленные (индексированные) зоны для intersects. Проверка отрезка против
    // буфера здания/коридора — самая частая операция маршрутизации (рёбра графа видимости, выходы семян,
    // спрямление): Geometry.intersects без подготовки перебирает все звенья буфера на каждый вызов
    // (~55 % времени плана на конкурсном наборе). Результат тот же, что у Geometry.intersects.
    private volatile org.locationtech.jts.geom.prep.PreparedGeometry blockedPrepared;
    private volatile org.locationtech.jts.geom.prep.PreparedGeometry corridorPrepared;
    private volatile org.locationtech.jts.geom.prep.PreparedGeometry offsetPrepared;

    boolean blockedShrunkIntersects(Geometry g) {
        if (blockedZoneShrunk == null || blockedZoneShrunk.isEmpty()) {
            return false;
        }
        org.locationtech.jts.geom.prep.PreparedGeometry p = blockedPrepared;
        if (p == null) {
            p = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(blockedZoneShrunk);
            blockedPrepared = p;
        }
        return p.intersects(g);
    }

    boolean corridorShrunkIntersects(Geometry g) {
        if (linearCorridorShrunk == null || linearCorridorShrunk.isEmpty()) {
            return false;
        }
        org.locationtech.jts.geom.prep.PreparedGeometry p = corridorPrepared;
        if (p == null) {
            p = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(linearCorridorShrunk);
            corridorPrepared = p;
        }
        return p.intersects(g);
    }

    boolean offsetShrunkIntersects(Geometry g) {
        if (polygonOffsetCorridorShrunk == null || polygonOffsetCorridorShrunk.isEmpty()) {
            return false;
        }
        org.locationtech.jts.geom.prep.PreparedGeometry p = offsetPrepared;
        if (p == null) {
            p = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(polygonOffsetCorridorShrunk);
            offsetPrepared = p;
        }
        return p.intersects(g);
    }

    private Geometry combinedSpecial;

    /** Спецзона ∪ линейный коридор; считается один раз (раньше — на каждом запросе ребра). */
    Geometry getCombinedSpecial() {
        Geometry cached = combinedSpecial;
        if (cached != null) {
            return cached;
        }
        Geometry zone = specialZone;
        Geometry corridor = linearCorridor;
        if (zone == null || zone.isEmpty()) {
            cached = corridor;
        } else if (corridor == null || corridor.isEmpty()) {
            cached = zone;
        } else {
            try {
                cached = zone.union(corridor);
            } catch (RuntimeException ex) {
                cached = zone;
            }
        }
        combinedSpecial = cached;
        return cached;
    }

    // AUDIT-13 (Claude, 25.09): подготовленные полигоны исходной геометрии и зоны спецпрохода площадного барьера
    // (дорога/трамвай). Проверки «звено заходит внутрь полигона дороги» и «звено задевает зону спецпрохода» делались
    // оверлеем JTS (intersection/difference) для КАЖДОГО звена-кандидата, чья рамка задела буфер дороги, — даже когда
    // звено дороги не касается (на городском наборе s1 ~50 % времени плана). Теперь сначала — точная проверка
    // {@code intersects} по подготовленной геометрии: пустое пересечение ⇔ нет {@code intersects}, поэтому
    // результат прежний, а оверлей выполняется только для звеньев, действительно задевающих полигон.
    private volatile List<org.locationtech.jts.geom.Polygon> sourcePolygons;
    private volatile org.locationtech.jts.geom.prep.PreparedGeometry[] sourcePrepared;
    private volatile org.locationtech.jts.geom.prep.PreparedGeometry specialPrepared;

    /** Полигоны исходной геометрии (как {@code PortalGateGenerator.polygonsOf}). */
    List<org.locationtech.jts.geom.Polygon> sourcePolygons() {
        List<org.locationtech.jts.geom.Polygon> p = sourcePolygons;
        if (p == null) {
            p = Collections.unmodifiableList(PortalGateGenerator.polygonsOf(getSourceGeometry()));
            sourcePolygons = p;
        }
        return p;
    }

    /** {@code sourcePolygons().get(part).intersects(g)} по подготовленной геометрии. */
    boolean sourcePolygonIntersects(int part, Geometry g) {
        org.locationtech.jts.geom.prep.PreparedGeometry[] arr = sourcePrepared;
        if (arr == null) {
            arr = new org.locationtech.jts.geom.prep.PreparedGeometry[sourcePolygons().size()];
            sourcePrepared = arr;
        }
        org.locationtech.jts.geom.prep.PreparedGeometry p = arr[part];
        if (p == null) {
            p = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(sourcePolygons().get(part));
            arr[part] = p;
        }
        return p.intersects(g);
    }

    /** {@code getSpecialZone().intersects(g)} по подготовленной геометрии; пустая зона — {@code false}. */
    boolean specialZoneIntersects(Geometry g) {
        if (specialZone == null || specialZone.isEmpty()) {
            return false;
        }
        org.locationtech.jts.geom.prep.PreparedGeometry p = specialPrepared;
        if (p == null) {
            p = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(specialZone);
            specialPrepared = p;
        }
        return p.intersects(g);
    }

    private volatile Boolean offsetInsideSpecial;

    /**
     * AUDIT-13: полоса отступа площадного барьера (полигон + 1,5 м + W/2) лежит строго внутри зоны спецпрохода
     * (полигон + 3 м) — не ближе 1 мм к её границе. Тогда часть звена вне зоны спецпрохода заведомо не задевает
     * полосу отступа (так при W/2 &lt; 1,5 м, то есть при ДУ ≤ 1000).
     */
    boolean offsetInsideSpecial() {
        Boolean v = offsetInsideSpecial;
        if (v == null) {
            boolean inside;
            try {
                inside = specialZone != null && !specialZone.isEmpty()
                        && polygonOffsetCorridorShrunk != null && !polygonOffsetCorridorShrunk.isEmpty()
                        && specialZone.contains(polygonOffsetCorridorShrunk)
                        && polygonOffsetCorridorShrunk.distance(specialZone.getBoundary()) > 1e-3;
            } catch (RuntimeException ex) {
                inside = false;
            }
            v = Boolean.valueOf(inside);
            offsetInsideSpecial = v;
        }
        return v.booleanValue();
    }

    boolean isProhibited() {
        return getRule().isProhibited();
    }

    boolean isPolygonBarrier() {
        return !isProhibited() && getRule().getZoneKind() == RestrictionRule.ZoneKind.POLYGON_MARGIN;
    }

    private static Geometry shrink(Geometry zone, double toleranceM) {
        if (zone == null || zone.isEmpty() || toleranceM <= 0.0) {
            return zone;
        }
        Geometry candidate = zone.buffer(-toleranceM);
        return candidate.isEmpty() ? zone : candidate;
    }
}
