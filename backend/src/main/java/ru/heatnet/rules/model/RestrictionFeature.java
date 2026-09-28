package ru.heatnet.rules.model;

import org.locationtech.jts.geom.Geometry;

import ru.heatnet.calc.reference.RestrictionRule;

/** Ограничение из ingest в метрах (EPSG:32637) с привязкой к rules.yaml. */
public final class RestrictionFeature {

    private final String id;
    private final String rulesKey;
    private final RestrictionRule rule;
    private final Geometry geometryUtm;
    private final Integer obstacleDn;

    public RestrictionFeature(String id, String rulesKey, RestrictionRule rule, Geometry geometryUtm) {
        this(id, rulesKey, rule, geometryUtm, null);
    }

    /**
     * @param obstacleDn ДУ самого препятствия, если его габарит задаётся по табл. 1
     *                   (существующая тепловая сеть, §4 приложения); иначе {@code null}
     */
    public RestrictionFeature(String id, String rulesKey, RestrictionRule rule, Geometry geometryUtm,
                              Integer obstacleDn) {
        this.id = id;
        this.rulesKey = rulesKey;
        this.rule = rule;
        this.geometryUtm = geometryUtm;
        this.obstacleDn = obstacleDn;
    }

    public String getId() {
        return id;
    }

    public String getRulesKey() {
        return rulesKey;
    }

    public RestrictionRule getRule() {
        return rule;
    }

    public Geometry getGeometryUtm() {
        return geometryUtm;
    }

    public Integer getObstacleDn() {
        return obstacleDn;
    }
}
