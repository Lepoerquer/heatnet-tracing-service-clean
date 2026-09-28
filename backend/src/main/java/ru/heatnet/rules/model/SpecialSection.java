package ru.heatnet.rules.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.locationtech.jts.geom.LineString;

/**
 * Участок трассы в зоне спецпрохода.
 * При наложении нескольких зон — один участок с max(K_спец) (протокол 16.09, п. 9).
 */
public final class SpecialSection {

    private final LineString geometry;
    private final double kSpec;
    private final Set<String> restrictionTypes;

    public SpecialSection(LineString geometry, double kSpec, Set<String> restrictionTypes) {
        this.geometry = geometry;
        this.kSpec = kSpec;
        this.restrictionTypes = Collections.unmodifiableSet(new LinkedHashSet<>(restrictionTypes));
    }

    public LineString getGeometry() {
        return geometry;
    }

    public double getKSpec() {
        return kSpec;
    }

    public Set<String> getRestrictionTypes() {
        return restrictionTypes;
    }
}
