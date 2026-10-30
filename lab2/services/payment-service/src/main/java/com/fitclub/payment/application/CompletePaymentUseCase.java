package com.fitclub.payment.application;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fitclub.common.idempotency.IdempotencyStore;
import com.fitclub.common.idempotency.IdempotencyStore.Fresh;
import com.fitclub.common.idempotency.IdempotencyStore.Outcome;
import com.fitclub.payment.application.port.PaymentPorts.PaymentEventPublisher;
import com.fitclub.payment.application.port.PaymentPorts.PaymentRepository;
import com.fitclub.payment.application.port.PaymentPorts.TransactionRunner;
import com.fitclub.payment.application.port.PaymentProvider;
import com.fitclub.payment.domain.Payment;
import com.fitclub.payment.domain.PaymentErrors;

/**
 * Завершение платежа по результату провайдера. Новый статус и событие PaymentSucceeded/PaymentDeclined
 * сохраняются одной транзакцией (outbox). Повтор с тем же Idempotency-Key возвращает исходный результат
 * без нового события; противоположный исход для завершённого платежа — 409.
 */
@Service
public class CompletePaymentUseCase {

    public static final String OPERATION = "SIMULATE_PAYMENT";

    private static final Logger log = LoggerFactory.getLogger(CompletePaymentUseCase.class);

    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final PaymentEventPublisher events;
    private final IdempotencyStore idempotency;
    private final TransactionRunner tx;
    private final Clock clock;

    public CompletePaymentUseCase(PaymentRepository payments, PaymentProvider provider, PaymentEventPublisher events,
            IdempotencyStore idempotency, TransactionRunner tx, Clock clock) {
        this.payments = payments;
        this.provider = provider;
        this.events = events;
        this.idempotency = idempotency;
        this.tx = tx;
        this.clock = clock;
    }

    public record Request(UUID paymentId, PaymentProvider.Instruction outcome) {
    }

    public Outcome<PaymentView> complete(UUID userId, UUID idempotencyKey, Request request) {
        return tx.inTransaction(() -> idempotency.execute(userId, OPERATION, idempotencyKey, request,
                PaymentView.class, () -> completeOnce(userId, request)));
    }

    Fresh<PaymentView> completeOnce(UUID userId, Request request) {
        Payment payment = payments.findById(request.paymentId())
                .filter(p -> p.isOwnedBy(userId))
                .orElseThrow(PaymentErrors::paymentNotFound);
        PaymentProvider.Result result = provider.finish(payment.providerReference(), request.outcome());
        Payment.CompletionOutcome outcome = result.approved()
                ? payment.succeed(clock.instant())
                : payment.decline(result.declineReason(), clock.instant());
        if (outcome == Payment.CompletionOutcome.COMPLETED) {
            payments.update(payment);
            if (result.approved()) {
                events.paymentSucceeded(payment);
            } else {
                events.paymentDeclined(payment);
            }
            log.info("Payment completed: payment_id={}, status={}", payment.id(), payment.status());
        }
        return new Fresh<>(PaymentView.from(payment), HttpStatus.OK, payment.id());
    }
}
