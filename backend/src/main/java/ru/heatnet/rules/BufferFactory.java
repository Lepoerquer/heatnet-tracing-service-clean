package ru.heatnet.rules;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;

import ru.heatnet.calc.reference.DepthRules;
import ru.heatnet.calc.reference.GabaritTable;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.calc.reference.RestrictionRule;
import ru.heatnet.rules.model.RestrictionFeature;

/**
 * Буферы запретных зон: отступ между внешними границами габаритов (табл. 4.2, 4.3, 5.1).
 */
public final class BufferFactory {

    private static final int BUFFER_QUADRANT_SEGMENTS = 8;

    private final ReferenceData referenceData;
    private final GeometryFactory geometryFactory;

    public BufferFactory(ReferenceData referenceData, GeometryFactory geometryFactory) {
        this.referenceData = referenceData;
        this.geometryFactory = geometryFactory;
    }

    /** Полуширина пары труб новой сети, м. */
    public double pairHalfWidthM(int candidateDn) {
        return referenceData.getGabarits().spec(candidateDn).getWidthM() / 2.0;
    }

    /** Полуширина линейного препятствия по табл. 4.3, м. */
    public double obstacleHalfWidthM(String rulesKey, int candidateDn) {
        return obstacleHalfWidthM(rulesKey, candidateDn, null);
    }

    /**
     * @param obstacleDn ДУ самого препятствия: габарит существующей тепловой сети берётся по её ДУ
     *                   (§4 приложения: «для существующей тепловой сети — габарит по таблице 1»),
     *                   а не по ДУ новой сети
     */
    public double obstacleHalfWidthM(String rulesKey, int candidateDn, Integer obstacleDn) {
        DepthRules depth = referenceData.getDepth();
        DepthRules.Utility utility = depth.getUtilities().get(rulesKey);
        if (utility == null) {
            return 0.0;
        }
        if (utility.isGabaritByDn()) {
            int dn = obstacleDn != null && referenceData.getGabarits().all().containsKey(obstacleDn)
                    ? obstacleDn : nearestGabaritDn(obstacleDn, candidateDn);
            return referenceData.getGabarits().spec(dn).getWidthM() / 2.0;
        }
        Double width = utility.getWidthM();
        if (width == null) {
            throw new RulesException("depth-rules: для '" + rulesKey + "' не задана ширина габарита");
        }
        return width / 2.0;
    }

    /** Ближайший не меньший ДУ табл. 1 для нестандартного ДУ препятствия; без ДУ — ДУ новой сети. */
    private int nearestGabaritDn(Integer obstacleDn, int candidateDn) {
        if (obstacleDn == null || obstacleDn <= 0) {
            return candidateDn;
        }
        int best = -1;
        int largest = candidateDn;
        for (Integer dn : referenceData.getGabarits().all().keySet()) {
            largest = Math.max(largest, dn);
            if (dn >= obstacleDn && (best < 0 || dn < best)) {
                best = dn;
            }
        }
        return best > 0 ? best : largest;
    }

    /**
     * Зона, в которую новая трасса не может заходить (запретные типы и площадные барьеры road/tram).
     */
    public Geometry buildBlockedZone(RestrictionFeature feature, int candidateDn) {
        RestrictionRule rule = feature.getRule();
        Geometry source = feature.getGeometryUtm();
        double pairHalf = pairHalfWidthM(candidateDn);

        if (rule.isProhibited()) {
            double distance = rule.minOffsetM(candidateDn) + pairHalf;
            return buffer(source, distance);
        }

        if (isPolygonBarrier(rule)) {
            return source;
        }

        return geometryFactory.createEmpty(2);
    }

    /**
     * Кольцо мин. отступа вокруг площадного барьера (road/tram: 1,5 м + W_пары/2).
     * Внутри полигона отступ не действует — там спецпроход.
     */
    public Geometry buildPolygonOffsetCorridor(RestrictionFeature feature, int candidateDn) {
        RestrictionRule rule = feature.getRule();
        if (!isPolygonBarrier(rule)) {
            return geometryFactory.createEmpty(2);
        }
        Geometry source = feature.getGeometryUtm();
        double distance = rule.minOffsetM(candidateDn) + pairHalfWidthM(candidateDn);
        Geometry outer = buffer(source, distance);
        try {
            Geometry ring = outer.difference(source);
            return ring == null || ring.isEmpty() ? geometryFactory.createEmpty(2) : ring;
        } catch (RuntimeException ex) {
            return outer;
        }
    }

    /**
     * Зона спецпрохода: полигон + margin или точка + margin (табл. 5.1).
     */
    public Geometry buildSpecialZone(RestrictionFeature feature) {
        RestrictionRule rule = feature.getRule();
        if (rule.isProhibited()) {
            return geometryFactory.createEmpty(2);
        }
        Geometry source = feature.getGeometryUtm();
        switch (rule.getZoneKind()) {
            case POLYGON_MARGIN:
                return buffer(source, rule.getZoneMarginM());
            case POINT_MARGIN:
                return buffer(source, rule.getZoneMarginM());
            case NONE:
                return source;
            default:
                return geometryFactory.createEmpty(2);
        }
    }

    /**
     * Коридор минимального отступа для линейных спецобъектов (газ, кабель, теплосеть).
     * Пересечение коридора допускается как спецпроход, но не «проезд внутри» без учёта.
     */
    public Geometry buildLinearCorridor(RestrictionFeature feature, int candidateDn) {
        RestrictionRule rule = feature.getRule();
        if (rule.isProhibited() || isPolygonBarrier(rule)) {
            return geometryFactory.createEmpty(2);
        }
        if (!(feature.getGeometryUtm() instanceof LineString) && feature.getGeometryUtm().getDimension() != 1) {
            return geometryFactory.createEmpty(2);
        }
        double distance = rule.minOffsetM(candidateDn)
                + obstacleHalfWidthM(feature.getRulesKey(), candidateDn, feature.getObstacleDn())
                + pairHalfWidthM(candidateDn);
        return buffer(feature.getGeometryUtm(), distance);
    }

    public boolean isPolygonBarrier(RestrictionRule rule) {
        return !rule.isProhibited()
                && rule.getZoneKind() == RestrictionRule.ZoneKind.POLYGON_MARGIN;
    }

    private Geometry buffer(Geometry geometry, double distanceM) {
        if (distanceM <= 0.0) {
            return geometry.copy();
        }
        BufferParameters params = new BufferParameters();
        params.setQuadrantSegments(BUFFER_QUADRANT_SEGMENTS);
        params.setEndCapStyle(BufferParameters.CAP_ROUND);
        params.setJoinStyle(BufferParameters.JOIN_MITRE);
        // AUDIT-24.09 (Claude): предел скоса 1,0. С пределом по умолчанию (5,0) угол зоны у прямого
        // угла здания уходил на d·√2 (5,7 м → 8,0 м), у острых — до 5·d: трасса обходила фантомные
        // «уши» зоны отступа, а у сложных корпусов (кольцевой дом ОКС 10) исчезали все допустимые
        // точки захода. Скос на расстоянии 1,0·d касается окружности радиуса d — зона остаётся
        // консервативной (не меньше требуемого расстояния), но почти точной (≤ +8 % у 90°).
        params.setMitreLimit(1.0);
        return BufferOp.bufferOp(geometry, distanceM, params);
    }
}
