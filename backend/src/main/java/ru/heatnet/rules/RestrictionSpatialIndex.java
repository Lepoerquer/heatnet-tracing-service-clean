package ru.heatnet.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;

/**
 * STRtree по зонам ограничений в EPSG:32637.
 */
public final class RestrictionSpatialIndex {

    private final STRtree blockedIndex;
    private final STRtree specialIndex;
    private final List<IndexedRestriction> all;

    private RestrictionSpatialIndex(STRtree blockedIndex, STRtree specialIndex, List<IndexedRestriction> all) {
        this.blockedIndex = blockedIndex;
        this.specialIndex = specialIndex;
        this.all = Collections.unmodifiableList(all);
    }

    public static RestrictionSpatialIndex build(List<IndexedRestriction> restrictions) {
        STRtree blocked = new STRtree();
        STRtree special = new STRtree();
        for (IndexedRestriction r : restrictions) {
            if (!r.getBlockedZone().isEmpty()) {
                blocked.insert(r.getBlockedZone().getEnvelopeInternal(), r);
            }
            if (!r.getPolygonOffsetCorridor().isEmpty()) {
                blocked.insert(r.getPolygonOffsetCorridor().getEnvelopeInternal(), r);
            }
            Geometry specialGeom = r.getSpecialZone();
            if (!specialGeom.isEmpty()) {
                special.insert(specialGeom.getEnvelopeInternal(), r);
            }
            if (!r.getLinearCorridor().isEmpty()) {
                special.insert(r.getLinearCorridor().getEnvelopeInternal(), r);
            }
        }
        blocked.build();
        special.build();
        return new RestrictionSpatialIndex(blocked, special, restrictions);
    }

    public List<IndexedRestriction> queryBlocked(Envelope searchEnv) {
        return query(blockedIndex, searchEnv);
    }

    public List<IndexedRestriction> querySpecial(Envelope searchEnv) {
        return query(specialIndex, searchEnv);
    }

    public List<IndexedRestriction> allRestrictions() {
        return all;
    }

    @SuppressWarnings("unchecked")
    private static List<IndexedRestriction> query(STRtree tree, Envelope searchEnv) {
        List<IndexedRestriction> hits = (List<IndexedRestriction>) tree.query(searchEnv);
        if (hits.isEmpty()) {
            return Collections.emptyList();
        }
        // Одно ограничение вставляется в дерево дважды: по specialZone и по linearCorridor
        // (для blocked — по blockedZone и polygonOffsetCorridor). Дубли схлопываем, иначе одно
        // физическое пересечение превращается в два спецучастка и оплачивается дважды (§4).
        Map<String, IndexedRestriction> distinct = new LinkedHashMap<>();
        for (IndexedRestriction r : hits) {
            distinct.put(r.getId(), r);
        }
        return new ArrayList<>(distinct.values());
    }
}
