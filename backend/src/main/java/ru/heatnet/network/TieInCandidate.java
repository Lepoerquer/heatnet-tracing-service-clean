package ru.heatnet.network;

import org.locationtech.jts.geom.Coordinate;

import ru.heatnet.calc.model.TieInPoint;

/** Кандидат точки врезки с оценкой экономики (5 млн vs 5+камера). */
public final class TieInCandidate implements Comparable<TieInCandidate> {

    public enum Kind {
        EXISTING_CHAMBER,
        EXISTING_PIPE
    }

    private final String candidateId;
    private final Kind kind;
    private final TieInPoint tieIn;
    private final Coordinate locationUtm;
    private final double referenceDistanceM;
    private final long estimatedEntryCostRub;
    private final boolean usesExistingChamberOnly;

    public TieInCandidate(String candidateId, Kind kind, TieInPoint tieIn, Coordinate locationUtm,
                          double referenceDistanceM, long estimatedEntryCostRub, boolean usesExistingChamberOnly) {
        this.candidateId = candidateId;
        this.kind = kind;
        this.tieIn = tieIn;
        this.locationUtm = locationUtm;
        this.referenceDistanceM = referenceDistanceM;
        this.estimatedEntryCostRub = estimatedEntryCostRub;
        this.usesExistingChamberOnly = usesExistingChamberOnly;
    }

    public String getCandidateId() {
        return candidateId;
    }

    public Kind getKind() {
        return kind;
    }

    public TieInPoint getTieIn() {
        return tieIn;
    }

    public Coordinate getLocationUtm() {
        return locationUtm;
    }

    public double getReferenceDistanceM() {
        return referenceDistanceM;
    }

    public long getEstimatedEntryCostRub() {
        return estimatedEntryCostRub;
    }

    public boolean isUsesExistingChamberOnly() {
        return usesExistingChamberOnly;
    }

    @Override
    public int compareTo(TieInCandidate other) {
        if (this.usesExistingChamberOnly != other.usesExistingChamberOnly) {
            return this.usesExistingChamberOnly ? -1 : 1;
        }
        int costCmp = Long.compare(this.estimatedEntryCostRub, other.estimatedEntryCostRub);
        if (costCmp != 0) {
            return costCmp;
        }
        return Double.compare(this.referenceDistanceM, other.referenceDistanceM);
    }
}
