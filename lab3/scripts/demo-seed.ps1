# Наполнение кластера demo-данными (Job demo-seed). Запуск из каталога lab3:  .\scripts\demo-seed.ps1
# Повторный запуск безопасен: сидер ничего не дублирует. Пароли он не выводит.
$ErrorActionPreference = 'Continue'
Set-Location (Split-Path $PSScriptRoot -Parent)

kubectl -n fitclub delete job demo-seed --ignore-not-found | Out-Null
kubectl apply -f demo/
kubectl -n fitclub wait --for=condition=ready pod -l app=demo-seed --timeout=120s | Out-Null
kubectl -n fitclub logs -f job/demo-seed
kubectl -n fitclub wait --for=condition=complete job/demo-seed --timeout=300s
