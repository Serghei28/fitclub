package com.fitclub.booking.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Авторитетный учёт посещений одного абонемента: будущие зарезервированные и уже прошедшие CONFIRMED-записи.
 * Хранится в Booking рядом с бронированиями, поэтому запись на занятие — одна локальная транзакция
 * без распределённой транзакции с Membership. visitLimit == null — безлимит (лимит не проверяется).
 */
public final class MembershipUsage {

    private final UUID membershipId;
    private final UUID userId;
    private final Integer visitLimit;
    private int usedCount;

    public MembershipUsage(UUID membershipId, UUID userId, Integer visitLimit, int usedCount) {
        this.membershipId = Objects.requireNonNull(membershipId);
        this.userId = Objects.requireNonNull(userId);
        this.visitLimit = visitLimit;
        this.usedCount = usedCount;
    }

    public boolean unlimited() {
        return visitLimit == null;
    }

    public boolean exhausted() {
        return !unlimited() && usedCount >= visitLimit;
    }

    public void reserveVisit() {
        if (exhausted()) {
            throw BookingErrors.visitLimitReached();
        }
        usedCount++;
    }

    public void returnVisit() {
        if (usedCount > 0) {
            usedCount--;
        }
    }

    public Integer remaining() {
        return unlimited() ? null : Math.max(0, visitLimit - usedCount);
    }

    public UUID membershipId() {
        return membershipId;
    }

    public UUID userId() {
        return userId;
    }

    public Integer visitLimit() {
        return visitLimit;
    }

    public int usedCount() {
        return usedCount;
    }
}
