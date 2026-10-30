package com.fitclub.payment.application;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fitclub.common.idempotency.IdempotencyStore;
import com.fitclub.common.idempotency.IdempotencyStore.Fresh;
import com.fitclub.common.idempotency.IdempotencyStore.Outcome;
import com.fitclub.payment.application.port.PaymentPorts.MembershipOrderReader;
import com.fitclub.payment.application.port.PaymentPorts.MembershipOrderReader.OrderDetails;
import com.fitclub.payment.application.port.PaymentPorts.PaymentRepository;
import com.fitclub.payment.application.port.PaymentPorts.TransactionRunner;
import com.fitclub.payment.application.port.PaymentProvider;
import com.fitclub.payment.domain.Amount;
import com.fitclub.payment.domain.Payment;
import com.fitclub.payment.domain.PaymentErrors;

/**
 * Создание платежа по заказу. Клиент передаёт только membership_order_id:
 * владелец, сумма и валюта берутся из внутреннего API Membership (вне транзакции, с таймаутами),
 * владелец сверяется с пользователем из JWT. Один заказ — один платёж: повтор с другим ключом
 * вернёт уже существующий платёж.
 */
@Service
public class CreatePaymentUseCase {

    public static final String OPERATION = "CREATE_PAYMENT";

    private static final Logger log = LoggerFactory.getLogger(CreatePaymentUseCase.class);

    private final MembershipOrderReader orders;
    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final IdempotencyStore idempotency;
    private final TransactionRunner tx;
    private final Clock clock;

    public CreatePaymentUseCase(MembershipOrderReader orders, PaymentRepository payments, PaymentProvider provider,
            IdempotencyStore idempotency, TransactionRunner tx, Clock clock) {
        this.orders = orders;
        this.payments = payments;
        this.provider = provider;
        this.idempotency = idempotency;
        this.tx = tx;
        this.clock = clock;
    }

    public record Request(UUID membershipOrderId) {
    }

    public Outcome<PaymentView> create(UUID userId, UUID idempotencyKey, Request request) {
        // 1. Сетевой вызов вне транзакции. Чужой заказ неотличим от несуществующего (404)
        OrderDetails details = orders.paymentDetails(request.membershipOrderId())
                .filter(order -> order.userId().equals(userId))
                .orElseThrow(PaymentErrors::orderNotFound);
        // 2. Короткая транзакция: ключ идемпотентности и платёж
        return tx.inTransaction(() -> idempotency.execute(userId, OPERATION, idempotencyKey, request,
                PaymentView.class, () -> createOnce(userId, details)));
    }

    Fresh<PaymentView> createOnce(UUID userId, OrderDetails details) {
        Optional<Payment> existing = payments.findByOrderId(details.orderId());
        if (existing.isPresent()) {
            log.info("Existing payment returned for order: payment_id={}", existing.get().id());
            return new Fresh<>(PaymentView.from(existing.get()), HttpStatus.OK, existing.get().id());
        }
        if (!details.awaitingPayment()) {
            throw PaymentErrors.orderNotPayable();
        }
        UUID paymentId = UUID.randomUUID();
        Amount amount = new Amount(details.amountMinor(), details.currency());
        PaymentProvider.Registration registration = provider.register(paymentId, amount);
        Payment payment = Payment.start(paymentId, details.orderId(), userId, amount, provider.name(),
                registration.providerReference(), clock.instant());
        if (!payments.addIfOrderFree(payment)) {
            Payment winner = payments.findByOrderId(details.orderId()).orElseThrow();
            return new Fresh<>(PaymentView.from(winner), HttpStatus.OK, winner.id());
        }
        log.info("Payment created: payment_id={}, order_id={}, amount_minor={} {}", paymentId, details.orderId(),
                amount.minor(), amount.currency());
        return new Fresh<>(PaymentView.from(payment), HttpStatus.CREATED, paymentId);
    }
}
