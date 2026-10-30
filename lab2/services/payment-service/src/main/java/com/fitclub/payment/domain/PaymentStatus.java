package com.fitclub.payment.domain;

/** PENDING → SUCCEEDED или PENDING → DECLINED. Конечные статусы не меняются друг на друга. */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    DECLINED;

    public boolean terminal() {
        return this != PENDING;
    }
}
