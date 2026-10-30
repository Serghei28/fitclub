package com.fitclub.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fitclub.notification.application.NotificationEventHandler.BookingEvent;
import com.fitclub.notification.application.NotificationEventHandler.ReminderSettings;
import com.fitclub.notification.application.port.NotificationPorts.BookingStateRepository;
import com.fitclub.notification.application.port.NotificationPorts.NotificationChannel;
import com.fitclub.notification.application.port.NotificationPorts.ReminderRepository;
import com.fitclub.notification.domain.BookingState;
import com.fitclub.notification.domain.Notification;
import com.fitclub.notification.domain.NotificationTexts;
import com.fitclub.notification.domain.ReminderJob;

/**
 * Сценарии уведомлений на fake-адаптерах. Канал доставки подменён записью в список:
 * сценарии работают без изменений с любым NotificationChannel (OCP/DIP).
 */
class NotificationScenariosTest {

    static final Instant START = Instant.parse("2026-11-10T07:00:00Z");

    final Map<String, Notification> delivered = new LinkedHashMap<>();
    final Map<UUID, BookingState> states = new HashMap<>();
    final Map<UUID, ReminderJob> jobs = new HashMap<>();
    final MutableClock clock = new MutableClock(START.minus(Duration.ofDays(1)));
    NotificationEventHandler handler;
    ReminderProcessor processor;

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant start) {
            now = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        NotificationChannel channel = n -> delivered.putIfAbsent(n.dedupKey(), n) == null;
        BookingStateRepository stateRepo = new BookingStateRepository() {
            public Optional<BookingState> lock(UUID id) {
                return Optional.ofNullable(states.get(id));
            }

            public void save(BookingState s) {
                states.put(s.bookingId(), s);
            }
        };
        ReminderRepository reminderRepo = new ReminderRepository() {
            public boolean addIfAbsent(ReminderJob j) {
                return jobs.putIfAbsent(j.bookingId(), j) == null;
            }

            public Optional<ReminderJob> lockByBooking(UUID id) {
                return Optional.ofNullable(jobs.get(id));
            }

            public List<ReminderJob> claimDue(Instant now, int limit) {
                return new ArrayList<>(jobs.values().stream().filter(j -> j.status() == ReminderJob.Status.PENDING
                        && !j.remindAt().isAfter(now)).toList());
            }

            public void update(ReminderJob j) {
                jobs.put(j.bookingId(), j);
            }
        };
        NotificationTexts texts = new NotificationTexts(ZoneId.of("Europe/Chisinau"));
        handler = new NotificationEventHandler(channel, stateRepo, reminderRepo, texts,
                new ReminderSettings(Duration.ofMinutes(60)), clock);
        processor = new ReminderProcessor(reminderRepo, channel, texts, clock);
    }

    BookingEvent event(UUID bookingId, long version, String reason) {
        return new BookingEvent(UUID.randomUUID(), version, bookingId, UUID.randomUUID(), UUID.randomUUID(), "Йога",
                START, reason);
    }

    List<Notification.Type> types() {
        return delivered.values().stream().map(Notification::type).toList();
    }

    @Test
    void confirmationCreatesNotificationAndOneReminderDespiteRedelivery() {
        UUID booking = UUID.randomUUID();
        handler.onBookingConfirmed(event(booking, 1, null));
        handler.onBookingConfirmed(event(booking, 1, null));

        assertThat(types()).containsExactly(Notification.Type.BOOKING_CONFIRMED);
        assertThat(jobs.get(booking).remindAt()).isEqualTo(START.minus(Duration.ofMinutes(60)));
    }

    @Test
    void reminderIsSentOnceWhenDue() {
        UUID booking = UUID.randomUUID();
        handler.onBookingConfirmed(event(booking, 1, null));

        clock.now = START.minus(Duration.ofMinutes(61));
        assertThat(processor.processDue(10)).isZero();
        clock.now = START.minus(Duration.ofMinutes(60));
        assertThat(processor.processDue(10)).isEqualTo(1);
        assertThat(processor.processDue(10)).isZero();
        assertThat(types()).containsExactly(Notification.Type.BOOKING_CONFIRMED, Notification.Type.SESSION_REMINDER);
    }

    @Test
    void cancellationSuppressesReminder() {
        UUID booking = UUID.randomUUID();
        handler.onBookingConfirmed(event(booking, 1, null));
        handler.onBookingCancelled(event(booking, 2, "USER"));

        clock.now = START.minus(Duration.ofMinutes(30));
        assertThat(processor.processDue(10)).isZero();
        assertThat(jobs.get(booking).status()).isEqualTo(ReminderJob.Status.CANCELLED);
        assertThat(types()).containsExactly(Notification.Type.BOOKING_CONFIRMED, Notification.Type.BOOKING_CANCELLED);
    }

    @Test
    void cancellationArrivingFirstBlocksLateConfirmation() {
        UUID booking = UUID.randomUUID();
        handler.onBookingCancelled(event(booking, 2, "SESSION_CANCELLED"));
        handler.onBookingConfirmed(event(booking, 1, null));

        assertThat(types()).containsExactly(Notification.Type.SESSION_CANCELLED);
        assertThat(jobs).doesNotContainKey(booking);
        assertThat(states.get(booking).status()).isEqualTo(BookingState.Status.CANCELLED);
    }

    @Test
    void bookingShortlyBeforeStartGetsImmediateReminderButStartedGetsNone() {
        UUID soon = UUID.randomUUID();
        clock.now = START.minus(Duration.ofMinutes(10));
        handler.onBookingConfirmed(event(soon, 1, null));
        assertThat(processor.processDue(10)).isEqualTo(1);

        UUID started = UUID.randomUUID();
        clock.now = START.plusSeconds(1);
        handler.onBookingConfirmed(event(started, 1, null));
        assertThat(jobs).doesNotContainKey(started);
    }
}
