package com.fitclub.messaging;

/**
 * Обработчик одного типа события в сервисе-потребителе. Вызывается внутри локальной транзакции,
 * в которой уже записан processed_events: изменения обработчика и отметка «обработано» фиксируются вместе.
 */
@FunctionalInterface
public interface EventHandler {

    void handle(EventEnvelope envelope);
}
