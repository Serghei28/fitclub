#!/bin/sh
# Сборка Docker-образов FitClub и загрузка их в Minikube (Linux/macOS/Git Bash).
# Запуск из каталога lab3:  sh docker/build-images.sh [--docker-env]
# По умолчанию — сборка в локальном Docker и загрузка в Minikube (minikube image load), работает с любым
# runtime Minikube. --docker-env — сборка прямо в Docker внутри Minikube (только при --container-runtime=docker).
set -eu
cd "$(dirname "$0")/.."
COMPOSE=../lab2/docker-compose.yml
minikube status >/dev/null 2>&1 || { echo "Minikube не запущен: minikube start" >&2; exit 1; }

if [ "${1:-}" = "--docker-env" ]; then
  eval "$(minikube docker-env --shell bash)"
  docker compose -f "$COMPOSE" build
  eval "$(minikube docker-env --shell bash --unset)"
else
  docker compose -f "$COMPOSE" build
  for name in auth-service schedule-service membership-service booking-service payment-service \
              notification-service gateway frontend; do
    echo "minikube image load fitclub/$name:1.0.0"
    minikube image load "fitclub/$name:1.0.0" --overwrite=true
  done
fi
echo
minikube image ls | grep 'fitclub/'
