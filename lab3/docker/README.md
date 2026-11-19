# Docker-образы FitClub

Восемь образов: шесть бизнес-сервисов, API Gateway и интерфейс (Nginx). Dockerfile лежит рядом с кодом каждого
сервиса в `lab2`. Образ собирается из тех же исходников, которые проверяют тесты:

| Образ | Dockerfile | Свой `.dockerignore` |
|---|---|---|
| `fitclub/auth-service:1.0.0` | [lab2/services/auth-service/Dockerfile](../../lab2/services/auth-service/Dockerfile) | [Dockerfile.dockerignore](../../lab2/services/auth-service/Dockerfile.dockerignore) |
| `fitclub/schedule-service:1.0.0` | [lab2/services/schedule-service/Dockerfile](../../lab2/services/schedule-service/Dockerfile) | рядом с Dockerfile |
| `fitclub/membership-service:1.0.0` | [lab2/services/membership-service/Dockerfile](../../lab2/services/membership-service/Dockerfile) | рядом с Dockerfile |
| `fitclub/booking-service:1.0.0` | [lab2/services/booking-service/Dockerfile](../../lab2/services/booking-service/Dockerfile) | рядом с Dockerfile |
| `fitclub/payment-service:1.0.0` | [lab2/services/payment-service/Dockerfile](../../lab2/services/payment-service/Dockerfile) | рядом с Dockerfile |
| `fitclub/notification-service:1.0.0` | [lab2/services/notification-service/Dockerfile](../../lab2/services/notification-service/Dockerfile) | рядом с Dockerfile |
| `fitclub/gateway:1.0.0` | [lab2/gateway/Dockerfile](../../lab2/gateway/Dockerfile) | рядом с Dockerfile |
| `fitclub/frontend:1.0.0` | [lab2/frontend/Dockerfile](../../lab2/frontend/Dockerfile) | рядом с Dockerfile |

Инфраструктура использует официальные образы с точными версиями: `postgres:17.11-alpine`,
`rabbitmq:4.1.8-management-alpine`, `python:3.13.18-alpine` (Job demo-seed). Тегов `latest` нет нигде.

## Как выполнены требования к Dockerfile

| Требование | Как сделано |
|---|---|
| Небольшой размер | Многостадийная сборка: JDK, Maven и исходники остаются в стадии `build`, в итоговый образ попадают только JRE и приложение. База — `eclipse-temurin:21.0.13_9-jre-alpine`, у Nginx — `nginx:1.30.6-alpine` |
| `.dockerignore` | Корневой `lab2/.dockerignore` и для каждого образа свой `Dockerfile.dockerignore` (BuildKit берёт его вместо корневого). Принцип «запрещено всё, кроме нужного»: в контекст попадают POM-файлы, исходники общих модулей и самого сервиса |
| Кэширование слоёв | 1) Локальный репозиторий Maven — в кэше BuildKit (`RUN --mount=type=cache`): зависимости скачиваются один раз, в слои образа не попадают. 2) Слои Spring Boot (`java -Djarmode=tools … extract --layers`): библиотеки (~65–72 МБ) — отдельным слоем, код и общие модули проекта — последним слоем (45–210 КБ). При правке кода пересобирается и передаётся только он. 3) Свой `.dockerignore`: правка другого сервиса не меняет контекст и не сбрасывает кэш этого образа |
| Нет секретов в образе | Пароли, ключи JWT и токены монтируются при запуске в `/run/secrets` (Compose secrets / Kubernetes Secret). `secrets/` и `.env` исключены из контекста. Адреса и настройки — переменные окружения (ConfigMap) |
| Не от root | Java: пользователь `fitclub` с числовым UID 10001 (`USER 10001:10001`). Числовой UID нужен Kubernetes для проверки `runAsNonRoot`. Nginx: пользователь `nginx` (UID 101), порт 8080, pid-файл в `/tmp`. PostgreSQL и RabbitMQ в Kubernetes тоже запускаются сразу от своих пользователей (UID 70 и 100) |
| Корректный ENTRYPOINT/CMD | Exec-форма `ENTRYPOINT ["java", "-jar", "/app/<service>.jar"]`: java — PID 1 и получает SIGTERM, Spring Boot завершается корректно (graceful shutdown). У Nginx — entrypoint и CMD базового образа (подстановка шаблона конфигурации, затем `nginx -g 'daemon off;'`) |
| Конфигурация вне образа | Адрес БД, брокера, других сервисов, уровень логов — переменные окружения (`FITCLUB_DB_URL`, `FITCLUB_RABBITMQ_HOST`, `FITCLUB_MEMBERSHIP_URL`, `LOGGING_LEVEL_COM_FITCLUB`…). Конфигурация Nginx — шаблон с переменными `FITCLUB_GATEWAY_HOST` и DNS-резолвером из `/etc/resolv.conf` |
| Память JVM | `-XX:MaxRAMPercentage=60`: heap считается от лимита контейнера, а не от памяти машины. `-XX:+ExitOnOutOfMemoryError`: при нехватке памяти процесс завершается, и оркестратор перезапускает контейнер, а не оставляет «полуживой» |

## Замеры (19.11.2026, Docker Desktop, WSL 2, 8 ГБ)

| Что | Значение |
|---|---|
| Образ booking-service | 424 МБ в Docker Desktop, **131 МБ сжатый** (`docker save \| gzip`) |
| Слои booking-service | JRE alpine (база), библиотеки 72,2 МБ, код и модули проекта 209 КБ |
| Слои gateway | библиотеки 63,2 МБ, код 45 КБ |
| Образ frontend | 92,9 МБ (база nginx alpine), **25 МБ сжатый**; статика и конфигурация — 0,2 МБ |
| Контекст сборки booking-service | **277 КБ** (раньше — около 1 МБ: в контекст попадали исходники всех сервисов и UI) |
| Сборка booking-service с тёплым кэшем Maven | ~105 с (компиляция модуля и зависимых); без изменений в коде — секунды |
| Память Java-сервиса **без лимита** | 410–460 МиБ: JVM рассчитывает heap от 7,6 ГБ машины и берёт память «про запас» |
| Память Java-сервиса **с лимитом 768 МиБ** после E2E-нагрузки | 292–307 МиБ (Gateway 204 из 512, PostgreSQL 146 из 512, RabbitMQ 94 из 512, Nginx 6 из 64) |

Проверка, что ограничения ничего не сломали: E2E-тесты Лабы 2 (`lab2/tests/e2e/run-e2e.ps1`) на этих образах
в Compose — **9 из 9**. Образы при этом запущены с лимитами, корневой ФС только для чтения и без root.

## Сборка

Для Docker Compose (из каталога `lab2`):

```powershell
docker compose build
```

Для Minikube (из каталога `lab3`, Minikube запущен). Образы собираются в Docker Desktop и копируются на узел
Minikube (`minikube image load`), реестр не нужен. Замер: 66 с на 8 уже собранных образов.

```powershell
.\docker\build-images.ps1
```

Сборка прямо в Docker внутри Minikube (`minikube docker-env`) работает только для кластера с
`--container-runtime=docker`: с containerd, который Minikube 1.39 использует по умолчанию, она не поддерживается.

```powershell
.\docker\build-images.ps1 -DockerEnv
```

## Рассмотренные альтернативы

| Вариант | Почему не выбран |
|---|---|
| Один Dockerfile на JDK (без многостадийной сборки) | В образе остались бы JDK, Maven, исходники и локальный репозиторий: в разы больше и шире поверхность атаки |
| Сборка jar на хосте и `COPY target/*.jar` | Образ зависит от того, что собрано на машине разработчика; нельзя собрать «с нуля» одной командой |
| `jlink` (своя урезанная JRE) | Минус ~100 МБ, но список модулей JDK для Spring/Hibernate нужно подбирать и сопровождать. Для учебного стенда выигрыш не окупает сложность |
| Distroless / Buildpacks (`spring-boot:build-image`) | Меньше утилит в образе, но нет shell (`kubectl exec … sh` для отладки на защите недоступен), а Buildpacks скачивают сборщик ~1 ГБ |
| `nginxinc/nginx-unprivileged` | Готовый Nginx без root, но это второй базовый образ. Того же результата добились тремя строками в Dockerfile официального образа |
