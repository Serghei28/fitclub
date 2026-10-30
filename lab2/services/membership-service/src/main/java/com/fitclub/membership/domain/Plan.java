package com.fitclub.membership.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Тариф. visitLimit == null — безлимит, иначе положительное число посещений за срок.
 * Изменение тарифа не затрагивает уже созданные заказы: они хранят свой снимок (PlanSnapshot).
 */
public final class Plan {

    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_DURATION_DAYS = 3660;

    private final UUID id;
    private String name;
    private int durationDays;
    private Integer visitLimit;
    private Money price;
    private boolean active;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private Plan(UUID id, String name, int durationDays, Integer visitLimit, Money price, boolean active,
            Instant createdAt, Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.name = name;
        this.durationDays = durationDays;
        this.visitLimit = visitLimit;
        this.price = price;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Plan create(UUID id, String name, int durationDays, Integer visitLimit, Money price, Instant now) {
        return new Plan(id, validName(name), validDuration(durationDays), validLimit(visitLimit), price, true, now, now,
                0);
    }

    public static Plan restore(UUID id, String name, int durationDays, Integer visitLimit, Money price, boolean active,
            Instant createdAt, Instant updatedAt, long version) {
        return new Plan(id, name, durationDays, visitLimit, price, active, createdAt, updatedAt, version);
    }

    /** Изменения тарифа администратором. null в поле — «не менять»; unlimited=true делает тариф безлимитным. */
    public record Changes(String name, Integer durationDays, Integer visitLimit, Boolean unlimited, Long priceMinor,
            String currency, Boolean active) {
    }

    public void change(Changes changes, Instant now) {
        if (changes.name() != null) {
            name = validName(changes.name());
        }
        if (changes.durationDays() != null) {
            durationDays = validDuration(changes.durationDays());
        }
        if (Boolean.TRUE.equals(changes.unlimited())) {
            visitLimit = null;
        } else if (changes.visitLimit() != null) {
            visitLimit = validLimit(changes.visitLimit());
        }
        if (changes.priceMinor() != null || changes.currency() != null) {
            price = new Money(changes.priceMinor() == null ? price.amountMinor() : changes.priceMinor(),
                    changes.currency() == null ? price.currency() : changes.currency());
        }
        if (changes.active() != null) {
            active = changes.active();
        }
        updatedAt = now;
    }

    public PlanSnapshot snapshot() {
        return new PlanSnapshot(id, name, durationDays, visitLimit, price);
    }

    private static String validName(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > MAX_NAME_LENGTH) {
            throw MembershipErrors.invalidPlan("name", "Название тарифа — от 1 до " + MAX_NAME_LENGTH + " символов.");
        }
        return value;
    }

    private static int validDuration(int days) {
        if (days < 1 || days > MAX_DURATION_DAYS) {
            throw MembershipErrors.invalidPlan("duration_days", "Срок действия — от 1 до " + MAX_DURATION_DAYS
                    + " дней.");
        }
        return days;
    }

    private static Integer validLimit(Integer limit) {
        if (limit != null && limit < 1) {
            throw MembershipErrors.invalidPlan("visit_limit",
                    "Лимит посещений — положительное число; для безлимитного тарифа не указывайте его.");
        }
        return limit;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int durationDays() {
        return durationDays;
    }

    public Integer visitLimit() {
        return visitLimit;
    }

    public boolean unlimited() {
        return visitLimit == null;
    }

    public Money price() {
        return price;
    }

    public boolean active() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
