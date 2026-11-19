#!/bin/sh
# Генерация ConfigMap из файлов инфраструктуры lab2 (Linux/macOS/Git Bash).
# Запуск из каталога lab3:  sh scripts/sync-configmaps.sh
# Нужен только kubectl: манифесты строятся локально (--dry-run=client), кластер не требуется.
set -eu
cd "$(dirname "$0")/.."
INFRA=../lab2/infrastructure
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

configmap() {
  name="$1"; target="$2"; comment="$3"; shift 3
  args=""
  for file in "$@"; do
    tr -d '\r' < "$file" > "$TMP/$(basename "$file")"   # CRLF -> LF
    args="$args --from-file=$TMP/$(basename "$file")"
  done
  {
    echo "# $comment"
    echo "# Сгенерировано lab3/scripts/sync-configmaps из lab2/infrastructure — не редактируйте вручную."
    # shellcheck disable=SC2086
    kubectl create configmap "$name" -n fitclub $args --dry-run=client -o yaml \
      | kubectl label --local -f - app.kubernetes.io/part-of=fitclub -o yaml
  } > "$target"
  echo "$target"
}

configmap postgres-bootstrap k8s/configmaps/postgres-bootstrap.yaml \
  "Скрипт создания ролей и БД сервисов (Job postgres-bootstrap)." \
  "$INFRA/postgres/bootstrap-databases.sh"
configmap rabbitmq-config k8s/configmaps/rabbitmq-config.yaml \
  "Конфигурация RabbitMQ и топология: exchange, очереди сервисов, DLQ." \
  "$INFRA/rabbitmq/rabbitmq.conf" "$INFRA/rabbitmq/topology.json"
configmap demo-seed-script demo/demo-seed-script.yaml \
  "Скрипт demo-наполнения через публичный API (Job demo-seed)." \
  "$INFRA/demo/seed.py"
