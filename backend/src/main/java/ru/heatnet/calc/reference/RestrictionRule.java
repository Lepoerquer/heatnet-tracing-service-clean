package ru.heatnet.calc.reference;

import java.util.Collections;
import java.util.List;

import ru.heatnet.calc.CalcException;

/** Правило табл. 5.1 для одного restriction_type (или oks_existing). */
public final class RestrictionRule {

    public enum Kind { PROHIBITED, SPECIAL }

    public enum ZoneKind { NONE, POLYGON_MARGIN, POINT_MARGIN }

    private final String type;
    private final Kind kind;
    private final Double minOffsetM;
    private final List<DnBand> minOffsetByDn;
    private final Double minCrossingAngleDeg;
    private final Double kSpec;
    private final ZoneKind zoneKind;
    private final double zoneMarginM;
    private final boolean teamDefault;

    public RestrictionRule(String type, Kind kind, Double minOffsetM, List<DnBand> minOffsetByDn,
                           Double minCrossingAngleDeg, Double kSpec, ZoneKind zoneKind,
                           double zoneMarginM, boolean teamDefault) {
        this.type = type;
        this.kind = kind;
        this.minOffsetM = minOffsetM;
        this.minOffsetByDn = minOffsetByDn == null
                ? Collections.<DnBand>emptyList() : Collections.unmodifiableList(minOffsetByDn);
        this.minCrossingAngleDeg = minCrossingAngleDeg;
        this.kSpec = kSpec;
        this.zoneKind = zoneKind;
        this.zoneMarginM = zoneMarginM;
        this.teamDefault = teamDefault;
        if (minOffsetM == null && this.minOffsetByDn.isEmpty()) {
            throw new CalcException("rules.yaml: для '" + type + "' не задан минимальный отступ");
        }
        if (kind == Kind.SPECIAL && (kSpec == null || kSpec < 1.0)) {
            throw new CalcException("rules.yaml: для спецпрохода '" + type + "' k_spec должен быть >= 1");
        }
    }

    public String getType() {
        return type;
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isProhibited() {
        return kind == Kind.PROHIBITED;
    }

    /** Минимальный горизонтальный отступ, м, для новой сети заданного DN. */
    public double minOffsetM(int newNetworkDn) {
        if (!minOffsetByDn.isEmpty()) {
            for (DnBand band : minOffsetByDn) {
                if (band.contains(newNetworkDn)) {
                    return band.getValue();
                }
            }
            throw new CalcException("rules.yaml: для '" + type + "' нет отступа для DN" + newNetworkDn);
        }
        return minOffsetM;
    }

    /** Минимальный угол пересечения, градусы; null — не задаётся. */
    public Double getMinCrossingAngleDeg() {
        return minCrossingAngleDeg;
    }

    /** Kспец; null для запрещённых пересечений. */
    public Double getKSpec() {
        return kSpec;
    }

    public ZoneKind getZoneKind() {
        return zoneKind;
    }

    public double getZoneMarginM() {
        return zoneMarginM;
    }

    /** true — правило является дефолтом команды, а не нормой приложения. */
    public boolean isTeamDefault() {
        return teamDefault;
    }
}
