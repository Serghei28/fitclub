package com.fitclub.notification.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fitclub.common.web.PageResponse;
import com.fitclub.notification.domain.BookingState;
import com.fitclub.notification.domain.Notification;
import com.fitclub.notification.domain.ReminderJob;

/** Порты notification-service: доставка, чтение уведомлений, проекция бронирований, задачи напоминаний. */
public final class NotificationPorts {

    private NotificationPorts() {
    }

    /**
     * Канал доставки (OCP): сценарии не знают, как именно доставляется уведомление.
     * Реализация в проекте — InApp: уведомление сохраняется и доступно через API. Email/push добавляются
     * новым адаптером. Контракт: повторная доставка того же факта (dedup_key) не создаёт дубль и возвращает false.
     */
    public interface NotificationChannel {

        boolean deliver(Notification notification);
    }

    /** Чтение и отметка прочтения сохранённых уведомлений. */
    public interface NotificationRepository {

        Optional<Notification> findById(UUID id);

        PageResponse<Notification> findByUser(UUID userId, boolean unreadOnly, int page, int size);

        long countUnread(UUID userId);

        void update(Notification notification);
    }

    public interface BookingStateRepository {

        /** Состояние брони с блокировкой строки (если есть). */
        Optional<BookingState> lock(UUID bookingId);

        /** Вставка или замена состояния. */
        void save(BookingState state);
    }

    public interface ReminderRepository {

        /** Одна задача на бронь (uq_reminder_jobs_booking): повтор не создаёт вторую. */
        boolean addIfAbsent(ReminderJob job);

        Optional<ReminderJob> lockByBooking(UUID bookingId);

        /**
         * Наступившие ожидающие задачи с блокировкой FOR UPDATE SKIP LOCKED: параллельный обработчик
         * (второй экземпляр сервиса) пропустит уже взятые строки, поэтому напоминание не уйдёт дважды.
         */
        List<ReminderJob> claimDue(Instant now, int limit);

        void update(ReminderJob job);
    }
}
