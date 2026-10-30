package com.fitclub.notification.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.notification.application.port.NotificationPorts.BookingStateRepository;
import com.fitclub.notification.application.port.NotificationPorts.NotificationChannel;
import com.fitclub.notification.application.port.NotificationPorts.ReminderRepository;
import com.fitclub.notification.domain.BookingState;
import com.fitclub.notification.domain.Notification;
import com.fitclub.notification.domain.NotificationTexts;
import com.fitclub.notification.domain.ReminderJob;

/**
 * Реакция на события других сервисов. Выполняется в транзакции обработчика событий (вместе с processed_events).
 * Повторы безопасны трижды: processed_events (тот же event_id), dedup_key уведомления (тот же факт с новым
 * event_id) и версия брони в проекции (устаревшее или пришедшее не по порядку событие).
 */
@Service
public class NotificationEventHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventHandler.class);

    private final NotificationChannel channel;
    private final BookingStateRepository bookings;
    private final ReminderRepository reminders;
    private final NotificationTexts texts;
    private final Duration reminderLead;
    private final Clock clock;

    public NotificationEventHandler(NotificationChannel channel, BookingStateRepository bookings,
            ReminderRepository reminders, NotificationTexts texts, ReminderSettings settings, Clock clock) {
        this.channel = channel;
        this.bookings = bookings;
        this.reminders = reminders;
        this.texts = texts;
        this.reminderLead = settings.lead();
        this.clock = clock;
    }

    /** Время до начала занятия, за которое отправляется напоминание (REMINDER_LEAD_MINUTES). */
    public record ReminderSettings(Duration lead) {
    }

    public record MembershipActivated(UUID eventId, UUID membershipId, UUID userId, String planName, Instant startsAt,
            Instant endsAt, Integer visitLimit) {
    }

    public record BookingEvent(UUID eventId, long version, UUID bookingId, UUID userId, UUID sessionId,
            String sessionTitle, Instant startsAt, String reason) {
    }

    @Transactional
    public void onMembershipActivated(MembershipActivated e) {
        boolean created = channel.deliver(Notification.create(e.userId(), Notification.Type.MEMBERSHIP_ACTIVATED,
                "Абонемент активирован", texts.membershipActivated(e.planName(), e.startsAt(), e.endsAt(),
                        e.visitLimit()),
                e.eventId(), "MEMBERSHIP_ACTIVATED:" + e.membershipId(), clock.instant()));
        log.info("Membership notification {}: membership_id={}", created ? "created" : "already exists",
                e.membershipId());
    }

    @Transactional
    public void onBookingConfirmed(BookingEvent e) {
        Optional<BookingState> known = bookings.lock(e.bookingId());
        if (BookingState.isStale(known.orElse(null), e.version())) {
            log.info("Stale BookingConfirmed ignored: booking_id={}, version={}, known={} {}", e.bookingId(),
                    e.version(), known.get().status(), known.get().version());
            return;
        }
        Instant now = clock.instant();
        bookings.save(new BookingState(e.bookingId(), e.userId(), e.sessionId(), e.sessionTitle(), e.startsAt(),
                BookingState.Status.CONFIRMED, e.version()));
        channel.deliver(Notification.create(e.userId(), Notification.Type.BOOKING_CONFIRMED, "Вы записаны на занятие",
                texts.bookingConfirmed(e.sessionTitle(), e.startsAt()), e.eventId(),
                "BOOKING_CONFIRMED:" + e.bookingId() + ":" + e.version(), now));
        ReminderJob.plan(e.bookingId(), e.userId(), e.sessionTitle(), e.startsAt(), reminderLead, now)
                .ifPresentOrElse(job -> {
                    if (reminders.addIfAbsent(job)) {
                        log.info("Reminder scheduled: booking_id={}, remind_at={}", e.bookingId(), job.remindAt());
                    }
                }, () -> log.info("No reminder: session already started, booking_id={}", e.bookingId()));
    }

    @Transactional
    public void onBookingCancelled(BookingEvent e) {
        Optional<BookingState> known = bookings.lock(e.bookingId());
        if (BookingState.isStale(known.orElse(null), e.version())) {
            log.info("Stale BookingCancelled ignored: booking_id={}, version={}", e.bookingId(), e.version());
            return;
        }
        Instant now = clock.instant();
        // «Надгробие»: даже если подтверждение ещё не приходило, опоздавшее BookingConfirmed (меньшая версия)
        // уже не создаст ни уведомления, ни напоминания
        bookings.save(new BookingState(e.bookingId(), e.userId(), e.sessionId(), e.sessionTitle(), e.startsAt(),
                BookingState.Status.CANCELLED, e.version()));
        boolean bySession = "SESSION_CANCELLED".equals(e.reason());
        channel.deliver(Notification.create(e.userId(),
                bySession ? Notification.Type.SESSION_CANCELLED : Notification.Type.BOOKING_CANCELLED,
                bySession ? "Занятие отменено" : "Запись отменена",
                bySession ? texts.sessionCancelled(e.sessionTitle(), e.startsAt())
                        : texts.bookingCancelledByUser(e.sessionTitle(), e.startsAt()),
                e.eventId(), "BOOKING_CANCELLED:" + e.bookingId(), now));
        reminders.lockByBooking(e.bookingId()).ifPresent(job -> {
            if (job.cancel(now)) {
                reminders.update(job);
                log.info("Reminder cancelled: booking_id={}", e.bookingId());
            }
        });
    }
}
