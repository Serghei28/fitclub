package com.fitclub.payment.domain;

import java.util.Objects;

/** Сумма платежа в минимальных единицах валюты. Берётся из снимка заказа Membership, а не от клиента. */
public record Amount(long minor, String currency) {

    public Amount {
        if (minor < 0) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
        Objects.requireNonNull(currency);
    }
}
