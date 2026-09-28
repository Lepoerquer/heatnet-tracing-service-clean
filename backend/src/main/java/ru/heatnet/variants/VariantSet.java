package ru.heatnet.variants;

import java.util.Collections;
import java.util.List;

/** M7. Итог генерации: ранжированные варианты (не более {@code ranking.max_variants}) и диагностика. */
public final class VariantSet {

    private final List<GeneratedVariant> variants;
    private final List<String> diagnostics;

    public VariantSet(List<GeneratedVariant> variants, List<String> diagnostics) {
        this.variants = Collections.unmodifiableList(variants);
        this.diagnostics = Collections.unmodifiableList(diagnostics);
    }

    /** Варианты в порядке ранжирования: индекс 0 — {@code rank = 1} (наименьший S). */
    public List<GeneratedVariant> getVariants() {
        return variants;
    }

    /** Почему часть кандидатов отброшена: не построились, дубли по содержанию, срез по max_variants. */
    public List<String> getDiagnostics() {
        return diagnostics;
    }

    public boolean isEmpty() {
        return variants.isEmpty();
    }

    /** Лучший вариант или null, если не построен ни один. */
    public GeneratedVariant best() {
        return variants.isEmpty() ? null : variants.get(0);
    }
}
