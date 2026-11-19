#!/bin/sh
# Создание (или обновление) Kubernetes Secret из локальных файлов lab2/secrets (Linux/macOS/Git Bash).
# Запуск из каталога lab3:  sh scripts/create-secrets.sh
# Значения не печатаются и не сохраняются на диск: манифест строится в памяти и сразу уходит в kubectl apply.
set -eu
cd "$(dirname "$0")/.."
SECRETS_DIR="${SECRETS_DIR:-../lab2/secrets}"
NAMESPACE="${NAMESPACE:-fitclub}"

[ -d "$SECRETS_DIR" ] || { echo "Нет каталога $SECRETS_DIR: выполните lab2/infrastructure/scripts/init-secrets.sh" >&2; exit 1; }

create() {
  name="$1"; shift
  args=""
  for key in "$@"; do
    [ -f "$SECRETS_DIR/$key" ] || { echo "Нет файла секрета $key: запустите генератор секретов lab2" >&2; exit 1; }
    args="$args --from-file=$key=$SECRETS_DIR/$key"
  done
  # shellcheck disable=SC2086
  kubectl create secret generic "$name" -n "$NAMESPACE" $args --dry-run=client -o yaml | kubectl apply -f -
}

create auth-service-secrets db_auth_password jwt_public_key.pem jwt_private_key.pem \
  auth_initial_admin_password service_token_schedule_to_auth
create schedule-service-secrets db_schedule_password jwt_public_key.pem rabbitmq_schedule_password \
  service_token_schedule_to_auth
create membership-service-secrets db_membership_password jwt_public_key.pem rabbitmq_membership_password \
  service_token_payment_to_membership service_token_booking_to_membership
create booking-service-secrets db_booking_password jwt_public_key.pem rabbitmq_booking_password \
  service_token_booking_to_membership
create payment-service-secrets db_payment_password jwt_public_key.pem rabbitmq_payment_password \
  service_token_payment_to_membership
create notification-service-secrets db_notification_password jwt_public_key.pem rabbitmq_notification_password
create gateway-secrets jwt_public_key.pem
create postgres-secrets postgres_admin_password db_auth_password db_schedule_password db_membership_password \
  db_booking_password db_payment_password db_notification_password
create rabbitmq-secrets rabbitmq_users.json
create demo-seed-secrets auth_initial_admin_password demo_users_password
echo "Secret'ы созданы в namespace $NAMESPACE. Значения не выводились."
