package ru.heatnet.calc;

import java.util.Collections;
import java.util.List;

import ru.heatnet.calc.model.CalcDiagnostic;
import ru.heatnet.calc.model.CalcDiagnostic.Severity;

/** Полный инженерный расчёт варианта (вход для cost/ и export/). */
public final class EngineeringResult {

    private final List<TreeCalcResult> trees;
    private final List<TieInSizing> tieIns;
    private final ReconstructionResult reconstruction;
    private final List<ChamberSizing> newChambers;
    private final List<ChamberSizing> existingChambers;
    private final List<CalcDiagnostic> diagnostics;

    public EngineeringResult(List<TreeCalcResult> trees, List<TieInSizing> tieIns, ReconstructionResult reconstruction,
                             List<ChamberSizing> newChambers, List<ChamberSizing> existingChambers,
                             List<CalcDiagnostic> diagnostics) {
        this.trees = Collections.unmodifiableList(trees);
        this.tieIns = Collections.unmodifiableList(tieIns);
        this.reconstruction = reconstruction;
        this.newChambers = Collections.unmodifiableList(newChambers);
        this.existingChambers = Collections.unmodifiableList(existingChambers);
        this.diagnostics = Collections.unmodifiableList(diagnostics);
    }

    public List<TreeCalcResult> getTrees() {
        return trees;
    }

    public List<TieInSizing> getTieIns() {
        return tieIns;
    }

    public ReconstructionResult getReconstruction() {
        return reconstruction;
    }

    /** Новые камеры (всегда строятся). */
    public List<ChamberSizing> getNewChambers() {
        return newChambers;
    }

    /** Существующие камеры врезки (реконструкция — если {@link ChamberSizing#isReconstructionRequired()}). */
    public List<ChamberSizing> getExistingChambers() {
        return existingChambers;
    }

    public List<CalcDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    public boolean hasErrors() {
        for (CalcDiagnostic d : diagnostics) {
            if (d.getSeverity() == Severity.ERROR) {
                return true;
            }
        }
        return false;
    }
}
