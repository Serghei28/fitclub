package com.fitclub.payment.infrastructure.provider;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fitclub.payment.application.port.PaymentProvider;
import com.fitclub.payment.domain.Amount;

/**
 * Явно обозначенный СИМУЛЯТОР оплаты: не списывает деньги, не запрашивает данные карты и не обращается
 * к банку. Исход задаёт тестовое demo-действие (SUCCESS/DECLINE). Это не безопасная интеграция с банком.
 * Без состояния: ссылки детерминированы (sim_ + id платежа) и переживают перезапуск сервиса.
 */
@Component
public class SimulatorPaymentProvider implements PaymentProvider {

    static final String NAME = "SIMULATOR";
    static final String PREFIX = "sim_";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Registration register(UUID paymentId, Amount amount) {
        return new Registration(PREFIX + paymentId);
    }

    @Override
    public Result finish(String providerReference, Instruction instruction) {
        if (providerReference == null || !providerReference.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Unknown simulator reference");
        }
        return instruction == Instruction.SUCCESS
                ? new Result(true, null)
                : new Result(false, "SIMULATED_DECLINE");
    }
}
