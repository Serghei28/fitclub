package com.fitclub.payment.application;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fitclub.common.security.AuthenticatedUser;
import com.fitclub.payment.application.port.PaymentPorts.PaymentRepository;
import com.fitclub.payment.application.port.PaymentPorts.TransactionRunner;
import com.fitclub.payment.domain.PaymentErrors;

/** Чтение платежа: владелец или ADMIN. Чужой платёж для клиента не существует (404). */
@Service
public class PaymentQueryService {

    private final PaymentRepository payments;
    private final TransactionRunner tx;

    public PaymentQueryService(PaymentRepository payments, TransactionRunner tx) {
        this.payments = payments;
        this.tx = tx;
    }

    public PaymentView payment(UUID paymentId, AuthenticatedUser viewer) {
        return tx.readOnly(() -> payments.findById(paymentId)
                .filter(p -> p.isOwnedBy(viewer.id()) || viewer.isAdmin())
                .map(PaymentView::from)
                .orElseThrow(PaymentErrors::paymentNotFound));
    }
}
