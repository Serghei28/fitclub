package com.fitclub.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fitclub.payment.application.port.PaymentProvider;
import com.fitclub.payment.domain.Amount;
import com.fitclub.payment.infrastructure.provider.SimulatorPaymentProvider;

/**
 * Контрактная проверка LSP: любая реализация PaymentProvider ведёт себя одинаково с точки зрения сценария оплаты.
 * Проверяются симулятор и вторая, независимо написанная реализация (имитация внешнего шлюза с хранением ссылок).
 */
abstract class PaymentProviderContractTest {

    abstract PaymentProvider provider();

    @Test
    void registrationGivesUniqueNonBlankReference() {
        PaymentProvider provider = provider();
        String a = provider.register(UUID.randomUUID(), new Amount(100, "MDL")).providerReference();
        String b = provider.register(UUID.randomUUID(), new Amount(100, "MDL")).providerReference();
        assertThat(a).isNotBlank();
        assertThat(a).isNotEqualTo(b);
        assertThat(provider.name()).isNotBlank();
    }

    @Test
    void finishAlwaysReturnsApprovedOrDeclinedWithReason() {
        PaymentProvider provider = provider();
        String ok = provider.register(UUID.randomUUID(), new Amount(100, "MDL")).providerReference();
        String ko = provider.register(UUID.randomUUID(), new Amount(100, "MDL")).providerReference();

        assertThat(provider.finish(ok, PaymentProvider.Instruction.SUCCESS).approved()).isTrue();
        PaymentProvider.Result declined = provider.finish(ko, PaymentProvider.Instruction.DECLINE);
        assertThat(declined.approved()).isFalse();
        assertThat(declined.declineReason()).isNotBlank();
    }

    @Test
    void unknownReferenceIsIllegalArgumentNotUnsupportedOperation() {
        assertThatThrownBy(() -> provider().finish("unknown-ref", PaymentProvider.Instruction.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Вторая реализация порта: подставляется в сценарий без изменения его кода (OCP). */
    static final class InMemoryGatewayProvider implements PaymentProvider {
        private final Map<String, Amount> registered = new HashMap<>();

        @Override
        public String name() {
            return "TEST_GATEWAY";
        }

        @Override
        public Registration register(UUID paymentId, Amount amount) {
            String reference = "gw-" + UUID.randomUUID();
            registered.put(reference, amount);
            return new Registration(reference);
        }

        @Override
        public Result finish(String providerReference, Instruction instruction) {
            if (!registered.containsKey(providerReference)) {
                throw new IllegalArgumentException("Unknown reference");
            }
            return instruction == Instruction.SUCCESS ? new Result(true, null) : new Result(false, "CARD_DECLINED");
        }
    }
}

/** Контракт для симулятора. */
class SimulatorProviderContractTest extends PaymentProviderContractTest {
    @Override
    PaymentProvider provider() {
        return new SimulatorPaymentProvider();
    }
}

/** Контракт для второй реализации: та же семантика без изменений сценария (OCP + LSP). */
class InMemoryGatewayProviderContractTest extends PaymentProviderContractTest {
    @Override
    PaymentProvider provider() {
        return new InMemoryGatewayProvider();
    }
}
