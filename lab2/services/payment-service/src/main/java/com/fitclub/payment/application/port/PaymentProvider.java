package com.fitclub.payment.application.port;

import java.util.UUID;

import com.fitclub.payment.domain.Amount;

/**
 * Порт платёжного провайдера (OCP/DIP): сценарии оплаты не знают, кто проводит платёж.
 * Сейчас единственная реализация — SimulatorPaymentProvider. Другой провайдер добавляется новым адаптером
 * без изменения CreatePaymentUseCase / CompletePaymentUseCase.
 *
 * <p>Контракт для всех реализаций (LSP, проверяется PaymentProviderContractTest):
 * <ul>
 *   <li>register возвращает непустую уникальную ссылку провайдера и ничего не списывает;</li>
 *   <li>finish для известной ссылки всегда возвращает Result (approved или declined с причиной);
 *       неизвестная ссылка — IllegalArgumentException; UnsupportedOperationException недопустим;</li>
 *   <li>сбой связи с провайдером выражается ApiException 502/503/504, а не «успехом».</li>
 * </ul>
 */
public interface PaymentProvider {

    /** Имя провайдера для журнала платежей (payments.provider). */
    String name();

    Registration register(UUID paymentId, Amount amount);

    /**
     * Получить итог платежа.
     *
     * @param instruction желаемый исход для тестового провайдера; настоящий провайдер его игнорирует
     *                    и сообщает фактический результат
     */
    Result finish(String providerReference, Instruction instruction);

    record Registration(String providerReference) {
    }

    record Result(boolean approved, String declineReason) {
    }

    enum Instruction {
        SUCCESS, DECLINE
    }
}
