package com.fitclub.payment.application.port;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.fitclub.payment.domain.Payment;

/** Остальные порты payment-service: узкие интерфейсы под нужды сценариев оплаты (ISP). */
public final class PaymentPorts {

    private PaymentPorts() {
    }

    /**
     * Чтение реквизитов заказа из membership-service. Только чтение: Payment не может менять
     * заказы или тарифы. Пустой Optional — заказа нет.
     */
    public interface MembershipOrderReader {

        Optional<OrderDetails> paymentDetails(UUID orderId);

        record OrderDetails(UUID orderId, UUID userId, String status, long amountMinor, String currency) {

            public boolean awaitingPayment() {
                return "AWAITING_PAYMENT".equals(status);
            }
        }
    }

    public interface PaymentRepository {

        Optional<Payment> findById(UUID id);

        Optional<Payment> findByOrderId(UUID membershipOrderId);

        /** Второй платёж по тому же заказу (в том числе при гонке) → false, платёж не сохраняется. */
        boolean addIfOrderFree(Payment payment);

        void update(Payment payment);
    }

    /** События оплаты; реализация пишет их в outbox текущей транзакции. */
    public interface PaymentEventPublisher {

        void paymentSucceeded(Payment payment);

        void paymentDeclined(Payment payment);
    }

    /** Граница локальной транзакции: вызов Membership выполняется до неё. */
    public interface TransactionRunner {

        <T> T inTransaction(Supplier<T> work);

        <T> T readOnly(Supplier<T> work);
    }
}
