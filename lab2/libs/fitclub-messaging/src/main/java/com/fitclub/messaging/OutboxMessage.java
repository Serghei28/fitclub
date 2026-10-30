package com.fitclub.messaging;

import java.util.UUID;

/**
 * Новый факт для записи в outbox. Сервис-издатель заполняет бизнес-часть: тип события, routing key,
 * агрегат с версией после изменения и payload (record, сериализуется в JSON).
 */
public record OutboxMessage(String eventType, int schemaVersion, String routingKey, String aggregateType,
        UUID aggregateId, long aggregateVersion, Object payload) {
}
