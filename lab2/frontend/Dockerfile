# Статический UI FitClub на Nginx. Сборка из корня lab2:  docker compose build frontend
FROM nginx:1.30.6-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/static/ /usr/share/nginx/html/
# Проверка конфигурации при сборке образа: ошибка в nginx.conf не доживёт до запуска
RUN nginx -t
EXPOSE 80
