#!/bin/sh
# Идемпотентный bootstrap логических БД FitClub.
#
# Выполняется одноразовым контейнером postgres-bootstrap при каждом `docker compose up`:
#  - создаёт для каждого сервиса отдельную роль <service>_user и БД <service>_db (если их ещё нет);
#  - синхронизирует пароль роли с файлом секрета;
#  - отзывает у PUBLIC право подключения, поэтому роль одного сервиса не может открыть чужую БД.
# Схемы таблиц здесь НЕ создаются: это делает Flyway соответствующего сервиса.
# Пароли читаются из /run/secrets и не попадают ни в аргументы процессов, ни в лог.
set -eu

: "${PGHOST:?PGHOST is required}"
PGUSER="${PGUSER:-postgres}"
PGPASSWORD="$(cat /run/secrets/postgres_admin_password)"
export PGUSER PGPASSWORD

SERVICES="auth schedule membership booking payment notification"

for service in $SERVICES; do
  secret_file="/run/secrets/db_${service}_password"
  if [ ! -s "$secret_file" ]; then
    echo "bootstrap: missing secret for ${service}, run GenerateSecrets first" >&2
    exit 1
  fi

  psql --no-psqlrc --quiet --set ON_ERROR_STOP=1 --dbname postgres \
       --set role="${service}_user" --set db="${service}_db" --set secret_file="$secret_file" <<'SQL'
\set pw `cat :'secret_file'`
SELECT format('CREATE ROLE %I LOGIN', :'role')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
              :'role', :'pw') \gexec
\unset pw
SELECT format('CREATE DATABASE %I OWNER %I ENCODING ''UTF8'' TEMPLATE template0', :'db', :'role')
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db') \gexec
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO :"role";
SQL

  echo "bootstrap: ${service}_db ready (owner ${service}_user)"
done

# Служебная БД postgres тоже закрыта для ролей сервисов
psql --no-psqlrc --quiet --set ON_ERROR_STOP=1 --dbname postgres \
     -c 'REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;' \
     -c 'REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;'

echo "bootstrap: done"
