package com.fitclub.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Правила напоминаний и форматирование времени — без Spring и без текущего времени компьютера. */
class NotificationDomainTest {

    static final Instant START = Instant.parse("2026-11-10T07:00:00Z");
    static final Duration LEAD = Duration.ofMinutes(60);

    @Test
    void reminderIsPlannedLeadBeforeStart() {
        ReminderJob job = ReminderJob.plan(UUID.randomUUID(), UUID.randomUUID(), "Йога", START, LEAD,
                START.minus(Duration.ofDays(1))).orElseThrow();
        assertThat(job.remindAt()).isEqualTo(START.minus(LEAD));
        assertThat(job.status()).isEqualTo(ReminderJob.Status.PENDING);
    }

    @Test
    void lateBookingGetsReminderAtNearestProcessing() {
        Instant now = START.minus(Duration.ofMinutes(20));
        ReminderJob job = ReminderJob.plan(UUID.randomUUID(), UUID.randomUUID(), "Йога", START, LEAD, now)
                .orElseThrow();
        assertThat(job.remindAt()).isEqualTo(now);
    }

    @Test
    void noReminderForStartedSession() {
        assertThat(ReminderJob.plan(UUID.randomUUID(), UUID.randomUUID(), "Йога", START, LEAD, START)).isEmpty();
        assertThat(ReminderJob.plan(UUID.randomUUID(), UUID.randomUUID(), "Йога", START, LEAD, START.plusSeconds(1)))
                .isEmpty();
    }

    @Test
    void dueReminderForSessionThatAlreadyStartedIsSkipped() {
        ReminderJob job = ReminderJob.plan(UUID.randomUUID(), UUID.randomUUID(), "Йога", START, LEAD,
                START.minus(Duration.ofDays(1))).orElseThrow();
        assertThat(job.decide(START.minusSeconds(1))).isEqualTo(ReminderJob.Decision.SEND);
        assertThat(job.decide(START)).isEqualTo(ReminderJob.Decision.SKIP_STARTED);
        assertThat(job.cancel(START)).isTrue();
        assertThat(job.cancel(START)).isFalse();
    }

    @Test
    void staleBookingEventsAreDetectedByVersion() {
        BookingState cancelled = new BookingState(UUID.randomUUID(), UUID.randomUUID(), null, null, null,
                BookingState.Status.CANCELLED, 2);
        assertThat(BookingState.isStale(cancelled, 1)).isTrue();
        assertThat(BookingState.isStale(cancelled, 2)).isTrue();
        assertThat(BookingState.isStale(null, 1)).isFalse();
    }

    @Test
    void timesAreShownInClubTimeZone() {
        NotificationTexts texts = new NotificationTexts(ZoneId.of("Europe/Chisinau"));
        // 10 ноября — зимнее время Кишинёва, UTC+2
        assertThat(texts.time(START)).isEqualTo("10.11.2026 09:00");
        assertThat(texts.reminder("Йога", START)).contains("10.11.2026 09:00");
    }
}
