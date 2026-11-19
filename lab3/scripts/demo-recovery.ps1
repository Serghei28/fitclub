# Демонстрация самовосстановления: удаляем Pod — Deployment (через ReplicaSet) создаёт новый.
# Запуск из каталога lab3:  .\scripts\demo-recovery.ps1 [-App booking-service]
param([string]$App = 'booking-service')
$ErrorActionPreference = 'Continue'

$desired = [int](kubectl -n fitclub get deployment $App -o jsonpath='{.spec.replicas}')
$pod = kubectl -n fitclub get pods -l app=$App -o jsonpath='{.items[0].metadata.name}'
Write-Host "== Было (нужно реплик: $desired)" -ForegroundColor Cyan
kubectl -n fitclub get pods -l app=$App

Write-Host "`n== kubectl delete pod $pod" -ForegroundColor Cyan
$started = Get-Date
kubectl -n fitclub delete pod $pod --wait=false
Start-Sleep -Seconds 2
kubectl -n fitclub get pods -l app=$App

Write-Host "`n== Ждём, пока новый Pod станет Ready..." -ForegroundColor Cyan
do {
    Start-Sleep -Seconds 3
    $ready = [int](kubectl -n fitclub get deployment $App -o jsonpath='{.status.readyReplicas}')
    $alive = kubectl -n fitclub get pods -l app=$App -o name
} while ($ready -lt $desired -or ($alive -contains "pod/$pod"))
$seconds = [int]((Get-Date) - $started).TotalSeconds

kubectl -n fitclub get pods -l app=$App
Write-Host "`nВосстановлено за $seconds с: снова $ready из $desired реплик, удалённого Pod $pod больше нет." -ForegroundColor Green
Write-Host 'Пока новый Pod не прошёл readinessProbe, Service направлял запросы только на оставшиеся Pod''ы.'
