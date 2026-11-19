# Создание (или обновление) Kubernetes Secret из локальных файлов lab2/secrets.
# Запуск из каталога lab3:  .\scripts\create-secrets.ps1
#
# Значения нигде не печатаются и не сохраняются на диск: манифест Secret строится в памяти
# (kubectl create --dry-run=client -o yaml) и сразу передаётся в kubectl apply.
# Каждый сервис получает отдельный Secret только с нужными ему ключами (принцип минимальных прав).
# Файлы в lab2/secrets создаёт генератор: lab2\infrastructure\scripts\init-secrets.ps1
param(
    [string]$SecretsDir = (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'lab2\secrets'),
    [string]$Namespace = 'fitclub'
)
$ErrorActionPreference = 'Continue'

$secrets = [ordered]@{
    'auth-service-secrets'         = @('db_auth_password', 'jwt_public_key.pem', 'jwt_private_key.pem',
                                       'auth_initial_admin_password', 'service_token_schedule_to_auth')
    'schedule-service-secrets'     = @('db_schedule_password', 'jwt_public_key.pem', 'rabbitmq_schedule_password',
                                       'service_token_schedule_to_auth')
    'membership-service-secrets'   = @('db_membership_password', 'jwt_public_key.pem', 'rabbitmq_membership_password',
                                       'service_token_payment_to_membership', 'service_token_booking_to_membership')
    'booking-service-secrets'      = @('db_booking_password', 'jwt_public_key.pem', 'rabbitmq_booking_password',
                                       'service_token_booking_to_membership')
    'payment-service-secrets'      = @('db_payment_password', 'jwt_public_key.pem', 'rabbitmq_payment_password',
                                       'service_token_payment_to_membership')
    'notification-service-secrets' = @('db_notification_password', 'jwt_public_key.pem', 'rabbitmq_notification_password')
    'gateway-secrets'              = @('jwt_public_key.pem')
    'postgres-secrets'             = @('postgres_admin_password', 'db_auth_password', 'db_schedule_password',
                                       'db_membership_password', 'db_booking_password', 'db_payment_password',
                                       'db_notification_password')
    'rabbitmq-secrets'             = @('rabbitmq_users.json')
    'demo-seed-secrets'            = @('auth_initial_admin_password', 'demo_users_password')
}

if (-not (Test-Path $SecretsDir)) {
    throw "Нет каталога $SecretsDir. Сначала выполните lab2\infrastructure\scripts\init-secrets.ps1"
}

foreach ($name in $secrets.Keys) {
    $fromFile = @()
    foreach ($key in $secrets[$name]) {
        $path = Join-Path $SecretsDir $key
        if (-not (Test-Path $path)) {
            throw "Нет файла секрета $key. Запустите lab2\infrastructure\scripts\init-secrets.ps1 (он добавит недостающие)"
        }
        $fromFile += "--from-file=$key=$path"
    }
    $manifest = kubectl create secret generic $name -n $Namespace @fromFile --dry-run=client -o yaml
    if ($LASTEXITCODE -ne 0) { throw "Не удалось подготовить Secret $name" }
    $manifest | kubectl apply -f - | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Не удалось применить Secret $name" }
}
Write-Host "Secret'ы созданы в namespace $Namespace. Значения не выводились."
