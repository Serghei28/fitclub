package com.fitclub.schedule.application.port;

import com.fitclub.schedule.domain.Session;

/**
 * Порт событий расписания. Вызывается внутри той же транзакции, что и изменение занятия:
 * реализация записывает событие в outbox, а не отправляет его в брокер напрямую.
 */
public interface SessionEventPublisher {

    void sessionPublished(Session session);

    void sessionCancelled(Session session);
}
