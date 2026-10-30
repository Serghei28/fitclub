package com.fitclub.membership.domain;

/** AWAITING_PAYMENT → PAID (подтверждённая оплата) или PAYMENT_FAILED (отказ). Оба конечные. */
public enum OrderStatus {
    AWAITING_PAYMENT,
    PAID,
    PAYMENT_FAILED
}
