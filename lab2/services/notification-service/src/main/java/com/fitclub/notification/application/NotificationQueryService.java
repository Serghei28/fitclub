package com.fitclub.notification.application;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.common.error.DomainException;
import com.fitclub.common.web.PageResponse;
import com.fitclub.notification.application.port.NotificationPorts.NotificationRepository;
import com.fitclub.notification.domain.Notification;

/** Уведомления текущего пользователя. Чужое уведомление не существует (404); прочитать может только получатель. */
@Service
public class NotificationQueryService {

    private final NotificationRepository notifications;
    private final Clock clock;

    public NotificationQueryService(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<Notification> list(UUID userId, boolean unreadOnly, int page, int size) {
        return notifications.findByUser(userId, unreadOnly, page, Math.min(size, PageResponse.MAX_SIZE));
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notifications.countUnread(userId);
    }

    @Transactional
    public Notification markRead(UUID userId, UUID notificationId) {
        Notification notification = notifications.findById(notificationId)
                .filter(n -> n.isOwnedBy(userId))
                .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "RESOURCE_NOT_FOUND",
                        "Ресурс не найден", "Уведомление не найдено."));
        if (!notification.isRead()) {
            notification.markRead(clock.instant());
            notifications.update(notification);
        }
        return notification;
    }
}
