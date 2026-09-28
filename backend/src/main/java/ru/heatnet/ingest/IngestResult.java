package ru.heatnet.ingest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Geometry;

import ru.heatnet.calc.model.ExistingNetwork;

/** Результат потокового разбора GeoJSON. */
public final class IngestResult {

    private final IngestReport report;
    private final ExistingNetwork existingNetwork;
    private final List<OksConnectionPoint> oksConnectionPoints;
    private final Map<String, Geometry> restrictionsWgs84;
    private final List<RawFeature> acceptedFeatures;
    private final Map<String, RawFeature> featuresById;

    public IngestResult(IngestReport report,
                        ExistingNetwork existingNetwork,
                        List<OksConnectionPoint> oksConnectionPoints,
                        Map<String, Geometry> restrictionsWgs84,
                        Collection<RawFeature> acceptedFeatures) {
        this.report = report;
        this.existingNetwork = existingNetwork;
        this.oksConnectionPoints = Collections.unmodifiableList(oksConnectionPoints);
        this.acceptedFeatures = Collections.unmodifiableList(new ArrayList<>(acceptedFeatures));
        Map<String, RawFeature> byId = new LinkedHashMap<>();
        Map<String, Geometry> restrictions = new LinkedHashMap<>();
        for (RawFeature feature : this.acceptedFeatures) {
            byId.put(feature.getId(), feature);
            if (feature.getKind() == RawFeature.Kind.RESTRICTION && feature.getGeometryWgs84() != null) {
                restrictions.put(feature.getId(), feature.getGeometryWgs84());
            }
        }
        if (restrictions.isEmpty() && restrictionsWgs84 != null && !restrictionsWgs84.isEmpty()) {
            restrictions.putAll(restrictionsWgs84);
        }
        this.featuresById = Collections.unmodifiableMap(byId);
        this.restrictionsWgs84 = Collections.unmodifiableMap(restrictions);
    }

    public IngestReport getReport() {
        return report;
    }

    public ExistingNetwork getExistingNetwork() {
        return existingNetwork;
    }

    public List<OksConnectionPoint> getOksConnectionPoints() {
        return oksConnectionPoints;
    }

    public Map<String, Geometry> getRestrictionsWgs84() {
        return restrictionsWgs84;
    }

    public List<RawFeature> getAcceptedFeatures() {
        return acceptedFeatures;
    }

    public RawFeature featureById(String id) {
        return featuresById.get(id);
    }

    public RawFeature oksFeature(String id) {
        RawFeature feature = featuresById.get(id);
        if (feature == null || feature.getKind() != RawFeature.Kind.OKS_CONNECTION_POINT) {
            return null;
        }
        return feature;
    }
}
