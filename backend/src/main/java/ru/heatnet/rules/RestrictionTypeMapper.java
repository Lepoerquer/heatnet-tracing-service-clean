package ru.heatnet.rules;

/**
 * Сопоставление {@code restriction_type} из GeoJSON с ключами {@code config/rules.yaml}.
 * В конкурсном датасете существующие ОКС переданы как {@code restriction_type=oks},
 * в YAML — {@code oks_existing} (см. 03-dataset-audit.md §3.4).
 */
public final class RestrictionTypeMapper {

    private RestrictionTypeMapper() {
    }

    public static String toRulesKey(String restrictionType) {
        if (restrictionType == null || restrictionType.isEmpty()) {
            throw new RulesException("restriction_type пуст");
        }
        if ("oks".equals(restrictionType)) {
            return "oks_existing";
        }
        return restrictionType;
    }
}
