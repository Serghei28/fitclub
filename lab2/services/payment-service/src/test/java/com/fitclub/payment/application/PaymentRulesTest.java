package com.fitclub.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.fitclub.common.error.DomainException;
import com.fitclub.payment.application.port.PaymentPorts.MembershipOrderReader;
import com.fitclub.payment.application.port.PaymentPorts.MembershipOrderReader.OrderDetails;
import com.fitclub.payment.application.port.PaymentPorts.PaymentEventPublisher;
import com.fitclub.payment.application.port.PaymentPorts.PaymentRepository;
import com.fitclub.payment.application.port.PaymentPorts.TransactionRunner;
import com.fitclub.payment.application.port.PaymentProvider;
import com.fitclub.payment.domain.Amount;
import com.fitclub.payment.domain.Payment;
import com.fitclub.payment.domain.PaymentErrors;
import com.fitclub.payment.domain.PaymentStatus;

/** Правила оплаты на fake-адаптерах (идемпотентность и гонки — в PaymentApiIT). */
class PaymentRulesTest {

    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    final Map<UUID, OrderDetails> orders = new HashMap<>();
    final Map<UUID, Payment> payments = new HashMap<>();
    final List<String> events = new ArrayList<>();
    CreatePaymentUseCase create;
    CompletePaymentUseCase complete;
    UUID user = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MembershipOrderReader reader = id -> Optional.ofNullable(orders.get(id));
        PaymentRepository repo = new PaymentRepository() {
            public Optional<Payment> findById(UUID id) {
                return Optional.ofNullable(payments.get(id));
            }

            public Optional<Payment> findByOrderId(UUID id) {
                return payments.values().stream().filter(p -> p.membershipOrderId().equals(id)).findFirst();
            }

            public boolean addIfOrderFree(Payment p) {
                if (findByOrderId(p.membershipOrderId()).isPresent()) {
                    return false;
                }
                payments.put(p.id(), p);
                return true;
            }

            public void update(Payment p) {
                payments.put(p.id(), p);
            }
        };
        PaymentEventPublisher publisher = new PaymentEventPublisher() {
            public void paymentSucceeded(Payment p) {
                events.add("PaymentSucceeded:" + p.amount().minor());
            }

            public void paymentDeclined(Payment p) {
                events.add("PaymentDeclined");
            }
        };
        TransactionRunner tx = new TransactionRunner() {
            public <T> T inTransaction(Supplier<T> work) {
                return work.get();
            }

            public <T> T readOnly(Supplier<T> work) {
                return work.get();
            }
        };
        PaymentProvider provider = new PaymentProvider() {
            public String name() {
                return "FAKE";
            }

            public Registration register(UUID paymentId, Amount amount) {
                return new Registration("fake-" + paymentId);
            }

            public Result finish(String reference, Instruction instruction) {
                return new Result(instruction == Instruction.SUCCESS, instruction == Instruction.SUCCESS ? null : "NO");
            }
        };
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        create = new CreatePaymentUseCase(reader, repo, provider, null, tx, clock);
        complete = new CompletePaymentUseCase(repo, provider, publisher, null, tx, clock);
        orders.put(orderId, new OrderDetails(orderId, user, "AWAITING_PAYMENT", 45000, "MDL"));
    }

    static void assertCode(Throwable e, String code) {
        assertThat(e).isInstanceOf(DomainException.class);
        assertThat(((DomainException) e).code()).isEqualTo(code);
    }

    @Test
    void amountComesFromMembershipOrderSnapshot() {
        var fresh = create.createOnce(user, orders.get(orderId));
        assertThat(fresh.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(fresh.body().amountMinor()).isEqualTo(45000);
        assertThat(fresh.body().currency()).isEqualTo("MDL");
        assertThat(fresh.body().status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void foreignOrderLooksLikeMissingOrder() {
        assertThatThrownBy(() -> create.create(UUID.randomUUID(), UUID.randomUUID(),
                new CreatePaymentUseCase.Request(orderId))).satisfies(e -> assertCode(e, "RESOURCE_NOT_FOUND"));
    }

    @Test
    void secondPaymentForSameOrderReturnsFirst() {
        var first = create.createOnce(user, orders.get(orderId));
        var second = create.createOnce(user, orders.get(orderId));
        assertThat(second.status()).isEqualTo(HttpStatus.OK);
        assertThat(second.resourceId()).isEqualTo(first.resourceId());
        assertThat(payments).hasSize(1);
    }

    @Test
    void paidOrderIsNotPayable() {
        UUID paid = UUID.randomUUID();
        orders.put(paid, new OrderDetails(paid, user, "PAID", 45000, "MDL"));
        assertThatThrownBy(() -> create.createOnce(user, orders.get(paid)))
                .satisfies(e -> assertCode(e, PaymentErrors.ORDER_NOT_PAYABLE));
    }

    @Test
    void completionEmitsExactlyOneEvent() {
        UUID paymentId = create.createOnce(user, orders.get(orderId)).resourceId();
        CompletePaymentUseCase.Request success = new CompletePaymentUseCase.Request(paymentId,
                PaymentProvider.Instruction.SUCCESS);

        assertThat(complete.completeOnce(user, success).body().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        complete.completeOnce(user, success);

        assertThat(events).containsExactly("PaymentSucceeded:45000");
        assertThatThrownBy(() -> complete.completeOnce(user, new CompletePaymentUseCase.Request(paymentId,
                PaymentProvider.Instruction.DECLINE))).satisfies(e -> assertCode(e,
                PaymentErrors.PAYMENT_ALREADY_COMPLETED));
    }

    @Test
    void onlyOwnerCanCompletePayment() {
        UUID paymentId = create.createOnce(user, orders.get(orderId)).resourceId();
        assertThatThrownBy(() -> complete.completeOnce(UUID.randomUUID(), new CompletePaymentUseCase.Request(paymentId,
                PaymentProvider.Instruction.SUCCESS))).satisfies(e -> assertCode(e, "RESOURCE_NOT_FOUND"));
        assertThat(events).isEmpty();
    }
}
