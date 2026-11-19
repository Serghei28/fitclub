# Сборка Docker-образов FitClub и загрузка их в Minikube.
# Запуск из каталога lab3 (Minikube должен быть запущен):  .\docker\build-images.ps1
#
# По умолчанию: образы собираются в Docker Desktop (docker compose build, как для Лабы 2) и
# копируются в хранилище образов узла Minikube (minikube image load). Kubernetes берёт их оттуда
# (imagePullPolicy: IfNotPresent), реестр не нужен. Работает с любым runtime Minikube
# (по умолчанию в Minikube 1.39 — containerd).
#
# -DockerEnv: собрать прямо в Docker-демоне внутри Minikube (minikube docker-env). Только если кластер
#             создан с --container-runtime=docker: с containerd команда docker-env не поддерживается.
param([switch]$DockerEnv)
$ErrorActionPreference = 'Continue'
$lab2 = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'lab2'
$compose = Join-Path $lab2 'docker-compose.yml'
$images = 'auth-service', 'schedule-service', 'membership-service', 'booking-service', 'payment-service',
          'notification-service', 'gateway', 'frontend' | ForEach-Object { "fitclub/${_}:1.0.0" }

minikube status *> $null
if ($LASTEXITCODE -ne 0) { throw 'Minikube не запущен: выполните minikube start (см. lab3/README.md)' }

if ($DockerEnv) {
    # Переменные DOCKER_HOST и др. указывают docker на демон внутри Minikube — только на время сборки
    minikube docker-env --shell powershell | Invoke-Expression
    try {
        docker compose -f $compose build
        if ($LASTEXITCODE -ne 0) { throw 'Сборка образов не удалась' }
    } finally {
        minikube docker-env --shell powershell --unset | Invoke-Expression
    }
} else {
    docker compose -f $compose build
    if ($LASTEXITCODE -ne 0) { throw 'Сборка образов не удалась' }
    foreach ($image in $images) {
        $started = Get-Date
        # --overwrite: заменить образ с тем же тегом, если он уже есть в Minikube (после пересборки)
        minikube image load $image --overwrite=true
        if ($LASTEXITCODE -ne 0) { throw "Не удалось загрузить $image" }
        Write-Host ("{0} загружен за {1:n0} с" -f $image, ((Get-Date) - $started).TotalSeconds)
    }
}

Write-Host "`nОбразы FitClub в Minikube:"
minikube image ls | Select-String 'fitclub/'
