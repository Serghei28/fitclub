# Сквозные проверки API на кластере: те же E2E-тесты, что в lab2 (tests/e2e), через Gateway в Kubernetes.
# Запуск из каталога lab3:  .\scripts\test-k8s.ps1
#
# Gateway пробрасывается на localhost:9000 (kubectl port-forward). Выполняются:
#   FullJourneyIT    — абонемент → оплата → активация по событию → запись → уведомления → отмена;
#   AccessControlIT  — 401 без токена, /internal закрыт, роли и владение ресурсами;
#   ConcurrencyIT    — параллельная запись на последнее место и последнее посещение: вместимость
#                      и лимит не превышаются (блокировки строк в БД booking-service).
#                      Запросы идут через пул keep-alive соединений Gateway, поэтому чаще всего
#                      попадают в один Pod booking-service (Service балансирует соединения, а не запросы).
# FailureScenariosIT не запускается: он останавливает контейнеры через docker compose
# (в Kubernetes отказоустойчивость показывает demo-recovery.ps1).
$ErrorActionPreference = 'Continue'
$lab2 = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'lab2'

$forward = Start-Process kubectl -ArgumentList 'port-forward', '-n', 'fitclub', 'svc/gateway', '9000:8080' `
    -PassThru -WindowStyle Hidden
try {
    $deadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Seconds 1
        try { $ok = (Invoke-WebRequest http://localhost:9000/health -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200 }
        catch { $ok = $false }
    } until ($ok -or (Get-Date) -gt $deadline)
    if (-not $ok) { throw 'Gateway недоступен через port-forward на localhost:9000' }

    Push-Location $lab2
    .\mvnw.cmd -B -Pe2e -pl tests/e2e verify "-De2e.baseUrl=http://localhost:9000" `
        "-Dit.test=FullJourneyIT,AccessControlIT,ConcurrencyIT"
    $code = $LASTEXITCODE
} finally {
    Pop-Location
    Stop-Process -Id $forward.Id -ErrorAction SilentlyContinue
}
exit $code
