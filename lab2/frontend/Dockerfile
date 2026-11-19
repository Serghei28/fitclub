# syntax=docker/dockerfile:1
# Статический UI FitClub на Nginx. Сборка из корня lab2:  docker compose build frontend
# Контекст ограничен файлом frontend/Dockerfile.dockerignore: только шаблон конфигурации и статика.
FROM nginx:1.30.6-alpine

# Nginx без root: директива user не нужна (процесс уже не root), pid — в /tmp,
# каталоги кэша и сгенерированной конфигурации принадлежат пользователю nginx (UID 101).
# worker_processes 2: значение auto берёт число ядер хоста и игнорирует лимит CPU контейнера.
RUN sed -i -e '/^user /d' -e 's#^pid .*#pid /tmp/nginx.pid;#' -e 's#^worker_processes .*#worker_processes 2;#' \
        /etc/nginx/nginx.conf \
 && chown -R 101:101 /var/cache/nginx /etc/nginx/conf.d

COPY frontend/default.conf.template /etc/nginx/templates/default.conf.template
COPY frontend/static/ /usr/share/nginx/html/

# Значения по умолчанию — для Docker Compose; Kubernetes переопределяет их через ConfigMap.
# NGINX_ENTRYPOINT_LOCAL_RESOLVERS: entrypoint берёт DNS-сервер из /etc/resolv.conf контейнера.
# NGINX_ENVSUBST_FILTER: в шаблон подставляются только эти две переменные, остальное ($host, $uri…) не трогается.
ENV FITCLUB_GATEWAY_HOST=gateway \
    NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    NGINX_ENVSUBST_FILTER="^(NGINX_LOCAL_RESOLVERS|FITCLUB_GATEWAY_HOST)$"

# Проверка конфигурации при сборке: ошибка в шаблоне не доживёт до запуска
RUN NGINX_LOCAL_RESOLVERS=127.0.0.11 envsubst '${NGINX_LOCAL_RESOLVERS} ${FITCLUB_GATEWAY_HOST}' \
      < /etc/nginx/templates/default.conf.template > /etc/nginx/conf.d/default.conf \
 && nginx -t \
 && rm -f /tmp/nginx.pid \
 && chown 101:101 /etc/nginx/conf.d/default.conf

USER 101
EXPOSE 8080
# ENTRYPOINT (/docker-entrypoint.sh: подстановка шаблона) и CMD (nginx -g 'daemon off;') — из базового образа
