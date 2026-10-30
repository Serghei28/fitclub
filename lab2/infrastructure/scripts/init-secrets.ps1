# Генерация локальных секретов FitClub (Windows PowerShell).
# Запуск из каталога lab2:  .\infrastructure\scripts\init-secrets.ps1
# Существующие секреты не перезаписываются, значения не выводятся.
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Set-Location $root

$java = Get-Command java -ErrorAction SilentlyContinue
if ($null -ne $java) {
    & java infrastructure/secrets/GenerateSecrets.java secrets
} else {
    Write-Host 'Java не найдена в PATH, используется контейнер eclipse-temurin:21.0.13_9-jdk-alpine'
    docker run --rm -v "${root}:/work" -w /work eclipse-temurin:21.0.13_9-jdk-alpine `
        java infrastructure/secrets/GenerateSecrets.java secrets
}
if ($LASTEXITCODE -ne 0) { throw "Генерация секретов завершилась с кодом $LASTEXITCODE" }
