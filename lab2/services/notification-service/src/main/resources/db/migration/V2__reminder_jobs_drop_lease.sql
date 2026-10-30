-- Защита от двойной отправки напоминаний реализована через SELECT … FOR UPDATE SKIP LOCKED и смену статуса
-- в той же транзакции (ReminderProcessor). Аренда по времени (locked_until), запланированная в V1, не нужна.
-- Применённая миграция V1 не редактируется: изменение схемы — новой версией.
ALTER TABLE reminder_jobs DROP COLUMN locked_until;
