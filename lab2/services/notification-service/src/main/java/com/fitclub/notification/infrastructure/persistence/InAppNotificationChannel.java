package com.fitclub.notification.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fitclub.notification.application.port.NotificationPorts.NotificationChannel;
import com.fitclub.notification.domain.Notification;
import com.fitclub.notification.infrastructure.persistence.NotificationPersistence.JpaNotificationRepository;

/**
 * InApp-канал: уведомление сохраняется в notification_db и становится доступно через GET /api/notifications.
 * Это реальная доставка для лабораторной (не вывод в stdout). Email или push добавляются отдельным адаптером
 * NotificationChannel без изменения сценариев.
 */
@Component
class InAppNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(InAppNotificationChannel.class);

    private final JpaNotificationRepository store;

    InAppNotificationChannel(JpaNotificationRepository store) {
        this.store = store;
    }

    @Override
    public boolean deliver(Notification notification) {
        boolean created = store.addIfAbsent(notification);
        if (created) {
            log.info("Notification delivered in-app: type={}, notification_id={}", notification.type(),
                    notification.id());
        } else {
            log.info("Duplicate notification suppressed: type={}", notification.type());
        }
        return created;
    }
}
