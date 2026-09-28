package ru.heatnet.calc;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import ru.heatnet.calc.model.CalcDiagnostic;

/** Итог проверки предельной длины: окончательные DN, плети, диагностика. */
public final class LengthLimitResult {

    private final Map<String, Integer> diameters;
    private final Map<String, Integer> hydraulicDiameters;
    private final List<DnRun> runs;
    private final List<DnRun> exceededRuns;
    private final List<CalcDiagnostic> diagnostics;

    public LengthLimitResult(Map<String, Integer> diameters, Map<String, Integer> hydraulicDiameters,
                             List<DnRun> runs, List<DnRun> exceededRuns, List<CalcDiagnostic> diagnostics) {
        this.diameters = Collections.unmodifiableMap(diameters);
        this.hydraulicDiameters = Collections.unmodifiableMap(hydraulicDiameters);
        this.runs = Collections.unmodifiableList(runs);
        this.exceededRuns = Collections.unmodifiableList(exceededRuns);
        this.diagnostics = Collections.unmodifiableList(diagnostics);
    }

    /** Окончательный DN каждого участка. */
    public Map<String, Integer> getDiameters() {
        return diameters;
    }

    /** DN по пропускной способности до поправки на предельную длину. */
    public Map<String, Integer> getHydraulicDiameters() {
        return hydraulicDiameters;
    }

    public List<DnRun> getRuns() {
        return runs;
    }

    /** Плети, превышающие предельную длину даже после допустимого подъёма DN. */
    public List<DnRun> getExceededRuns() {
        return exceededRuns;
    }

    public boolean isWithinLimits() {
        return exceededRuns.isEmpty();
    }

    public List<CalcDiagnostic> getDiagnostics() {
        return diagnostics;
    }
}
