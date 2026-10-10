package org.example.subscriptions.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import su.onno.validation.ValidationException;

/** The precision of the onno resources and document attributes, checked before SQL. */
public final class Money {
    private Money() {
    }

    public static boolean valid(BigDecimal value) {
        if (value == null || value.signum() <= 0) return false;
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY).precision() <= 15;
        } catch (ArithmeticException invalid) {
            return false;
        }
    }

    public static BigDecimal exact(BigDecimal value) {
        if (!valid(value)) throw new ValidationException("Сумма должна быть положительной, до 13 целых и 2 дробных цифр");
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static int positiveInteger(Object value) {
        try {
            int integer = new BigDecimal(String.valueOf(value)).intValueExact();
            if (integer > 0) return integer;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new ValidationException("Введите положительное целое число не больше 2147483647");
        }
        throw new ValidationException("Введите положительное целое число не больше 2147483647");
    }
}
