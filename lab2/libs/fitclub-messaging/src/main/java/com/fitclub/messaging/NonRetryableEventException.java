package com.fitclub.messaging;

/**
 * Событие нельзя обработать никогда: повтор ничего не изменит (неизвестный тип, несовместимая версия схемы,
 * противоречивые данные). Сообщение сразу уходит в DLQ для ручного разбора.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
