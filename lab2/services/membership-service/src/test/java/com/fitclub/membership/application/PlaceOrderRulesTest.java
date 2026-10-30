package com.fitclub.membership.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.fitclub.common.error.DomainException;
import com.fitclub.membership.application.port.MembershipPorts.MembershipRepository;
import com.fitclub.membership.application.port.MembershipPorts.OrderRepository;
import com.fitclub.membership.application.port.MembershipPorts.PlanRepository;
import com.fitclub.membership.domain.Membership;
import com.fitclub.membership.domain.MembershipErrors;
import com.fitclub.membership.domain.MembershipOrder;
import com.fitclub.membership.domain.Money;
import com.fitclub.membership.domain.OrderStatus;
import com.fitclub.membership.domain.Plan;

/** Бизнес-правила заказа на fake-адаптерах (идемпотентность и гонки — в MembershipApiIT на PostgreSQL). */
class PlaceOrderRulesTest {

    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    final Map<UUID, Plan> plans = new HashMap<>();
    final List<MembershipOrder> orders = new ArrayList<>();
    final List<Membership> memberships = new ArrayList<>();
    PlaceOrderUseCase useCase;
    UUID user = UUID.randomUUID();
    Plan basic;
    Plan premium;

    @BeforeEach
    void setUp() {
        PlanRepository planRepo = new PlanRepository() {
            public Optional<Plan> findById(UUID id) {
                return Optional.ofNullable(plans.get(id));
            }

            public List<Plan> findAll(boolean includeInactive) {
                return List.copyOf(plans.values());
            }

            public void add(Plan plan) {
                plans.put(plan.id(), plan);
            }

            public void update(Plan plan) {
                plans.put(plan.id(), plan);
            }
        };
        OrderRepository orderRepo = new OrderRepository() {
            public Optional<MembershipOrder> findById(UUID id) {
                return orders.stream().filter(o -> o.id().equals(id)).findFirst();
            }

            public Optional<MembershipOrder> findPendingByUser(UUID userId) {
                return orders.stream().filter(o -> o.userId().equals(userId) && o.awaitingPayment()).findFirst();
            }

            public void lockUser(UUID userId) {
            }

            public void add(MembershipOrder order) {
                orders.add(order);
            }
        };
        MembershipRepository membershipRepo = new MembershipRepository() {
            public List<Membership> findByUser(UUID userId) {
                return memberships.stream().filter(m -> m.userId().equals(userId)).toList();
            }

            public Optional<Membership> findValidAt(UUID userId, Instant moment) {
                return findByUser(userId).stream().filter(m -> m.isValidAt(moment)).findFirst();
            }

            public boolean existsNotEndedAt(UUID userId, Instant moment) {
                return findByUser(userId).stream().anyMatch(m -> m.notEndedAt(moment));
            }
        };
        useCase = new PlaceOrderUseCase(planRepo, orderRepo, membershipRepo, null, Clock.fixed(NOW, ZoneOffset.UTC));
        basic = Plan.create(UUID.randomUUID(), "8 посещений", 30, 8, new Money(45000, "MDL"), NOW);
        premium = Plan.create(UUID.randomUUID(), "Безлимит", 30, null, new Money(90000, "MDL"), NOW);
        plans.put(basic.id(), basic);
        plans.put(premium.id(), premium);
    }

    static void assertCode(Throwable e, String code) {
        assertThat(e).isInstanceOf(DomainException.class);
        assertThat(((DomainException) e).code()).isEqualTo(code);
    }

    @Test
    void newOrderTakesPriceAndLimitsFromPlan() {
        var fresh = useCase.placeOnce(user, basic.id());
        assertThat(fresh.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(fresh.body().priceMinor()).isEqualTo(45000);
        assertThat(fresh.body().currency()).isEqualTo("MDL");
        assertThat(fresh.body().visitLimit()).isEqualTo(8);
        assertThat(fresh.body().status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }

    @Test
    void repeatedOrderForSamePlanReturnsExistingPendingOrder() {
        var first = useCase.placeOnce(user, basic.id());
        var second = useCase.placeOnce(user, basic.id());
        assertThat(second.status()).isEqualTo(HttpStatus.OK);
        assertThat(second.resourceId()).isEqualTo(first.resourceId());
        assertThat(orders).hasSize(1);
    }

    @Test
    void anotherPlanWhilePendingIsConflict() {
        useCase.placeOnce(user, basic.id());
        assertThatThrownBy(() -> useCase.placeOnce(user, premium.id()))
                .satisfies(e -> assertCode(e, MembershipErrors.PENDING_ORDER_EXISTS));
    }

    @Test
    void notEndedMembershipBlocksNewOrder() {
        memberships.add(new Membership(UUID.randomUUID(), user, UUID.randomUUID(), UUID.randomUUID(), "8 посещений", 8,
                NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(29)), NOW));
        assertThatThrownBy(() -> useCase.placeOnce(user, basic.id()))
                .satisfies(e -> assertCode(e, MembershipErrors.ACTIVE_MEMBERSHIP_EXISTS));
    }

    @Test
    void expiredMembershipDoesNotBlock() {
        memberships.add(new Membership(UUID.randomUUID(), user, UUID.randomUUID(), UUID.randomUUID(), "8 посещений", 8,
                NOW.minus(Duration.ofDays(31)), NOW, NOW));
        assertThat(useCase.placeOnce(user, basic.id()).status()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void unknownPlanIsNotAvailable() {
        assertThatThrownBy(() -> useCase.placeOnce(user, UUID.randomUUID()))
                .satisfies(e -> assertCode(e, MembershipErrors.PLAN_NOT_AVAILABLE));
    }
}
