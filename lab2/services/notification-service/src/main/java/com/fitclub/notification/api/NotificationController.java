package com.fitclub.notification.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fitclub.common.security.AuthenticatedUser;
import com.fitclub.common.web.PageResponse;
import com.fitclub.notification.application.NotificationQueryService;
import com.fitclub.notification.domain.Notification;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Уведомления", description = "Внутренние уведомления текущего пользователя")
class NotificationController {

    private final NotificationQueryService notifications;

    NotificationController(NotificationQueryService notifications) {
        this.notifications = notifications;
    }

    record NotificationResponse(UUID id, Notification.Type type, String title, String body, boolean read,
            Instant createdAt, Instant readAt) {

        static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.id(), n.type(), n.title(), n.body(), n.isRead(), n.createdAt(),
                    n.readAt());
        }
    }

    record UnreadCountResponse(long unread) {
    }

    @GetMapping
    @Operation(summary = "Мои уведомления", description = "Новые сверху; unread=true — только непрочитанные.")
    PageResponse<NotificationResponse> list(@RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, AuthenticatedUser user) {
        return notifications.list(user.id(), unread, page, size).map(NotificationResponse::from);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Количество непрочитанных")
    UnreadCountResponse unreadCount(AuthenticatedUser user) {
        return new UnreadCountResponse(notifications.unreadCount(user.id()));
    }

    @PatchMapping("/{id}/read")
    @Operation(summary = "Отметить прочитанным", description = "Только получатель; чужое уведомление — 404. "
            + "Повторная отметка не меняет время первого прочтения.")
    NotificationResponse markRead(@PathVariable UUID id, AuthenticatedUser user) {
        return NotificationResponse.from(notifications.markRead(user.id(), id));
    }
}
