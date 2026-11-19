# Демонстрация горизонтального масштабирования booking-service.
# Запуск из каталога lab3:  .\scripts\demo-scaling.ps1 [-Replicas 3] [-Requests 30]
#
# 1. kubectl scale меняет число реплик в Deployment;
# 2. Service booking-service получает адреса всех готовых Pod'ов (EndpointSlice);
# 3. изнутри кластера отправляются запросы к Service, каждый — новым TCP-соединением;
# 4. по логам видно, какой Pod обработал какой запрос (у каждой строки — префикс с именем Pod).
param([int]$Replicas = 3, [int]$Requests = 30)
$ErrorActionPreference = 'Continue'

Write-Host "== kubectl scale deployment booking-service --replicas=$Replicas" -ForegroundColor Cyan
kubectl -n fitclub scale deployment booking-service --replicas=$Replicas
kubectl -n fitclub rollout status deployment/booking-service --timeout=300s
kubectl -n fitclub get pods -l app=booking-service -o wide

Write-Host "`n== Адреса Pod'ов за Service booking-service" -ForegroundColor Cyan
kubectl -n fitclub get endpointslices -l kubernetes.io/service-name=booking-service

Write-Host "`n== $Requests запросов к http://booking-service:8080 изнутри кластера" -ForegroundColor Cyan
# Клиент — временный Pod из образа postgres:17.11-alpine: он уже есть в кластере, и в нём есть wget.
# Запрос без токена получает 401, но обрабатывается сервисом и попадает в его лог.
$loop = 'for i in $(seq 1 {0}); do wget -q -O /dev/null http://booking-service:8080/api/bookings/me 2>/dev/null; done; echo sent {0}' -f $Requests
# 2>$null: kubectl run печатает в stderr служебное предупреждение о записи сессии в лог контейнера
kubectl -n fitclub run lb-test --rm -i --restart=Never --image=postgres:17.11-alpine `
    --image-pull-policy=IfNotPresent --command -- sh -c $loop 2>$null
Start-Sleep -Seconds 2

Write-Host "`n== Сколько запросов обработал каждый Pod" -ForegroundColor Cyan
kubectl -n fitclub logs -l app=booking-service -c booking-service --prefix --since=3m --tail=-1 --max-log-requests=10 |
    Select-String 'GET /api/bookings/me' |
    ForEach-Object { ($_.Line -split ' ')[0] } |
    Group-Object | Sort-Object Name | Format-Table @{ n = 'Запросов'; e = { $_.Count } }, @{ n = 'Pod'; e = { $_.Name } } -AutoSize

Write-Host "Вернуть 2 реплики:  kubectl -n fitclub scale deployment booking-service --replicas=2"
