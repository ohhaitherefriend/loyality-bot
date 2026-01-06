# 🚀 Деплой через Docker Hub в Яндекс Облако

## ⚡ Быстрый старт

### Шаг 1: Соберите и опубликуйте в Docker Hub

```bash
./docker-build-push.sh
```

Скрипт:
- ✅ Соберет образ для **AMD64** архитектуры (совместимо с Яндекс Облаком)
- ✅ Проверит корректность архитектуры
- ✅ Опубликует в Docker Hub

### Шаг 2: На виртуалке в Яндекс Облаке

#### Вариант A: Через docker run

```bash
# Замените your-username на ваш Docker Hub username
docker pull your-username/loyalty-bot:latest

docker run -d \
  --name loyalty-bot \
  -p 8080:8080 \
  -e TELEGRAM_BOT_TOKEN=your_bot_token \
  -e TELEGRAM_BOT_USERNAME=your_bot_username \
  -e ADMIN_SECRET_CODE=your_secret_code \
  -e SPRING_PROFILES_ACTIVE=prod \
  -v $(pwd)/data:/app/data \
  --restart unless-stopped \
  your-username/loyalty-bot:latest
```

#### Вариант B: Через docker-compose (рекомендуется)

Создайте `docker-compose.yml`:

```yaml
version: '3.8'

services:
  app:
    image: your-username/loyalty-bot:latest
    container_name: loyalty-bot
    environment:
      SPRING_PROFILES_ACTIVE: prod
      TELEGRAM_BOT_TOKEN: ${TELEGRAM_BOT_TOKEN}
      TELEGRAM_BOT_USERNAME: ${TELEGRAM_BOT_USERNAME}
      ADMIN_SECRET_CODE: ${ADMIN_SECRET_CODE}
    ports:
      - "8080:8080"
    volumes:
      - ./data:/app/data
    restart: unless-stopped
```

Создайте `.env`:

```bash
TELEGRAM_BOT_TOKEN=your_bot_token_here
TELEGRAM_BOT_USERNAME=your_bot_username
ADMIN_SECRET_CODE=your_secret_code
```

Запустите:

```bash
docker-compose up -d
```

### Шаг 3: Проверка

```bash
# Проверьте что контейнер запущен
docker ps

# Посмотрите логи
docker logs -f loyalty-bot
# или
docker-compose logs -f

# Проверьте что бот отвечает в Telegram
# Отправьте /start вашему боту
```

---

## 📋 Полная инструкция для Яндекс Облака

### 1. Создайте виртуальную машину

Через веб-интерфейс Яндекс Облака или CLI:

```bash
yc compute instance create \
  --name loyalty-bot-vm \
  --zone ru-central1-a \
  --network-interface subnet-name=default-ru-central1-a,nat-ip-version=ipv4 \
  --create-boot-disk image-folder-id=standard-images,image-family=ubuntu-2204-lts,size=20 \
  --memory 2GB \
  --cores 2 \
  --ssh-key ~/.ssh/id_rsa.pub
```

Параметры:
- **Ubuntu 22.04 LTS** - рекомендуемая ОС
- **2 GB RAM** - минимум для Java приложения
- **2 vCPU** - достаточно для среднего бота
- **20 GB диск** - для ОС, Docker и базы данных

### 2. Подключитесь к виртуалке

```bash
# Узнайте внешний IP
yc compute instance list

# Подключитесь по SSH
ssh ubuntu@YOUR_VM_IP
```

### 3. Установите Docker

```bash
# Обновите систему
sudo apt update && sudo apt upgrade -y

# Установите Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# Добавьте пользователя в группу docker
sudo usermod -aG docker $USER

# Установите Docker Compose
sudo apt install docker-compose -y

# Перелогиньтесь для применения изменений
exit
ssh ubuntu@YOUR_VM_IP
```

### 4. Разверните приложение

```bash
# Создайте директорию для проекта
mkdir loyalty-bot
cd loyalty-bot

# Создайте docker-compose.yml
cat > docker-compose.yml << 'EOF'
version: '3.8'

services:
  app:
    image: your-username/loyalty-bot:latest
    container_name: loyalty-bot
    env_file: .env
    ports:
      - "8080:8080"
    volumes:
      - ./data:/app/data
    restart: unless-stopped
EOF

# Создайте .env файл
cat > .env << 'EOF'
SPRING_PROFILES_ACTIVE=prod
TELEGRAM_BOT_TOKEN=your_bot_token_here
TELEGRAM_BOT_USERNAME=your_bot_username
ADMIN_SECRET_CODE=your_secret_code
EOF

# Отредактируйте .env с вашими настройками
nano .env

# Запустите
docker-compose up -d

# Проверьте логи
docker-compose logs -f
```

---

## 🔄 Обновление приложения

### Когда вы внесли изменения в код:

**На вашем Mac:**

```bash
# Соберите и опубликуйте новую версию
./docker-build-push.sh
```

**На сервере в Яндекс Облаке:**

```bash
cd loyalty-bot

# Остановите контейнер
docker-compose down

# Загрузите новую версию
docker-compose pull

# Запустите
docker-compose up -d

# Проверьте логи
docker-compose logs -f
```

---

## 🐛 Решение проблем

### Ошибка: exec format error

**Причина:** Образ собран для ARM64 вместо AMD64.

**Решение:** Пересоберите образ через обновленный `./docker-build-push.sh` - он автоматически собирает для AMD64.

### Бот не отвечает в Telegram

```bash
# Проверьте логи
docker-compose logs -f

# Проверьте переменные окружения
docker-compose exec app env | grep TELEGRAM

# Проверьте что контейнер запущен
docker ps
```

### Недостаточно памяти (Out of Memory)

В `docker-compose.yml` добавьте ограничения памяти и увеличьте JVM heap:

```yaml
services:
  app:
    # ... остальное
    environment:
      JAVA_OPTS: "-Xms512m -Xmx1024m"
    deploy:
      resources:
        limits:
          memory: 1.5G
```

### База данных не сохраняется

Убедитесь что volume настроен правильно:

```bash
# Проверьте что директория создана
ls -la ./data/

# Проверьте права доступа
sudo chown -R 1000:1000 ./data/
```

---

## 💡 Преимущества этого подхода

✅ **Простота** - не нужно настраивать Yandex Container Registry  
✅ **Публичный доступ** - образ доступен всем (если образ не содержит секретов)  
✅ **Совместимость** - образ собран для AMD64, работает на любом облаке  
✅ **Быстрое обновление** - просто `docker-compose pull && docker-compose up -d`

---

## 📊 Мониторинг

### Просмотр логов

```bash
# Все логи
docker-compose logs

# Последние 100 строк
docker-compose logs --tail=100

# В реальном времени
docker-compose logs -f

# Только ошибки
docker-compose logs | grep ERROR
```

### Статистика контейнера

```bash
# Использование ресурсов
docker stats loyalty-bot

# Информация о контейнере
docker inspect loyalty-bot
```

### Размер базы данных

```bash
# Размер data директории
du -sh ./data/
```

---

## 🔒 Безопасность

### Рекомендации:

1. **Не храните секреты в docker-compose.yml** - используйте `.env` файл
2. **Добавьте .env в .gitignore** если используете git на сервере
3. **Настройте firewall**:

```bash
# Разрешите только SSH и HTTP/HTTPS
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

4. **Регулярно обновляйте систему:**

```bash
sudo apt update && sudo apt upgrade -y
```

5. **Настройте автоматический backup базы данных:**

```bash
# Создайте скрипт backup.sh
cat > backup.sh << 'EOF'
#!/bin/bash
DATE=$(date +%Y%m%d_%H%M%S)
tar -czf backup_${DATE}.tar.gz data/
# Опционально: загрузите в облачное хранилище
EOF

chmod +x backup.sh

# Настройте cron для ежедневного backup
crontab -e
# Добавьте: 0 2 * * * /home/ubuntu/loyalty-bot/backup.sh
```

---

**Готово! Ваш бот работает в Яндекс Облаке через Docker Hub!** 🎉


