package com.fitclub.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import com.fitclub.common.error.ApiException;
import com.fitclub.common.error.ErrorCodes;

/**
 * Idempotency-Key для POST-операций, создающих ресурс (таблица idempotency_records в БД сервиса).
 * <ul>
 *   <li>ключ действует в области (владелец, операция): разные пользователи не мешают друг другу;</li>
 *   <li>хранится SHA-256 нормализованного тела (поля по алфавиту): тот же ключ с другим телом → 409;</li>
 *   <li>повтор того же ключа и тела возвращает сохранённый ответ (тот же id ресурса), действие не выполняется;</li>
 *   <li>одновременные запросы с одним ключом упорядочиваются pg_advisory_xact_lock — второй дождётся первого
 *       и получит его результат;</li>
 *   <li>если действие завершилось ошибкой, транзакция откатывается и ключ не сохраняется: повтор выполнит
 *       действие заново.</li>
 * </ul>
 * Вызывается внутри бизнес-транзакции; сетевые вызовы делаются до неё.
 */
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final JsonMapper canonicalMapper;
    private final Clock clock;

    public IdempotencyStore(JdbcTemplate jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.canonicalMapper = jsonMapper.rebuild()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        this.clock = clock;
    }

    /** Результат: тело ответа, HTTP-статус и признак повтора (для заголовка Idempotent-Replayed). */
    public record Outcome<T>(T body, int status, boolean replayed) {
    }

    /** Результат действия для сохранения: тело ответа, его статус и id созданного ресурса. */
    public record Fresh<T>(T body, HttpStatus status, UUID resourceId) {
    }

    public <T> Outcome<T> execute(UUID ownerId, String operation, UUID key, Object request, Class<T> responseType,
            Supplier<Fresh<T>> action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Idempotent operation must run inside a transaction");
        }
        String requestHash = hash(request);
        // pg_advisory_xact_lock возвращает void, поэтому оборачиваем в SELECT 1; блокировка снимается при commit/rollback
        jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) l", Integer.class,
                "idempotency:" + ownerId + ":" + operation + ":" + key);

        List<StoredRecord> existing = jdbc.query("""
                select request_hash, response_status, response_body::text as response_body
                from idempotency_records where owner_id = ? and operation = ? and idempotency_key = ?
                """, (rs, i) -> new StoredRecord(rs.getString("request_hash").trim(), rs.getInt("response_status"),
                rs.getString("response_body")), ownerId, operation, key);
        if (!existing.isEmpty()) {
            StoredRecord stored = existing.getFirst();
            if (!stored.requestHash().equals(requestHash)) {
                log.info("Idempotency conflict: operation={}", operation);
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_CONFLICT, "Конфликт ключа",
                        "Этот Idempotency-Key уже использован с другим запросом. Для нового действия создайте новый ключ.");
            }
            log.info("Idempotent replay: operation={}", operation);
            return new Outcome<>(jsonMapper.readValue(stored.responseBody(), responseType), stored.status(), true);
        }

        Fresh<T> fresh = action.get();
        jdbc.update("""
                insert into idempotency_records (owner_id, operation, idempotency_key, request_hash, resource_id,
                    response_status, response_body, created_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, ownerId, operation, key, requestHash, fresh.resourceId(), fresh.status().value(),
                jsonMapper.writeValueAsString(fresh.body()), Timestamp.from(clock.instant()));
        return new Outcome<>(fresh.body(), fresh.status().value(), false);
    }

    private String hash(Object request) {
        try {
            byte[] canonical = canonicalMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record StoredRecord(String requestHash, int status, String responseBody) {
    }
}
