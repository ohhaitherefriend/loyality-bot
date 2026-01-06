# ⚡ Быстрый деплой в Яндекс Облако

## 🎯 Самый простой путь (5 минут)

### 1️⃣ Соберите образ для Яндекс Облака

```bash
./docker-build-yandex.sh
```

Выберите опцию **1** (Yandex Container Registry)

### 2️⃣ На виртуалке в Яндекс Облаке

```bash
# Создайте виртуалку Ubuntu 22.04 через веб-интерфейс
# Подключитесь по SSH

# Установите Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER

# Перелогиньтесь
exit
ssh ubuntu@YOUR_IP

# Установите YC CLI
curl https://storage.yandexcloud.net/yandexcloud-yc/install.sh | bash
exec -l $SHELL
yc init

# Настройте Docker для работы с реестром
yc container registry configure-docker

# Создайте .env файл
cat > .env << 'EOF'
TELEGRAM_BOT_TOKEN=your_token_here
TELEGRAM_BOT_USERNAME=your_bot_username
ADMIN_SECRET_CODE=your_secret_code
SPRING_PROFILES_ACTIVE=prod
EOF

# Создайте docker-compose.yml
REGISTRY_ID="ваш_registry_id"  # из вывода: yc container registry list
cat > docker-compose.yml << EOF
version: '3.8'

services:
  app:
    image: cr.yandex/${REGISTRY_ID}/loyalty-bot:latest
    container_name: loyalty-bot
    env_file: .env
    ports:
      - "8080:8080"
    volumes:
      - ./data:/app/data
    restart: unless-stopped
EOF

# Запустите
docker-compose up -d

# Проверьте логи
docker-compose logs -f
```

### 3️⃣ Проверка

```bash
# Проверьте что бот работает
docker-compose logs -f app

# Попробуйте отправить сообщение боту в Telegram
```

## 📚 Полная документация

- **[DOCKER_BUILD_ARCH.md](DOCKER_BUILD_ARCH.md)** - Детали про архитектуры и совместимость
- **[DEPLOY_YANDEX_CLOUD.md](DEPLOY_YANDEX_CLOUD.md)** - Полная инструкция по деплою

## ⚠️ Важные моменты

1. **Mac M1/M2**: Обязательно используйте `docker-build-yandex.sh` или флаг `--platform linux/amd64`
2. **REGISTRY_ID**: Получите через `yc container registry list`
3. **Переменные окружения**: Заполните реальные значения в `.env`

## 🆘 Проблемы?

### Образ не запускается: "exec format error"

**Причина**: Неправильная архитектура образа.

**Решение**: Пересоберите образ через `./docker-build-yandex.sh`

### Бот не отвечает в Telegram

```bash
# Проверьте логи
docker-compose logs -f

# Проверьте переменные окружения
docker-compose exec app env | grep TELEGRAM
```

### Нет доступа к Container Registry

```bash
# Переавторизуйтесь
yc init

# Настройте Docker снова
yc container registry configure-docker

# Проверьте доступ
yc container registry list
```

---

**Готово! Бот работает в облаке!** 🎉


