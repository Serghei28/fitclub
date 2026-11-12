#!/bin/sh
# Сквозные проверки FitClub на изолированном стенде (Linux/macOS/Git Bash).
# Запуск из каталога lab2:  sh tests/e2e/run-e2e.sh [--keep-running]
#
# Поднимает отдельный Compose-проект fitclub-e2e (свои контейнеры и volumes, порты 18000/18080/15432/...),
# поэтому данные основного стенда (проект fitclub) не меняются. Профиль demo нужен для тестовой оплаты.
set -eu
cd "$(dirname "$0")/../.."

export FITCLUB_PROFILES=docker,demo
export GATEWAY_HOST_PORT=18000 UI_HOST_PORT=18080 POSTGRES_HOST_PORT=15432
export RABBITMQ_HOST_PORT=15673 RABBITMQ_MANAGEMENT_HOST_PORT=15674
PROJECT=fitclub-e2e

echo "Поднимаю изолированный стенд $PROJECT ..."
docker compose -p "$PROJECT" up -d --build --wait --wait-timeout 300

status=0
./mvnw -B -Pe2e -pl tests/e2e verify -De2e.baseUrl=http://localhost:18000 -De2e.composeProject="$PROJECT" || status=$?

if [ "${1:-}" != "--keep-running" ]; then
  docker compose -p "$PROJECT" stop
fi
exit "$status"
