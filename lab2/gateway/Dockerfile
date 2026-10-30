# syntax=docker/dockerfile:1
# Сборка из корня lab2:  docker compose build gateway
# Контекст — весь репозиторий: родительский POM перечисляет все модули.

FROM eclipse-temurin:21.0.13_9-jdk-alpine AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY libs/ libs/
COPY services/ services/
COPY gateway/ gateway/
RUN --mount=type=cache,target=/root/.m2,sharing=locked     sh ./mvnw -B -q -pl gateway -am package -DskipTests

FROM eclipse-temurin:21.0.13_9-jre-alpine
RUN addgroup -S fitclub && adduser -S -G fitclub fitclub
WORKDIR /app
COPY --from=build /src/gateway/target/gateway.jar app.jar
USER fitclub
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
