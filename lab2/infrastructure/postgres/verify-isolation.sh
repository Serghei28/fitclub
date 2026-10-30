#!/bin/sh
# Проверка логической изоляции БД: роль сервиса подключается к своей БД и НЕ подключается к чужим.
# Запуск: docker compose run --rm --entrypoint sh postgres-bootstrap /bootstrap/verify-isolation.sh
set -u

: "${PGHOST:?PGHOST is required}"
SERVICES="auth schedule membership booking payment notification"
failures=0

for owner in $SERVICES; do
  PGPASSWORD="$(cat "/run/secrets/db_${owner}_password")"
  export PGPASSWORD
  for target in $SERVICES postgres; do
    db="${target}_db"
    [ "$target" = "postgres" ] && db="postgres"
    if psql --no-psqlrc -tA -U "${owner}_user" -d "$db" -c 'select 1' >/dev/null 2>&1; then
      result="allowed"
    else
      result="denied"
    fi
    expected="denied"
    [ "$target" = "$owner" ] && expected="allowed"
    if [ "$result" = "$expected" ]; then
      echo "OK    ${owner}_user -> ${db}: ${result}"
    else
      echo "FAIL  ${owner}_user -> ${db}: ${result}, expected ${expected}"
      failures=$((failures + 1))
    fi
  done
done

unset PGPASSWORD
if [ "$failures" -ne 0 ]; then
  echo "isolation check failed: ${failures} problem(s)" >&2
  exit 1
fi
echo "isolation check passed"
