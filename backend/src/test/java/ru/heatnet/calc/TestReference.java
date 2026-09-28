package ru.heatnet.calc;

import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.calc.reference.ReferenceDataLoader;

/** Общая загрузка боевых config/*.yaml для тестов (тесты проверяют реальные справочники). */
public final class TestReference {

    private static ReferenceData cached;

    private TestReference() {
    }

    public static synchronized ReferenceData get() {
        if (cached == null) {
            cached = ReferenceDataLoader.loadDefault();
        }
        return cached;
    }
}
