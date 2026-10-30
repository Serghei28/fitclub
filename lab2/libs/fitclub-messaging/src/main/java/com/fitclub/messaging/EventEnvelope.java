package com.fitclub.messaging;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/**
 * Конверт любого события FitClub (контракт — docs/events.md). Имена полей зафиксированы явно,
 * чтобы контракт не зависел от настроек сериализации конкретного сервиса.
 */
public record EventEnvelope(
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("producer") String producer,
        @JsonProperty("aggregate_id") UUID aggregateId,
        @JsonProperty("aggregate_version") long aggregateVersion,
        @JsonProperty("correlation_id") String correlationId,
        @JsonProperty("causation_id") String causationId,
        @JsonProperty("payload") JsonNode payload) {
}
