package com.fitclub.membership.domain;

import java.util.regex.Pattern;

/**
 * Деньги: целое число минимальных единиц (бани для MDL) и код валюты ISO 4217.
 * float/double не используются, чтобы не было ошибок округления.
 */
public record Money(long amountMinor, String currency) {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public Money {
        if (amountMinor < 0) {
            throw MembershipErrors.invalidPlan("price_minor", "Цена не может быть отрицательной.");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw MembershipErrors.invalidPlan("currency", "Валюта — трёхбуквенный код ISO 4217, например MDL.");
        }
    }
}
