package com.fitclub.schedule.application.port;

import java.util.function.Supplier;

/**
 * Явная граница локальной транзакции. Use case сначала выполняет сетевой вызов (проверку тренера),
 * а затем короткую транзакцию: блокировки БД не удерживаются во время ожидания другого сервиса.
 */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);

    <T> T readOnly(Supplier<T> work);
}
