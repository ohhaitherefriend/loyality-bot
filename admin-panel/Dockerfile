# ========================================
# Stage 1: Build
# ========================================
FROM node:20-alpine AS build

WORKDIR /app

# Копируем файлы зависимостей
COPY package.json package-lock.json ./

# Устанавливаем зависимости
RUN npm ci

# Копируем исходники
COPY . .

# Собираем приложение
# VITE_API_BASE_URL оставляем пустым - nginx будет проксировать /api
RUN npm run build

# ========================================
# Stage 2: Nginx для раздачи статики
# ========================================
FROM nginx:alpine

# Копируем конфиг nginx
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Копируем собранное приложение
COPY --from=build /app/dist /usr/share/nginx/html

# Порт
EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
