package com.fitclub.membership.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.common.idempotency.IdempotencyStore;
import com.fitclub.common.idempotency.IdempotencyStore.Fresh;
import com.fitclub.common.idempotency.IdempotencyStore.Outcome;
import com.fitclub.membership.application.port.MembershipPorts.MembershipRepository;
import com.fitclub.membership.application.port.MembershipPorts.OrderRepository;
import com.fitclub.membership.application.port.MembershipPorts.PlanRepository;
import com.fitclub.membership.domain.MembershipErrors;
import com.fitclub.membership.domain.MembershipOrder;
import com.fitclub.membership.domain.Plan;

/**
 * Заказ абонемента. Клиент передаёт только plan_id; цена, срок и лимит — снимок тарифа из БД Membership.
 * Правила: один ожидающий оплаты заказ и один не закончившийся абонемент на пользователя.
 * Если ожидающий заказ того же тарифа уже есть, он возвращается (обновление страницы не создаёт дубликат).
 * Защита от гонок: Idempotency-Key, блокировка пользователя и уникальный частичный индекс в БД.
 */
@Service
public class PlaceOrderUseCase {

    public static final String OPERATION = "CREATE_MEMBERSHIP_ORDER";

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderUseCase.class);

    private final PlanRepository plans;
    private final OrderRepository orders;
    private final MembershipRepository memberships;
    private final IdempotencyStore idempotency;
    private final Clock clock;

    public PlaceOrderUseCase(PlanRepository plans, OrderRepository orders, MembershipRepository memberships,
            IdempotencyStore idempotency, Clock clock) {
        this.plans = plans;
        this.orders = orders;
        this.memberships = memberships;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /** Нормализованное тело запроса для хеша Idempotency-Key. */
    public record Request(UUID planId) {
    }

    @Transactional
    public Outcome<OrderView> place(UUID userId, UUID idempotencyKey, Request request) {
        return idempotency.execute(userId, OPERATION, idempotencyKey, request, OrderView.class,
                () -> placeOnce(userId, request.planId()));
    }

    /** Выполняется не более одного раза на ключ; сам по себе тоже безопасен к повторам. */
    Fresh<OrderView> placeOnce(UUID userId, UUID planId) {
        orders.lockUser(userId);
        Instant now = clock.instant();

        Optional<MembershipOrder> pending = orders.findPendingByUser(userId);
        if (pending.isPresent()) {
            if (pending.get().plan().planId().equals(planId)) {
                log.info("Existing pending order returned: order_id={}", pending.get().id());
                return new Fresh<>(OrderView.from(pending.get()), HttpStatus.OK, pending.get().id());
            }
            throw MembershipErrors.pendingOrderExists();
        }
        if (memberships.existsNotEndedAt(userId, now)) {
            throw MembershipErrors.activeMembershipExists();
        }
        Plan plan = plans.findById(planId).orElseThrow(MembershipErrors::planNotAvailable);
        MembershipOrder order = MembershipOrder.place(UUID.randomUUID(), userId, plan, now);
        orders.add(order);
        log.info("Membership order placed: order_id={}, plan_id={}, amount_minor={} {}", order.id(), planId,
                order.plan().price().amountMinor(), order.plan().price().currency());
        return new Fresh<>(OrderView.from(order), HttpStatus.CREATED, order.id());
    }
}
