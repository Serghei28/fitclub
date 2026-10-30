package com.fitclub.payment.domain;

import com.fitclub.common.error.DomainException;
import com.fitclub.common.error.DomainException.Kind;

/** Бизнес-ошибки payment-service со стабильными машинными кодами. */
public final class PaymentErrors {

    public static final String ORDER_NOT_PAYABLE = "ORDER_NOT_PAYABLE";
    public static final String PAYMENT_ALREADY_COMPLETED = "PAYMENT_ALREADY_COMPLETED";

    private PaymentErrors() {
    }

    public static DomainException orderNotFound() {
        return new DomainException(Kind.NOT_FOUND, "RESOURCE_NOT_FOUND", "Ресурс не найден", "Заказ не найден.");
    }

    public static DomainException paymentNotFound() {
        return new DomainException(Kind.NOT_FOUND, "RESOURCE_NOT_FOUND", "Ресурс не найден", "Платёж не найден.");
    }

    public static DomainException orderNotPayable() {
        return new DomainException(Kind.CONFLICT, ORDER_NOT_PAYABLE, "Заказ нельзя оплатить",
                "Заказ уже оплачен или оплата по нему отклонена. Создайте новый заказ.");
    }

    public static DomainException alreadyCompleted(PaymentStatus status) {
        return new DomainException(Kind.CONFLICT, PAYMENT_ALREADY_COMPLETED, "Платёж уже завершён",
                "Платёж уже в конечном статусе " + status + " и не может быть изменён.");
    }
}
