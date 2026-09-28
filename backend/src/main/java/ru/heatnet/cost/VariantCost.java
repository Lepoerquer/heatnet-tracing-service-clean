package ru.heatnet.cost;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import ru.heatnet.calc.EngineeringResult;

/** Стоимость каждого объекта варианта и сводка — всё, что нужно export/ для раздела 10. */
public final class VariantCost {

    private final String variantId;
    private final EngineeringResult engineering;
    private final Map<String, Long> segmentCosts;
    private final Map<String, Long> newChamberCosts;
    private final Map<String, Long> tieInCosts;
    private final List<CostedPiece> reconstructionCosts;
    private final Map<String, Long> chamberReconstructionCosts;
    private final Map<String, Long> penalties;
    private final VariantSummary summary;

    public VariantCost(String variantId, EngineeringResult engineering, Map<String, Long> segmentCosts,
                       Map<String, Long> newChamberCosts, Map<String, Long> tieInCosts,
                       List<CostedPiece> reconstructionCosts, Map<String, Long> chamberReconstructionCosts,
                       Map<String, Long> penalties, VariantSummary summary) {
        this.variantId = variantId;
        this.engineering = engineering;
        this.segmentCosts = Collections.unmodifiableMap(segmentCosts);
        this.newChamberCosts = Collections.unmodifiableMap(newChamberCosts);
        this.tieInCosts = Collections.unmodifiableMap(tieInCosts);
        this.reconstructionCosts = Collections.unmodifiableList(reconstructionCosts);
        this.chamberReconstructionCosts = Collections.unmodifiableMap(chamberReconstructionCosts);
        this.penalties = Collections.unmodifiableMap(penalties);
        this.summary = summary;
    }

    public VariantCost withSummary(VariantSummary newSummary) {
        return new VariantCost(variantId, engineering, segmentCosts, newChamberCosts, tieInCosts,
                reconstructionCosts, chamberReconstructionCosts, penalties, newSummary);
    }

    public String getVariantId() {
        return variantId;
    }

    public EngineeringResult getEngineering() {
        return engineering;
    }

    /** heat_network.cost по id участка. */
    public Map<String, Long> getSegmentCosts() {
        return segmentCosts;
    }

    /** heat_chamber.cost по id новой камеры. */
    public Map<String, Long> getNewChamberCosts() {
        return newChamberCosts;
    }

    /** tie_in.cost по id врезки. */
    public Map<String, Long> getTieInCosts() {
        return tieInCosts;
    }

    public List<CostedPiece> getReconstructionCosts() {
        return reconstructionCosts;
    }

    /** heat_chamber_reconstruction.cost по id существующей камеры. */
    public Map<String, Long> getChamberReconstructionCosts() {
        return chamberReconstructionCosts;
    }

    /** Штраф по id ОКС. */
    public Map<String, Long> getPenalties() {
        return penalties;
    }

    public VariantSummary getSummary() {
        return summary;
    }
}
