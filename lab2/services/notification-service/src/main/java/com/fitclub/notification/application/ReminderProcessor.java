package com.fitclub.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.notification.application.port.NotificationPorts.NotificationChannel;
import com.fitclub.notification.application.port.NotificationPorts.ReminderRepository;
import com.fitclub.notification.domain.Notification;
import com.fitclub.notification.domain.NotificationTexts;
import com.fitclub.notification.domain.ReminderJob;

/**
 * Отправка наступивших напоминаний. Задачи читаются из БД (переживают перезапуск), берутся с
 * FOR UPDATE SKIP LOCKED и меняют статус в той же транзакции, что и доставка, — напоминание уходит один раз.
 * Напоминание о начавшемся занятии не отправляется (SKIPPED).
 */
@Service
public class ReminderProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReminderProcessor.class);

    private final ReminderRepository reminders;
    private final NotificationChannel channel;
    private final NotificationTexts texts;
    private final Clock clock;

    public ReminderProcessor(ReminderRepository reminders, NotificationChannel channel, NotificationTexts texts,
            Clock clock) {
        this.reminders = reminders;
        this.channel = channel;
        this.texts = texts;
        this.clock = clock;
    }

    /** @return сколько напоминаний отправлено */
    @Transactional
    public int processDue(int batchSize) {
        Instant now = clock.instant();
        List<ReminderJob> due = reminders.claimDue(now, batchSize);
        int sent = 0;
        for (ReminderJob job : due) {
            if (job.decide(now) == ReminderJob.Decision.SEND) {
                channel.deliver(Notification.create(job.userId(), Notification.Type.SESSION_REMINDER, "Скоро занятие",
                        texts.reminder(job.sessionTitle(), job.startsAt()), null, "REMINDER:" + job.bookingId(), now));
                job.markSent(now);
                sent++;
            } else {
                job.markSkipped(now);
                log.info("Reminder skipped, session already started: booking_id={}", job.bookingId());
            }
            reminders.update(job);
        }
        if (!due.isEmpty()) {
            log.info("Reminders processed: due={}, sent={}", due.size(), sent);
        }
        return sent;
    }
}
