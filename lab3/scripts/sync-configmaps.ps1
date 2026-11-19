# Генерация ConfigMap из файлов инфраструктуры lab2 (один источник правды для Compose и Kubernetes).
# Запуск из каталога lab3 после изменения файлов в lab2/infrastructure:  .\scripts\sync-configmaps.ps1
#
#   lab2/infrastructure/postgres/bootstrap-databases.sh  -> k8s/configmaps/postgres-bootstrap.yaml
#   lab2/infrastructure/rabbitmq/rabbitmq.conf, topology.json -> k8s/configmaps/rabbitmq-config.yaml
#   lab2/infrastructure/demo/seed.py                     -> demo/demo-seed-script.yaml
# Нужен только kubectl: манифесты строятся локально (--dry-run=client), кластер не требуется.
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$lab3 = Split-Path $PSScriptRoot -Parent
$infra = Join-Path (Split-Path $lab3 -Parent) 'lab2\infrastructure'
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('fitclub-cm-' + [guid]::NewGuid())
New-Item -ItemType Directory $tmp | Out-Null

function Write-ConfigMap([string]$Name, [string[]]$Files, [string]$Target, [string]$Comment) {
    $args = @('create', 'configmap', $Name, '-n', 'fitclub', '--dry-run=client', '-o', 'yaml')
    foreach ($file in $Files) {
        # Перевод строк CRLF -> LF: при checkout на Windows Git может добавить CR, а скрипты выполняются в Linux
        $copy = Join-Path $tmp (Split-Path $file -Leaf)
        $text = [IO.File]::ReadAllText($file).Replace("`r`n", "`n")
        [IO.File]::WriteAllText($copy, $text, [Text.UTF8Encoding]::new($false))
        $args += "--from-file=$copy"
    }
    $yaml = (kubectl @args | kubectl label --local -f - app.kubernetes.io/part-of=fitclub -o yaml) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Не удалось сгенерировать ConfigMap $Name" }
    $header = "# $Comment`n# Сгенерировано lab3/scripts/sync-configmaps из lab2/infrastructure — не редактируйте вручную.`n"
    [IO.File]::WriteAllText((Join-Path $lab3 $Target), $header + $yaml + "`n", [Text.UTF8Encoding]::new($false))
    Write-Host "$Target <- $($Files -join ', ')"
}

try {
    Write-ConfigMap 'postgres-bootstrap' @("$infra\postgres\bootstrap-databases.sh") `
        'k8s\configmaps\postgres-bootstrap.yaml' 'Скрипт создания ролей и БД сервисов (Job postgres-bootstrap).'
    Write-ConfigMap 'rabbitmq-config' @("$infra\rabbitmq\rabbitmq.conf", "$infra\rabbitmq\topology.json") `
        'k8s\configmaps\rabbitmq-config.yaml' 'Конфигурация RabbitMQ и топология: exchange, очереди сервисов, DLQ.'
    Write-ConfigMap 'demo-seed-script' @("$infra\demo\seed.py") `
        'demo\demo-seed-script.yaml' 'Скрипт demo-наполнения через публичный API (Job demo-seed).'
} finally {
    Remove-Item -Recurse -Force $tmp
}
