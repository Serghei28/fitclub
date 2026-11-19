# syntax=docker/dockerfile:1
# Образ membership-service. Сборка из корня lab2:  docker compose build membership-service
# Контекст — корень lab2 (родительский POM и общие модули libs/). Что попадает в контекст, задаёт
# services/membership-service/Dockerfile.dockerignore: POM-файлы модулей и исходники только нужных модулей.

# ---------- 1. Сборка: JDK и Maven Wrapper, в итоговый образ не попадают ----------
FROM eclipse-temurin:21.0.13_9-jdk-alpine AS build
WORKDIR /src
# Сначала то, что меняется редко (wrapper и POM), потом исходники: слои копирования кэшируются
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY libs/ libs/
COPY services/ services/
COPY gateway/ gateway/
# Локальный репозиторий Maven — в кэше BuildKit: зависимости не скачиваются при каждой сборке
# и не попадают в слои образа. Тесты здесь не компилируются: они выполняются в mvnw verify.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    sh ./mvnw -B -q -pl services/membership-service -am package -Dmaven.test.skip=true \
 && java -Djarmode=tools -jar services/membership-service/target/membership-service.jar extract --layers --destination /layers \
 && mkdir -p /layers/dependencies /layers/spring-boot-loader /layers/snapshot-dependencies /layers/application

# ---------- 2. Запуск: только JRE и приложение ----------
FROM eclipse-temurin:21.0.13_9-jre-alpine
# Отдельный пользователь с числовым UID: Kubernetes (runAsNonRoot) может проверить, что это не root
RUN addgroup -S -g 10001 fitclub && adduser -S -D -H -u 10001 -G fitclub -s /sbin/nologin fitclub
WORKDIR /app
# Слои Spring Boot от редко меняющихся к часто меняющимся: при правке кода пересобирается
# и скачивается только последний небольшой слой application, а не ~70 МБ библиотек
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER 10001:10001
EXPOSE 8080
# Heap JVM — 60% от лимита памяти контейнера (cgroup), остальное — metaspace, потоки, буферы; время — UTC. Секретов в образе нет:
# они монтируются при запуске в /run/secrets (Compose secrets / Kubernetes Secret).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
# Exec-форма: java — PID 1 и получает SIGTERM для корректной остановки
ENTRYPOINT ["java", "-jar", "/app/membership-service.jar"]
