package ru.heatnet.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Деньги — только long (целые рубли). Произведения длины на ставку и коэффициенты
 * считаются в BigDecimal и округляются до рубля один раз, HALF_UP.
 */
public final class Money {

    private Money() {
    }

    /** round(base · f1 · f2 · ...) до рубля HALF_UP. Множители double переводятся через BigDecimal.valueOf. */
    public static long product(double base, long rubPerUnit, double... factors) {
        BigDecimal v = BigDecimal.valueOf(base).multiply(BigDecimal.valueOf(rubPerUnit));
        for (double f : factors) {
            v = v.multiply(BigDecimal.valueOf(f));
        }
        return toRub(v);
    }

    public static long toRub(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Сумма с контролем переполнения. */
    public static long add(long a, long b) {
        return Math.addExact(a, b);
    }
}
