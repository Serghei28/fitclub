package com.fitclub.membership.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.membership.application.port.MembershipPorts.MembershipEventPublisher;
import com.fitclub.membership.application.port.MembershipPorts.MembershipRepository;
import com.fitclub.membership.application.port.MembershipPorts.OrderRepository;
import com.fitclub.membership.domain.Membership;
import com.fitclub.membership.domain.MembershipOrder;
import com.fitclub.membership.domain.OrderStatus;

/**
 * Реакция на результат оплаты из payment-service. Вызывается внутри транзакции обработчика событий
 * (вместе с записью processed_events), поэтому изменения и отметка «обработано» фиксируются вместе.
 *
 * <p>PaymentSucceeded: проверяются владелец, payment_id, сумма и валюта по снимку заказа; затем заказ → PAID,
 * ровно один абонемент (уникальность по заказу и платежу) и MembershipActivated в outbox.
 * Повтор события (даже с новым event_id) не продлевает срок и не создаёт второй абонемент.
 */
@Service
public class PaymentResultHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultHandler.class);

    private final OrderRepository orders;
    private final MembershipRepository memberships;
    private final MembershipEventPublisher events;
    private final Clock clock;

    public PaymentResultHandler(OrderRepository orders, MembershipRepository memberships,
            MembershipEventPublisher events, Clock clock) {
        this.orders = orders;
        this.memberships = memberships;
        this.events = events;
        this.clock = clock;
    }

    /** Результат оплаты из события (payload PaymentSucceeded / PaymentDeclined). */
    public record PaymentResult(UUID paymentId, UUID orderId, UUID userId, long amountMinor, String currency,
            Instant completedAt) {
    }

    public enum Outcome {
        ACTIVATED,
        PAYMENT_FAILURE_RECORDED,
        /** Уже применено ранее: ничего не меняется. */
        ALREADY_APPLIED,
        /** Событие противоречит данным Membership: не применяется, требует ручного разбора (DLQ). */
        REJECTED
    }

    @Transactional
    public Outcome onPaymentSucceeded(PaymentResult payment) {
        orders.lockUser(payment.userId());
        Optional<MembershipOrder> found = orders.findById(payment.orderId());
        if (found.isEmpty()) {
            return reject("order not found", payment);
        }
        MembershipOrder order = found.get();
        if (!order.isOwnedBy(payment.userId())) {
            return reject("order owner does not match payment user", payment);
        }
        if (!order.matchesAmount(payment.amountMinor(), payment.currency())) {
            return reject("amount or currency does not match order snapshot", payment);
        }
        if (order.status() == OrderStatus.PAID) {
            if (payment.paymentId().equals(order.paymentId())) {
                log.info("Payment already applied: order_id={}", order.id());
                return Outcome.ALREADY_APPLIED;
            }
            return reject("order already paid by another payment", payment);
        }
        if (order.status() == OrderStatus.PAYMENT_FAILED) {
            return reject("order is already marked as payment failed", payment);
        }

        Instant now = clock.instant();
        order.markPaid(payment.paymentId(), payment.completedAt(), now);
        orders.update(order);
        Membership membership = Membership.activate(UUID.randomUUID(), order, now);
        if (!memberships.addIfAbsent(membership)) {
            log.info("Membership for order already exists: order_id={}", order.id());
            return Outcome.ALREADY_APPLIED;
        }
        events.membershipActivated(membership);
        log.info("Membership activated: membership_id={}, order_id={}, starts_at={}, ends_at={}", membership.id(),
                order.id(), membership.startsAt(), membership.endsAt());
        return Outcome.ACTIVATED;
    }

    @Transactional
    public Outcome onPaymentDeclined(PaymentResult payment) {
        orders.lockUser(payment.userId());
        Optional<MembershipOrder> found = orders.findById(payment.orderId());
        if (found.isEmpty() || !found.get().isOwnedBy(payment.userId())) {
            return reject("order not found or owner mismatch", payment);
        }
        MembershipOrder order = found.get();
        if (order.status() != OrderStatus.AWAITING_PAYMENT) {
            log.info("Decline ignored: order_id={} is already {}", order.id(), order.status());
            return Outcome.ALREADY_APPLIED;
        }
        order.markPaymentFailed(payment.paymentId(), payment.completedAt(), clock.instant());
        orders.update(order);
        log.info("Order payment failed: order_id={}", order.id());
        return Outcome.PAYMENT_FAILURE_RECORDED;
    }

    private static Outcome reject(String reason, PaymentResult payment) {
        log.error("Payment result rejected: {} (order_id={}, payment_id={})", reason, payment.orderId(),
                payment.paymentId());
        return Outcome.REJECTED;
    }
}
