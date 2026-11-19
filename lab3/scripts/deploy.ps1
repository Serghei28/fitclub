# Развёртывание FitClub в Kubernetes (Minikube).
# Запуск из каталога lab3 (после minikube start и docker\build-images.ps1):  .\scripts\deploy.ps1
#
# 1. namespace fitclub;  2. Secret из lab2/secrets;  3. все манифесты k8s/ (ConfigMap, StatefulSet,
# Job, Deployment, Service);  4. ожидание готовности.
$ErrorActionPreference = 'Continue'
$lab3 = Split-Path $PSScriptRoot -Parent
Set-Location $lab3

$context = kubectl config current-context
if ($context -ne 'minikube') {
    Write-Warning "Текущий контекст kubectl: '$context', а не minikube. Переключить: kubectl config use-context minikube"
}

kubectl apply -f k8s/00-namespace.yaml
& "$PSScriptRoot\create-secrets.ps1"
kubectl apply -R -f k8s/
if ($LASTEXITCODE -ne 0) { throw 'kubectl apply завершился с ошибкой' }

Write-Host "`nЖду инфраструктуру и bootstrap БД..."
kubectl -n fitclub rollout status statefulset/postgres --timeout=300s
kubectl -n fitclub rollout status statefulset/rabbitmq --timeout=300s
kubectl -n fitclub wait --for=condition=complete job/postgres-bootstrap --timeout=300s

Write-Host "`nЖду сервисы (первый запуск всех JVM — 2–4 минуты)..."
foreach ($d in 'auth-service', 'schedule-service', 'membership-service', 'booking-service', 'payment-service',
                'notification-service', 'gateway', 'frontend') {
    kubectl -n fitclub rollout status deployment/$d --timeout=600s
}

kubectl -n fitclub get pods -o wide
Write-Host "`nИнтерфейс: minikube service frontend -n fitclub"
Write-Host "или:       kubectl port-forward -n fitclub svc/frontend 9080:80  ->  http://localhost:9080"
