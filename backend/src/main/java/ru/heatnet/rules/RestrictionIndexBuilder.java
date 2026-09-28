package ru.heatnet.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.rules.model.PortalGate;
import ru.heatnet.rules.model.RestrictionFeature;

/** Сборка STRtree и порталов из списка ограничений. */
public final class RestrictionIndexBuilder {

    private final BufferFactory bufferFactory;
    private final PortalGateGenerator portalGateGenerator;
    private final GeometryFactory geometryFactory;

    public RestrictionIndexBuilder(ReferenceData referenceData, GeometryFactory geometryFactory) {
        this.geometryFactory = geometryFactory;
        this.bufferFactory = new BufferFactory(referenceData, geometryFactory);
        this.portalGateGenerator = new PortalGateGenerator(geometryFactory);
    }

    public BuiltIndex build(List<RestrictionFeature> features, int referenceDn) {
        return build(features, referenceDn, 0.0);
    }

    public BuiltIndex build(List<RestrictionFeature> features, int referenceDn, double toleranceM) {
        List<IndexedRestriction> indexed = new ArrayList<>();
        List<PortalGate> allGates = new ArrayList<>();
        Map<String, IndexedRestriction> byId = new LinkedHashMap<>();

        for (RestrictionFeature source : features) {
            RestrictionFeature feature = source;
            // AUDIT-12 (Claude, 24.09): линейная дорога/трамвай → узкая полоса (см. IngestRestrictionsLoader)
            Geometry strip = IngestRestrictionsLoader.linearBarrierAsStrip(source.getRule(), source.getGeometryUtm());
            if (strip != source.getGeometryUtm()) {
                feature = new RestrictionFeature(source.getId(), source.getRulesKey(), source.getRule(), strip,
                        source.getObstacleDn());
            }
            Geometry blocked = bufferFactory.buildBlockedZone(feature, referenceDn);
            Geometry special = bufferFactory.buildSpecialZone(feature);
            Geometry corridor = bufferFactory.buildLinearCorridor(feature, referenceDn);
            Geometry offset = bufferFactory.buildPolygonOffsetCorridor(feature, referenceDn);

            List<PortalGate> gates = new ArrayList<>();
            if (bufferFactory.isPolygonBarrier(feature.getRule()) && !feature.getRule().isProhibited()) {
                gates = portalGateGenerator.generate(feature.getId(), feature.getGeometryUtm(), feature.getRule());
                allGates.addAll(gates);
            }

            IndexedRestriction item = new IndexedRestriction(feature, blocked, special, corridor, offset, gates,
                    toleranceM);
            indexed.add(item);
            byId.put(feature.getId(), item);
        }

        RestrictionSpatialIndex spatialIndex = RestrictionSpatialIndex.build(indexed);
        return new BuiltIndex(spatialIndex, bufferFactory, allGates, byId);
    }

    public static final class BuiltIndex {
        private final RestrictionSpatialIndex spatialIndex;
        private final BufferFactory bufferFactory;
        private final List<PortalGate> portalGates;
        private final Map<String, IndexedRestriction> byId;

        BuiltIndex(RestrictionSpatialIndex spatialIndex, BufferFactory bufferFactory,
                   List<PortalGate> portalGates, Map<String, IndexedRestriction> byId) {
            this.spatialIndex = spatialIndex;
            this.bufferFactory = bufferFactory;
            this.portalGates = portalGates;
            this.byId = byId;
        }

        public RestrictionSpatialIndex getSpatialIndex() {
            return spatialIndex;
        }

        public BufferFactory getBufferFactory() {
            return bufferFactory;
        }

        public List<PortalGate> getPortalGates() {
            return portalGates;
        }

        public Map<String, IndexedRestriction> getById() {
            return byId;
        }
    }
}
