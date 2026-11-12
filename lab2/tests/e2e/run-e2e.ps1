# Сквозные проверки FitClub на изолированном стенде (Windows PowerShell).
# Запуск из каталога lab2:  .\tests\e2e\run-e2e.ps1
#
# Поднимает отдельный Compose-проект fitclub-e2e (свои контейнеры и volumes, порты 18000/18080/15432/...),
# поэтому данные основного стенда (проект fitclub) не меняются. Профиль demo нужен для тестовой оплаты.
# После тестов стенд останавливается без удаления данных; -KeepRunning оставляет его запущенным.
param([switch]$KeepRunning)

# Не 'Stop': docker пишет прогресс в stderr, а Windows PowerShell 5.1 считал бы это ошибкой.
# Успех внешних команд проверяется по $LASTEXITCODE.
$ErrorActionPreference = 'Continue'
Set-Location (Join-Path $PSScriptRoot '..\..')

$env:FITCLUB_PROFILES = 'docker,demo'
$env:GATEWAY_HOST_PORT = '18000'
$env:UI_HOST_PORT = '18080'
$env:POSTGRES_HOST_PORT = '15432'
$env:RABBITMQ_HOST_PORT = '15673'
$env:RABBITMQ_MANAGEMENT_HOST_PORT = '15674'
$project = 'fitclub-e2e'

Write-Host "Поднимаю изолированный стенд $project ..."
docker compose -p $project up -d --build --wait --wait-timeout 300
if ($LASTEXITCODE -ne 0) { throw "Стенд не поднялся: docker compose -p $project ps" }

try {
    .\mvnw.cmd -B -Pe2e -pl tests/e2e verify "-De2e.baseUrl=http://localhost:18000" "-De2e.composeProject=$project"
    $testExit = $LASTEXITCODE
} finally {
    if (-not $KeepRunning) {
        docker compose -p $project stop
    }
}
exit $testExit
