package ru.heatnet.cost;

import ru.heatnet.calc.reference.RulesConfig;

/**
 * M6. K_угол (протокол 16.09 п. 9): отвод на нестандартный угол (не 0/45/90/135/180°
 * с допуском angle_tolerance_deg) удорожает участок в k_angle = 1,5 раза.
 * Механика применения не формализована организаторами (clarifications №12):
 * дефолт команды — множитель к стоимости сегмента, заканчивающегося поворотом.
 */
public final class AngleCoefficient {

    private final RulesConfig rules;

    public AngleCoefficient(RulesConfig rules) {
        this.rules = rules;
    }

    /**
     * @param turnAngleDeg угол поворота трассы (между направлениями соседних сегментов), градусы
     */
    public boolean isStandard(double turnAngleDeg) {
        double a = normalize(turnAngleDeg);
        for (double std : rules.getStandardAnglesDeg()) {
            if (Math.abs(a - std) <= rules.getAngleToleranceDeg() + 1e-9) {
                return true;
            }
        }
        return false;
    }

    public double forTurn(double turnAngleDeg) {
        return isStandard(turnAngleDeg) ? 1.0 : rules.getKAngle();
    }

    /** Приведение к [0; 180]. */
    static double normalize(double deg) {
        double a = Math.abs(deg) % 360.0;
        return a > 180.0 ? 360.0 - a : a;
    }
}
