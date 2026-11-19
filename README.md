# FitClub — лабораторные работы по распределённым системам

| Каталог | Лабораторная | Содержание |
|---|---|---|
| [lab2/](lab2/README.md) | 2. Микросервисная архитектура | Шесть Spring Boot сервисов со своими БД, API Gateway, RabbitMQ (события, outbox, DLQ), UI на Nginx, Docker Compose, 270 тестов + 9 сквозных E2E |
| [lab3/](lab3/README.md) | 3. Docker и Kubernetes | Оптимизированные образы, Kubernetes-манифесты (Deployment, Service, StatefulSet, ConfigMap, Secret, пробы, лимиты), запуск в Minikube, масштабирование и восстановление |

Лабораторная 1 (сбор данных и RAG) в этом репозитории не хранится.

## Быстрый старт

Docker Compose (Лаба 2), из каталога `lab2`:

```powershell
.\infrastructure\scripts\init-secrets.ps1
$env:FITCLUB_PROFILES = "docker,demo"
docker compose up -d --build
```

Kubernetes (Лаба 3), из каталога `lab3`, после `minikube start --driver=docker --cpus=4 --memory=6g`:

```powershell
.\docker\build-images.ps1
.\scripts\deploy.ps1
```

Подробно — в README каждой лабораторной.
