package com.fitclub.membership.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Выданное право на посещения. Действует на полуинтервале [startsAt, endsAt): в момент endsAt уже недействителен.
 * Действительность считается по времени, а не по строковому статусу. Лимит посещений здесь только
 * описывает право; авторитетный учёт использованных посещений ведёт booking-service.
 */
public record Membership(UUID id, UUID userId, UUID orderId, UUID paymentId, String planName, Integer visitLimit,
        Instant startsAt, Instant endsAt, Instant createdAt) {

    public Membership {
        Objects.requireNonNull(id);
        Objects.requireNonNull(userId);
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("Membership period must be positive");
        }
    }

    /**
     * Абонемент по оплаченному заказу: начало — сохранённый момент успешной оплаты (а не время обработки события),
     * конец — начало + срок тарифа из снимка заказа.
     */
    public static Membership activate(UUID id, MembershipOrder paidOrder, Instant now) {
        Instant start = paidOrder.paidAt();
        return new Membership(id, paidOrder.userId(), paidOrder.id(), paidOrder.paymentId(),
                paidOrder.plan().planName(), paidOrder.plan().visitLimit(), start,
                start.plus(Duration.ofDays(paidOrder.plan().durationDays())), now);
    }

    public boolean isValidAt(Instant moment) {
        return !moment.isBefore(startsAt) && moment.isBefore(endsAt);
    }

    /** Ещё не закончился (действует сейчас или начнётся позже). */
    public boolean notEndedAt(Instant moment) {
        return moment.isBefore(endsAt);
    }

    public Status statusAt(Instant moment) {
        if (moment.isBefore(startsAt)) {
            return Status.UPCOMING;
        }
        return moment.isBefore(endsAt) ? Status.ACTIVE : Status.EXPIRED;
    }

    public enum Status {
        UPCOMING, ACTIVE, EXPIRED
    }
}
