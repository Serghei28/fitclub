package com.fitclub.membership.domain;

import java.util.UUID;

/** Неизменяемый снимок тарифа в заказе: цена, срок и лимит берутся отсюда, а не от клиента. */
public record PlanSnapshot(UUID planId, String planName, int durationDays, Integer visitLimit, Money price) {
}
