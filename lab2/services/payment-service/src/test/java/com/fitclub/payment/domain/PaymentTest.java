package com.fitclub.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fitclub.common.error.DomainException;

class PaymentTest {

    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    static Payment pending() {
        return Payment.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new Amount(45000, "MDL"),
                "SIMULATOR", "sim_x", NOW);
    }

    @Test
    void pendingCompletesOnceAndRepeatIsNoOp() {
        Payment payment = pending();
        assertThat(payment.succeed(NOW)).isEqualTo(Payment.CompletionOutcome.COMPLETED);
        assertThat(payment.version()).isEqualTo(1);
        assertThat(payment.succeed(NOW.plusSeconds(5))).isEqualTo(Payment.CompletionOutcome.ALREADY_IN_STATE);
        assertThat(payment.version()).isEqualTo(1);
        assertThat(payment.completedAt()).isEqualTo(NOW);
    }

    @Test
    void terminalStatusesNeverSwitch() {
        Payment succeeded = pending();
        succeeded.succeed(NOW);
        assertThatThrownBy(() -> succeeded.decline("x", NOW)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo(PaymentErrors.PAYMENT_ALREADY_COMPLETED));

        Payment declined = pending();
        declined.decline("SIMULATED_DECLINE", NOW);
        assertThat(declined.status()).isEqualTo(PaymentStatus.DECLINED);
        assertThatThrownBy(() -> declined.succeed(NOW)).isInstanceOf(DomainException.class);
    }
}
