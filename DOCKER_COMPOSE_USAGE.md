# 🚀 Использование Docker Compose с готовым образом

## Вариант 1: Простой (только бот с H2)

### Шаг 1: Создайте .env файл

```bash
cat > .env << EOF
DOCKER_USERNAME=your_dockerhub_username
TELEGRAM_BOT_TOKEN=your_token
TELEGRAM_BOT_USERNAME=your_bot_username
ADMIN_SECRET_CODE=your_secret
EOF
```

### Шаг 2: Запустите

```bash
docker-compose -f docker-compose.simple.yml up -d
```

### Шаг 3: Проверьте логи

```bash
docker-compose -f docker-compose.simple.yml logs -f
```

### Остановить

```bash
docker-compose -f docker-compose.simple.yml down
```

---

## Вариант 2: С PostgreSQL (продакшен)

### Шаг 1: Создайте .env файл

```bash
cat > .env << EOF
DOCKER_USERNAME=your_dockerhub_username
TELEGRAM_BOT_TOKEN=your_token
TELEGRAM_BOT_USERNAME=your_bot_username
ADMIN_SECRET_CODE=your_secret
DB_PASSWORD=secure_database_password
EOF
```

### Шаг 2: Запустите

```bash
docker-compose -f docker-compose.prod.yml up -d
```

### Шаг 3: Проверьте

```bash
# Логи бота
docker-compose -f docker-compose.prod.yml logs -f app

# Логи PostgreSQL
docker-compose -f docker-compose.prod.yml logs -f postgres

# Статус всех сервисов
docker-compose -f docker-compose.prod.yml ps
```

### Остановить

```bash
docker-compose -f docker-compose.prod.yml down
```

---

## 📦 Полные команды для копирования

### Простой вариант (H2):

```bash
# 1. Создать .env
export DOCKER_USERNAME=your_username
cat > .env << EOF
DOCKER_USERNAME=${DOCKER_USERNAME}
TELEGRAM_BOT_TOKEN=your_token_here
TELEGRAM_BOT_USERNAME=your_bot_username
ADMIN_SECRET_CODE=plstkwrld
EOF

# 2. Запустить
docker-compose -f docker-compose.simple.yml up -d

# 3. Проверить
docker-compose -f docker-compose.simple.yml logs -f
```

### PostgreSQL вариант:

```bash
# 1. Создать .env
export DOCKER_USERNAME=your_username
cat > .env << EOF
DOCKER_USERNAME=${DOCKER_USERNAME}
TELEGRAM_BOT_TOKEN=your_token_here
TELEGRAM_BOT_USERNAME=your_bot_username
ADMIN_SECRET_CODE=plstkwrld
DB_PASSWORD=secure_password_123
EOF

# 2. Запустить
docker-compose -f docker-compose.prod.yml up -d

# 3. Проверить
docker-compose -f docker-compose.prod.yml logs -f app
```

---

## 🔄 Обновление бота

Когда выйдет новая версия образа:

```bash
# Простой вариант
docker-compose -f docker-compose.simple.yml pull
docker-compose -f docker-compose.simple.yml up -d

# PostgreSQL вариант
docker-compose -f docker-compose.prod.yml pull
docker-compose -f docker-compose.prod.yml up -d
```

---

## 🗄️ Backup базы данных

### H2 (простой вариант):

```bash
# Скопировать файлы базы
docker cp loyalty-bot-app:/app/data ./backup-$(date +%Y%m%d)
```

### PostgreSQL:

```bash
# Создать дамп
docker-compose -f docker-compose.prod.yml exec postgres \
  pg_dump -U postgres loyalty_db > backup-$(date +%Y%m%d).sql

# Восстановить
cat backup-20241012.sql | \
  docker-compose -f docker-compose.prod.yml exec -T postgres \
  psql -U postgres loyalty_db
```

---

## 🐛 Troubleshooting

### Бот не запускается

```bash
# Проверить логи
docker-compose -f docker-compose.simple.yml logs app

# Проверить переменные окружения
docker-compose -f docker-compose.simple.yml exec app env | grep TELEGRAM
```

### PostgreSQL не подключается

```bash
# Проверить что PostgreSQL запущен
docker-compose -f docker-compose.prod.yml ps

# Проверить подключение
docker-compose -f docker-compose.prod.yml exec postgres \
  psql -U postgres -d loyalty_db -c "SELECT 1;"
```

### Очистить всё и начать заново

```bash
# ВНИМАНИЕ: Удалит все данные!
docker-compose -f docker-compose.prod.yml down -v
docker-compose -f docker-compose.prod.yml up -d
```

---

## 📊 Мониторинг

```bash
# Статус контейнеров
docker-compose ps

# Использование ресурсов
docker stats loyalty-bot-app

# Health check
curl http://localhost:8080/actuator/health

# Логи в реальном времени
docker-compose logs -f --tail=100 app
```

---

**Готово! Бот работает! 🎉**


