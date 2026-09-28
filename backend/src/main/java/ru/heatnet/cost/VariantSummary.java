package ru.heatnet.cost;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Сводка варианта — атрибуты variant_summary (§7.2 нового приложения). Деньги — long, рубли.
 * {@code construction_cost} = участки + новые камеры + врезки в существующие камеры.
 * Реконструкция хранится как диагностика и не входит в C и L.
 */
public final class VariantSummary {

    private final String variantId;
    private final int rank;
    private final long segmentCost;
    private final long constructionCost;
    private final long chamberConstructionCost;
    private final long tieInCost;
    private final int existingChamberTieInCount;
    private final long reconstructionCost;
    private final long chamberReconstructionCost;
    private final long unconnectedPenalty;
    private final long calculatedCost;
    private final double newNetworkLength;
    private final double reconstructionLength;
    private final double length;
    private final double score;
    private final double rawScore;
    private final List<String> unconnectedOksIds;

    private VariantSummary(Builder b, int rank, double score, double rawScore) {
        this(b.variantId, rank, b.segmentCost, b.constructionCost(), b.chamberConstructionCost, b.tieInCost,
                b.existingChamberTieInCount, b.reconstructionCost, b.chamberReconstructionCost,
                b.unconnectedPenalty, b.calculatedCost(), b.newNetworkLength.doubleValue(),
                b.reconstructionLength.doubleValue(), b.newNetworkLength.doubleValue(),
                score, rawScore, new ArrayList<>(b.unconnectedOksIds));
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private VariantSummary(String variantId, int rank, long segmentCost, long constructionCost,
                           long chamberConstructionCost, long tieInCost, int existingChamberTieInCount,
                           long reconstructionCost, long chamberReconstructionCost,
                           long unconnectedPenalty, long calculatedCost, double newNetworkLength,
                           double reconstructionLength, double length, double score, double rawScore,
                           List<String> unconnectedOksIds) {
        this.variantId = variantId;
        this.rank = rank;
        this.segmentCost = segmentCost;
        this.constructionCost = constructionCost;
        this.chamberConstructionCost = chamberConstructionCost;
        this.tieInCost = tieInCost;
        this.existingChamberTieInCount = existingChamberTieInCount;
        this.reconstructionCost = reconstructionCost;
        this.chamberReconstructionCost = chamberReconstructionCost;
        this.unconnectedPenalty = unconnectedPenalty;
        this.calculatedCost = calculatedCost;
        this.newNetworkLength = newNetworkLength;
        this.reconstructionLength = reconstructionLength;
        this.length = length;
        this.score = score;
        this.rawScore = rawScore;
        this.unconnectedOksIds = Collections.unmodifiableList(unconnectedOksIds);
    }

    public static Builder builder(String variantId) {
        return new Builder(variantId);
    }

    /** Копия с другим местом (rank). */
    public VariantSummary withRank(int newRank) {
        return new VariantSummary(variantId, newRank, segmentCost, constructionCost, chamberConstructionCost,
                tieInCost, existingChamberTieInCount, reconstructionCost, chamberReconstructionCost,
                unconnectedPenalty, calculatedCost, newNetworkLength, reconstructionLength, length,
                score, rawScore, new ArrayList<>(unconnectedOksIds));
    }

    /** id сводной записи для выхода. */
    public String getId() {
        return "summary_" + variantId;
    }

    public String getVariantId() {
        return variantId;
    }

    /** Место; 0 — ещё не ранжирован. */
    public int getRank() {
        return rank;
    }

    /** Только линейные новые участки (диагностика / сходимость с объектами). */
    public long getSegmentCost() {
        return segmentCost;
    }

    /** §7.2: участки + новые камеры + врезки в существующие камеры. */
    public long getConstructionCost() {
        return constructionCost;
    }

    public long getChamberConstructionCost() {
        return chamberConstructionCost;
    }

    public long getTieInCost() {
        return tieInCost;
    }

    public long getExistingChamberTieInCost() {
        return tieInCost;
    }

    public int getExistingChamberTieInCount() {
        return existingChamberTieInCount;
    }

    public long getReconstructionCost() {
        return reconstructionCost;
    }

    public long getChamberReconstructionCost() {
        return chamberReconstructionCost;
    }

    public long getUnconnectedPenalty() {
        return unconnectedPenalty;
    }

    public long getCalculatedCost() {
        return calculatedCost;
    }

    public double getNewNetworkLength() {
        return newNetworkLength;
    }

    public double getReconstructionLength() {
        return reconstructionLength;
    }

    /** §6: L для S = только new_network_length. */
    public double getLength() {
        return length;
    }

    /** S, округлённый до score_scale знаков. */
    public double getScore() {
        return score;
    }

    /** S без округления — для сортировки вариантов. */
    public double getRawScore() {
        return rawScore;
    }

    public List<String> getUnconnectedOksIds() {
        return unconnectedOksIds;
    }

    /** Накопитель составляющих стоимости и длины. */
    public static final class Builder {
        private final String variantId;
        private long segmentCost;
        private long chamberConstructionCost;
        private long tieInCost;
        private int existingChamberTieInCount;
        private long reconstructionCost;
        private long chamberReconstructionCost;
        private long unconnectedPenalty;
        private BigDecimal newNetworkLength = BigDecimal.ZERO;
        private BigDecimal reconstructionLength = BigDecimal.ZERO;
        private final List<String> unconnectedOksIds = new ArrayList<>();

        private Builder(String variantId) {
            this.variantId = variantId;
        }

        public Builder addNewSegment(double lengthM, long costRub) {
            newNetworkLength = newNetworkLength.add(BigDecimal.valueOf(lengthM));
            segmentCost = Money.add(segmentCost, costRub);
            return this;
        }

        public Builder addNewChamber(long costRub) {
            chamberConstructionCost = Money.add(chamberConstructionCost, costRub);
            return this;
        }

        public Builder addTieIn(long costRub) {
            tieInCost = Money.add(tieInCost, costRub);
            existingChamberTieInCount++;
            return this;
        }

        public Builder addReconstruction(double lengthM, long costRub) {
            reconstructionLength = reconstructionLength.add(BigDecimal.valueOf(lengthM));
            reconstructionCost = Money.add(reconstructionCost, costRub);
            return this;
        }

        public Builder addChamberReconstruction(long costRub) {
            chamberReconstructionCost = Money.add(chamberReconstructionCost, costRub);
            return this;
        }

        public Builder addUnconnected(String oksId, long penaltyRub) {
            unconnectedOksIds.add(oksId);
            unconnectedPenalty = Money.add(unconnectedPenalty, penaltyRub);
            return this;
        }

        long constructionCost() {
            return Money.add(Money.add(segmentCost, chamberConstructionCost), tieInCost);
        }

        long calculatedCost() {
            return Money.add(constructionCost(), unconnectedPenalty);
        }

        public VariantSummary build(ScoreCalculator scoreCalculator) {
            double totalLength = newNetworkLength.doubleValue();
            double raw = scoreCalculator.raw(calculatedCost(), totalLength);
            return new VariantSummary(this, 0, scoreCalculator.round(raw), raw);
        }
    }
}
