# 🐳 Публикация в Docker Hub

## Пошаговая инструкция

### Шаг 1: Подготовка

Убедитесь что у вас:
- ✅ Установлен Docker Desktop
- ✅ Есть аккаунт на [Docker Hub](https://hub.docker.com)
- ✅ Docker daemon запущен

### Шаг 2: Вход в Docker Hub

```bash
docker login
```

Введите ваш username и password от Docker Hub.

### Шаг 3: Сборка образа

```bash
# Замените YOUR_USERNAME на ваш Docker Hub username
docker build -t YOUR_USERNAME/loyalty-bot:1.0.0 .
docker build -t YOUR_USERNAME/loyalty-bot:latest .
```

**Пример:**
```bash
docker build -t johndoe/loyalty-bot:1.0.0 .
docker build -t johndoe/loyalty-bot:latest .
```

Процесс займет 3-5 минут. Вы увидите:
- Загрузку зависимостей Maven
- Компиляцию Java кода
- Создание финального образа

### Шаг 4: Проверка образа (опционально)

```bash
# Посмотреть список образов
docker images | grep loyalty-bot

# Тестовый запуск (замените YOUR_USERNAME)
docker run -d \
  --name loyalty-bot-test \
  -p 8080:8080 \
  -e TELEGRAM_BOT_TOKEN=your_token_here \
  -e TELEGRAM_BOT_USERNAME=your_bot_username \
  YOUR_USERNAME/loyalty-bot:latest

# Проверить логи
docker logs -f loyalty-bot-test

# Проверить health check
curl http://localhost:8080/actuator/health

# Остановить тестовый контейнер
docker stop loyalty-bot-test && docker rm loyalty-bot-test
```

### Шаг 5: Публикация в Docker Hub

```bash
# Публикуем версию 1.0.0
docker push YOUR_USERNAME/loyalty-bot:1.0.0

# Публикуем latest
docker push YOUR_USERNAME/loyalty-bot:latest
```

**Пример:**
```bash
docker push johndoe/loyalty-bot:1.0.0
docker push johndoe/loyalty-bot:latest
```

Процесс загрузки займет 2-3 минуты.

### Шаг 6: Проверка публикации

Откройте в браузере:
```
https://hub.docker.com/r/YOUR_USERNAME/loyalty-bot
```

---

## 🚀 Быстрые команды (скопировать и выполнить)

**1. Установите переменную с вашим username:**
```bash
export DOCKER_USERNAME=your_username_here
```

**2. Выполните все команды сразу:**
```bash
# Логин
docker login

# Сборка
docker build -t ${DOCKER_USERNAME}/loyalty-bot:1.0.0 -t ${DOCKER_USERNAME}/loyalty-bot:latest .

# Публикация
docker push ${DOCKER_USERNAME}/loyalty-bot:1.0.0
docker push ${DOCKER_USERNAME}/loyalty-bot:latest

# Информация
echo "✓ Образ опубликован!"
echo "Доступен по адресу: https://hub.docker.com/r/${DOCKER_USERNAME}/loyalty-bot"
```

---

## 📦 Использование на сервере

После публикации любой может скачать и запустить ваш бот:

```bash
# На любом сервере с Docker
docker pull YOUR_USERNAME/loyalty-bot:latest

docker run -d \
  --name loyalty-bot \
  --restart unless-stopped \
  -p 8080:8080 \
  -e TELEGRAM_BOT_TOKEN=your_token \
  -e TELEGRAM_BOT_USERNAME=your_username \
  -e ADMIN_SECRET_CODE=your_secret \
  YOUR_USERNAME/loyalty-bot:latest
```

### С PostgreSQL (через docker-compose)

Создайте на сервере файл `docker-compose.yml`:

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: loyalty_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    restart: unless-stopped

  app:
    image: YOUR_USERNAME/loyalty-bot:latest
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DATABASE_URL: jdbc:postgresql://postgres:5432/loyalty_db
      DATABASE_USERNAME: postgres
      DATABASE_PASSWORD: ${DB_PASSWORD}
      TELEGRAM_BOT_TOKEN: ${TELEGRAM_BOT_TOKEN}
      TELEGRAM_BOT_USERNAME: ${TELEGRAM_BOT_USERNAME}
      ADMIN_SECRET_CODE: ${ADMIN_SECRET_CODE}
    ports:
      - "8080:8080"
    depends_on:
      - postgres
    restart: unless-stopped

volumes:
  postgres_data:
```

И `.env` файл:
```env
DB_PASSWORD=secure_password
TELEGRAM_BOT_TOKEN=your_token
TELEGRAM_BOT_USERNAME=your_bot
ADMIN_SECRET_CODE=your_secret
```

Запуск:
```bash
docker-compose up -d
```

---

## 🔄 Обновление образа

При изменении кода:

```bash
# 1. Пересобрать
docker build -t ${DOCKER_USERNAME}/loyalty-bot:1.0.1 -t ${DOCKER_USERNAME}/loyalty-bot:latest .

# 2. Опубликовать
docker push ${DOCKER_USERNAME}/loyalty-bot:1.0.1
docker push ${DOCKER_USERNAME}/loyalty-bot:latest

# 3. На сервере обновить
docker pull ${DOCKER_USERNAME}/loyalty-bot:latest
docker-compose down
docker-compose up -d
```

---

## 🐛 Troubleshooting

### Ошибка "no basic auth credentials"
```bash
docker login
# Введите username и password
```

### Ошибка "denied: requested access to the resource is denied"
- Проверьте что используете правильный username
- Убедитесь что залогинены: `docker login`

### Образ не запускается
```bash
# Проверьте логи
docker logs loyalty-bot-test

# Проверьте что JAR файл создан правильно
docker run --rm -it YOUR_USERNAME/loyalty-bot:latest sh
ls -la /app/
```

### "Manifest not found" при запуске
Это значит что Spring Boot JAR собран неправильно. Проверьте:
```bash
# Локально соберите и проверьте
mvn clean package
ls -lah target/
# Должен быть файл loyalty-bot-1.0.0.jar (не .original)
```

---

## 📊 Информация об образе

После публикации на Docker Hub вы увидите:
- 📦 **Размер образа**: ~300-400 MB
- 🏷️ **Теги**: `latest`, `1.0.0`
- 📥 **Публичный доступ**: любой может `docker pull`
- 🔒 **Приватный**: если нужно - в настройках репозитория

---

**Готово! Ваше приложение теперь в Docker Hub! 🎉**


