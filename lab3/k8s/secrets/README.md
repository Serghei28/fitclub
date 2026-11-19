# Secret'ы FitClub

Настоящих значений в Git нет. Secret'ы создаёт скрипт из локальных файлов `lab2/secrets` (их генерирует
`lab2/infrastructure/scripts/init-secrets.ps1`, каталог исключён из Git):

```powershell
.\scripts\create-secrets.ps1
```

Скрипт строит манифест в памяти (`kubectl create secret generic … --dry-run=client -o yaml`) и сразу
передаёт его в `kubectl apply`. Значения не печатаются и не записываются в файлы.

| Secret | Ключи (= имена файлов в `/run/secrets` контейнера) | Кому монтируется |
|---|---|---|
| `auth-service-secrets` | `db_auth_password`, `jwt_public_key.pem`, `jwt_private_key.pem`, `auth_initial_admin_password`, `service_token_schedule_to_auth` | auth-service — единственный, у кого есть закрытый ключ JWT |
| `schedule-service-secrets` | `db_schedule_password`, `jwt_public_key.pem`, `rabbitmq_schedule_password`, `service_token_schedule_to_auth` | schedule-service |
| `membership-service-secrets` | `db_membership_password`, `jwt_public_key.pem`, `rabbitmq_membership_password`, `service_token_payment_to_membership`, `service_token_booking_to_membership` | membership-service |
| `booking-service-secrets` | `db_booking_password`, `jwt_public_key.pem`, `rabbitmq_booking_password`, `service_token_booking_to_membership` | booking-service |
| `payment-service-secrets` | `db_payment_password`, `jwt_public_key.pem`, `rabbitmq_payment_password`, `service_token_payment_to_membership` | payment-service |
| `notification-service-secrets` | `db_notification_password`, `jwt_public_key.pem`, `rabbitmq_notification_password` | notification-service |
| `gateway-secrets` | `jwt_public_key.pem` | gateway |
| `postgres-secrets` | `postgres_admin_password`, `db_<service>_password` × 6 | postgres (только пароль администратора), Job postgres-bootstrap |
| `rabbitmq-secrets` | `rabbitmq_users.json` (пользователи с хэшами паролей) | rabbitmq |
| `demo-seed-secrets` | `auth_initial_admin_password`, `demo_users_password` | Job demo-seed |

Почему файлы, а не переменные окружения: значения не видны в `kubectl describe pod`, в `env` процесса и в
дочерних процессах; файлы смонтированы с режимом `0440` и группой процесса (`fsGroup`), остальные
пользователи контейнера их не прочитают. Каждый сервис получает только свои ключи.

Ограничение учебного стенда: Secret в Kubernetes по умолчанию хранится в etcd в base64, а не зашифрованным.
В рабочем кластере включают шифрование etcd (EncryptionConfiguration) или используют внешнее хранилище
(Vault, Sealed Secrets, External Secrets Operator).

Структура манифеста (без значений) — в [secret.example.yaml.txt](secret.example.yaml.txt). Расширение `.txt`
стоит специально: `kubectl apply -R -f k8s/` такой файл не применяет.
