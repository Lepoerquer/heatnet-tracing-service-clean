package ru.heatnet.calc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import ru.heatnet.calc.model.CalcDiagnostic;

/** Итог распространения расходов по существующей сети. */
public final class ReconstructionResult {

    private final Map<String, List<FlowPiece>> piecesBySegment;
    private final List<CalcDiagnostic> diagnostics;

    public ReconstructionResult(Map<String, List<FlowPiece>> piecesBySegment, List<CalcDiagnostic> diagnostics) {
        this.piecesBySegment = Collections.unmodifiableMap(piecesBySegment);
        this.diagnostics = Collections.unmodifiableList(diagnostics);
    }

    /** Все затронутые части (с реконструкцией и без), по участкам. */
    public Map<String, List<FlowPiece>> getPiecesBySegment() {
        return piecesBySegment;
    }

    /** Части, подлежащие реконструкции — выходные heat_network_reconstruction. */
    public List<FlowPiece> getReconstructionParts() {
        List<FlowPiece> result = new ArrayList<>();
        for (List<FlowPiece> pieces : piecesBySegment.values()) {
            for (FlowPiece p : pieces) {
                if (p.isReconstructionRequired()) {
                    result.add(p);
                }
            }
        }
        return result;
    }

    /**
     * Диаметр существующего участка после реконструкции в точке {@code positionM}
     * (от конца к источнику); на границе частей берётся больший.
     */
    public int diameterAfterAt(String segmentId, int existingDiameter, double positionM) {
        int d = existingDiameter;
        List<FlowPiece> pieces = piecesBySegment.get(segmentId);
        if (pieces != null) {
            for (FlowPiece p : pieces) {
                if (positionM >= p.getFromM() - 1e-9 && positionM <= p.getToM() + 1e-9) {
                    d = Math.max(d, p.getRequiredDiameter());
                }
            }
        }
        return d;
    }

    public List<CalcDiagnostic> getDiagnostics() {
        return diagnostics;
    }
}
