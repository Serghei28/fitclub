package com.fitclub.membership.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.common.security.AuthenticatedUser;
import com.fitclub.membership.application.port.MembershipPorts.MembershipRepository;
import com.fitclub.membership.application.port.MembershipPorts.OrderRepository;
import com.fitclub.membership.application.port.MembershipPorts.PlanRepository;
import com.fitclub.membership.domain.Membership;
import com.fitclub.membership.domain.MembershipErrors;
import com.fitclub.membership.domain.MembershipOrder;
import com.fitclub.membership.domain.Plan;

/**
 * Чтение: тарифы, свой заказ и абонементы, а также внутренние данные для payment-service (реквизиты заказа)
 * и booking-service (право на посещение). Чужой заказ для обычного клиента не существует (404).
 */
@Service
@Transactional(readOnly = true)
public class MembershipQueryService {

    private final PlanRepository plans;
    private final OrderRepository orders;
    private final MembershipRepository memberships;
    private final Clock clock;

    public MembershipQueryService(PlanRepository plans, OrderRepository orders, MembershipRepository memberships,
            Clock clock) {
        this.plans = plans;
        this.orders = orders;
        this.memberships = memberships;
        this.clock = clock;
    }

    public List<Plan> plans(AuthenticatedUser viewer, boolean includeInactive) {
        return plans.findAll(includeInactive && viewer.isAdmin());
    }

    public MembershipOrder order(UUID orderId, AuthenticatedUser viewer) {
        return orders.findById(orderId)
                .filter(order -> order.isOwnedBy(viewer.id()) || viewer.isAdmin())
                .orElseThrow(() -> MembershipErrors.notFound("Заказ"));
    }

    public record MembershipView(Membership membership, Membership.Status status) {
    }

    public List<MembershipView> myMemberships(UUID userId) {
        Instant now = clock.instant();
        return memberships.findByUser(userId).stream()
                .sorted(Comparator.comparing(Membership::startsAt).reversed())
                .map(m -> new MembershipView(m, m.statusAt(now)))
                .toList();
    }

    /** Для payment-service: владелец, сумма и валюта берутся только отсюда, а не от клиента. */
    public MembershipOrder paymentDetails(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> MembershipErrors.notFound("Заказ"));
    }

    /** Для booking-service: какой абонемент действует в момент начала занятия. */
    public Optional<Membership> eligibility(UUID userId, Instant at) {
        return memberships.findValidAt(userId, at);
    }
}
