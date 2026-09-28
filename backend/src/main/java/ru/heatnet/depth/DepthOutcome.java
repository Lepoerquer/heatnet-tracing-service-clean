package ru.heatnet.depth;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import ru.heatnet.cost.VariantSummary;

/**
 * Результат M11 для одного варианта.
 * Пустые замены означают, что горизонтальная трасса остаётся на обычной глубине 3,0 м
 * и смета M6 не меняется.
 */
public final class DepthOutcome {

    private final Map<String, List<DepthPiece>> piecesBySegment;
    private final List<DepthExtraNode> extraNodes;
    private final VariantSummary summary;

    public DepthOutcome(Map<String, List<DepthPiece>> piecesBySegment, List<DepthExtraNode> extraNodes,
                        VariantSummary summary) {
        this.piecesBySegment = piecesBySegment;
        this.extraNodes = extraNodes;
        this.summary = summary;
    }

    public static DepthOutcome unchanged() {
        return new DepthOutcome(Collections.<String, List<DepthPiece>>emptyMap(),
                Collections.<DepthExtraNode>emptyList(), null);
    }

    public Map<String, List<DepthPiece>> getPiecesBySegment() {
        return piecesBySegment;
    }

    public List<DepthPiece> piecesOf(String segmentId) {
        List<DepthPiece> pieces = piecesBySegment.get(segmentId);
        return pieces == null ? Collections.<DepthPiece>emptyList() : pieces;
    }

    public List<DepthExtraNode> getExtraNodes() {
        return extraNodes;
    }

    /** Новая сводка, если Kгл изменил стоимость; иначе null — оставить сводку M6. */
    public VariantSummary getSummary() {
        return summary;
    }

    public boolean hasReplacements() {
        return !piecesBySegment.isEmpty();
    }
}
