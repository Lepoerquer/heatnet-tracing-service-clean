package ru.heatnet.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RestrictionTypeMapperTest {

    @Test
    @DisplayName("oks в датасете → oks_existing в rules.yaml")
    void mapsOksToOksExisting() {
        assertEquals("oks_existing", RestrictionTypeMapper.toRulesKey("oks"));
    }

    @Test
    @DisplayName("прочие типы без изменений")
    void passthrough() {
        assertEquals("park", RestrictionTypeMapper.toRulesKey("park"));
        assertEquals("railway", RestrictionTypeMapper.toRulesKey("railway"));
    }

    @Test
    @DisplayName("пустой тип — ошибка")
    void emptyType() {
        assertThrows(RulesException.class, () -> RestrictionTypeMapper.toRulesKey(null));
    }
}
