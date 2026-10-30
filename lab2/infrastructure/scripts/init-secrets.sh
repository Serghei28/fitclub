#!/bin/sh
# Генерация локальных секретов FitClub (Linux/macOS/Git Bash).
# Запуск из каталога lab2:  sh infrastructure/scripts/init-secrets.sh
# Существующие секреты не перезаписываются, значения не выводятся.
set -eu
cd "$(dirname "$0")/../.."

if command -v java >/dev/null 2>&1; then
  java infrastructure/secrets/GenerateSecrets.java secrets
else
  echo "Java не найдена в PATH, используется контейнер eclipse-temurin:21.0.13_9-jdk-alpine"
  docker run --rm -u "$(id -u):$(id -g)" -v "$(pwd):/work" -w /work eclipse-temurin:21.0.13_9-jdk-alpine \
    java infrastructure/secrets/GenerateSecrets.java secrets
fi
