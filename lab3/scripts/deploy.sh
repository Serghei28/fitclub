#!/bin/sh
# Развёртывание FitClub в Kubernetes (Minikube), Linux/macOS/Git Bash.
# Запуск из каталога lab3:  sh scripts/deploy.sh
set -eu
cd "$(dirname "$0")/.."

[ "$(kubectl config current-context)" = "minikube" ] || echo "Внимание: контекст kubectl не minikube" >&2

kubectl apply -f k8s/00-namespace.yaml
sh scripts/create-secrets.sh
kubectl apply -R -f k8s/

echo "Жду инфраструктуру и bootstrap БД..."
kubectl -n fitclub rollout status statefulset/postgres --timeout=300s
kubectl -n fitclub rollout status statefulset/rabbitmq --timeout=300s
kubectl -n fitclub wait --for=condition=complete job/postgres-bootstrap --timeout=300s

echo "Жду сервисы (первый запуск всех JVM — 2–4 минуты)..."
for d in auth-service schedule-service membership-service booking-service payment-service \
         notification-service gateway frontend; do
  kubectl -n fitclub rollout status "deployment/$d" --timeout=600s
done
kubectl -n fitclub get pods -o wide
echo "Интерфейс: minikube service frontend -n fitclub  (или kubectl port-forward -n fitclub svc/frontend 9080:80)"
