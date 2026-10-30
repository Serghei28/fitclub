package com.fitclub.payment.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fitclub.payment.application.port.PaymentPorts.PaymentRepository;
import com.fitclub.payment.domain.Amount;
import com.fitclub.payment.domain.Payment;
import com.fitclub.payment.domain.PaymentErrors;
import com.fitclub.payment.domain.PaymentStatus;

/**
 * Адаптер хранения платежей. Новый платёж вставляется через INSERT … ON CONFLICT (membership_order_id) DO NOTHING:
 * при гонке двух запросов на один заказ второй не ломает транзакцию ошибкой, а узнаёт, что платёж уже есть.
 */
@Repository
class JpaPaymentRepository implements PaymentRepository {

    private final PaymentJpaRepository jpa;
    private final JdbcTemplate jdbc;

    JpaPaymentRepository(PaymentJpaRepository jpa, JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return jpa.findById(id).map(JpaPaymentRepository::toDomain);
    }

    @Override
    public Optional<Payment> findByOrderId(UUID membershipOrderId) {
        return jpa.findByMembershipOrderId(membershipOrderId).map(JpaPaymentRepository::toDomain);
    }

    @Override
    public boolean addIfOrderFree(Payment p) {
        int inserted = jdbc.update("""
                insert into payments (id, membership_order_id, user_id, amount_minor, currency, status, provider,
                    provider_reference, created_at, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                on conflict (membership_order_id) do nothing
                """, p.id(), p.membershipOrderId(), p.userId(), p.amount().minor(), p.amount().currency(),
                p.status().name(), p.provider(), p.providerReference(), Timestamp.from(p.createdAt()));
        return inserted == 1;
    }

    @Override
    public void update(Payment p) {
        PaymentEntity e = jpa.findById(p.id()).orElseThrow(PaymentErrors::paymentNotFound);
        if (e.version != p.version() - 1) {
            throw new OptimisticLockingFailureException("Payment " + p.id() + " was modified concurrently");
        }
        e.status = p.status();
        e.declineReason = p.declineReason();
        e.completedAt = p.completedAt();
        jpa.flush();
    }

    private static Payment toDomain(PaymentEntity e) {
        return Payment.restore(e.id, e.membershipOrderId, e.userId, new Amount(e.amountMinor, e.currency), e.status,
                e.provider, e.providerReference, e.declineReason, e.createdAt, e.completedAt, e.version);
    }
}

interface PaymentJpaRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByMembershipOrderId(UUID membershipOrderId);
}

@Entity
@Table(name = "payments")
class PaymentEntity {

    @Id
    UUID id;

    @Column(name = "membership_order_id", nullable = false)
    UUID membershipOrderId;

    @Column(name = "user_id", nullable = false)
    UUID userId;

    @Column(name = "amount_minor", nullable = false)
    long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    PaymentStatus status;

    @Column(nullable = false, length = 32)
    String provider;

    @Column(name = "provider_reference", length = 100)
    String providerReference;

    @Column(name = "decline_reason", length = 200)
    String declineReason;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "completed_at")
    Instant completedAt;

    @Version
    long version;
}
