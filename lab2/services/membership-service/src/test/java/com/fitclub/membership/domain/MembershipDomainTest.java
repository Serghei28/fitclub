package com.fitclub.membership.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fitclub.common.error.DomainException;

class MembershipDomainTest {

    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void finiteAndUnlimitedPlans() {
        Plan finite = Plan.create(UUID.randomUUID(), "8 посещений", 30, 8, new Money(45000, "MDL"), NOW);
        Plan unlimited = Plan.create(UUID.randomUUID(), "Безлимит", 30, null, new Money(90000, "MDL"), NOW);
        assertThat(finite.unlimited()).isFalse();
        assertThat(unlimited.unlimited()).isTrue();
    }

    @Test
    void invalidPlanParametersAreRejected() {
        assertThatThrownBy(() -> Plan.create(UUID.randomUUID(), "X", 30, 0, new Money(1, "MDL"), NOW))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.field()).isEqualTo("visit_limit"));
        assertThatThrownBy(() -> Plan.create(UUID.randomUUID(), "X", 0, 5, new Money(1, "MDL"), NOW))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.field()).isEqualTo("duration_days"));
        assertThatThrownBy(() -> new Money(-1, "MDL")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new Money(100, "mdl")).isInstanceOf(DomainException.class);
    }

    @Test
    void orderKeepsSnapshotWhenPlanChangesLater() {
        Plan plan = Plan.create(UUID.randomUUID(), "8 посещений", 30, 8, new Money(45000, "MDL"), NOW);
        MembershipOrder order = MembershipOrder.place(UUID.randomUUID(), UUID.randomUUID(), plan, NOW);

        plan.change(new Plan.Changes(null, 60, 20, null, 99900L, null, null), NOW);

        assertThat(order.plan().price()).isEqualTo(new Money(45000, "MDL"));
        assertThat(order.plan().durationDays()).isEqualTo(30);
        assertThat(order.plan().visitLimit()).isEqualTo(8);
    }

    @Test
    void inactivePlanCannotBeOrdered() {
        Plan plan = Plan.create(UUID.randomUUID(), "Старый", 30, 8, new Money(45000, "MDL"), NOW);
        plan.change(new Plan.Changes(null, null, null, null, null, null, false), NOW);
        assertThatThrownBy(() -> MembershipOrder.place(UUID.randomUUID(), UUID.randomUUID(), plan, NOW))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(MembershipErrors.PLAN_NOT_AVAILABLE));
    }

    @Test
    void membershipIsValidOnHalfOpenInterval() {
        Instant start = NOW;
        Instant end = NOW.plus(Duration.ofDays(30));
        Membership m = new Membership(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "8 посещений", 8, start, end, NOW);

        assertThat(m.isValidAt(start.minusMillis(1))).isFalse();
        assertThat(m.isValidAt(start)).isTrue();
        assertThat(m.isValidAt(end.minusMillis(1))).isTrue();
        assertThat(m.isValidAt(end)).isFalse();
        assertThat(m.statusAt(start.minusSeconds(1))).isEqualTo(Membership.Status.UPCOMING);
        assertThat(m.statusAt(end)).isEqualTo(Membership.Status.EXPIRED);
    }
}
