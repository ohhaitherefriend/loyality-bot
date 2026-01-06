# 🚀 Деплой на Яндекс.Облако

## ⚠️ ВАЖНО: Совместимость архитектур

**Если вы на Mac M1/M2**, образ нужно собирать специально для AMD64 архитектуры!

**Используйте новый скрипт:**
```bash
./docker-build-yandex.sh
```

Подробнее читайте в [DOCKER_BUILD_ARCH.md](DOCKER_BUILD_ARCH.md)

---

## Вариант 1: Yandex Container Registry + Compute Instance

### Шаг 1: Подготовка проекта

```bash
# Создайте .env файл с настройками
cp .env.example .env
nano .env  # Заполните переменные
```

### Шаг 2: Установка Yandex Cloud CLI

```bash
# macOS
curl https://storage.yandexcloud.net/yandexcloud-yc/install.sh | bash

# Перезапустите терминал и инициализируйте
yc init
```

### Шаг 3: Создание Container Registry

```bash
# Создайте реестр
yc container registry create --name loyalty-bot-registry

# Получите ID реестра
yc container registry list

# Настройте Docker для работы с реестром
yc container registry configure-docker
```

### Шаг 4: Сборка и публикация образа

**Рекомендуется: используйте готовый скрипт**
```bash
./docker-build-yandex.sh
```

**Или вручную:**
```bash
# Получите ID реестра
REGISTRY_ID=$(yc container registry list --format json | jq -r '.[0].id')

# ⚠️ ВАЖНО: Собирайте для AMD64 архитектуры!
docker build --platform linux/amd64 \
  -t cr.yandex/${REGISTRY_ID}/loyalty-bot:latest .

# Загрузите в реестр
docker push cr.yandex/${REGISTRY_ID}/loyalty-bot:latest
```

### Шаг 5: Создание Compute Instance

```bash
# Создайте виртуальную машину
yc compute instance create \
  --name loyalty-bot-vm \
  --zone ru-central1-a \
  --network-interface subnet-name=default-ru-central1-a,nat-ip-version=ipv4 \
  --create-boot-disk image-folder-id=standard-images,image-family=container-optimized-image \
  --memory 2GB \
  --cores 2 \
  --core-fraction 20 \
  --metadata-from-file user-data=cloud-init.yaml
```

### Шаг 6: Создайте cloud-init.yaml

```yaml
#cloud-config

write_files:
  - path: /etc/docker-compose.yaml
    permissions: '0644'
    content: |
      version: '3.8'
      services:
        app:
          image: cr.yandex/${REGISTRY_ID}/loyalty-bot:latest
          container_name: loyalty-bot
          environment:
            SPRING_PROFILES_ACTIVE: prod
            TELEGRAM_BOT_TOKEN: "${TELEGRAM_BOT_TOKEN}"
            TELEGRAM_BOT_USERNAME: "${TELEGRAM_BOT_USERNAME}"
            ADMIN_SECRET_CODE: "${ADMIN_SECRET_CODE}"
          ports:
            - "8080:8080"
          restart: unless-stopped

runcmd:
  - cd /etc
  - docker-compose up -d
```

---

## Вариант 2: Yandex Serverless Containers (Рекомендуется для малых нагрузок)

### Шаг 1: Создание Serverless Container

```bash
# Создайте контейнер
yc serverless container create --name loyalty-bot

# Создайте ревизию с образом
yc serverless container revision deploy \
  --container-name loyalty-bot \
  --image cr.yandex/${REGISTRY_ID}/loyalty-bot:latest \
  --cores 1 \
  --memory 512MB \
  --execution-timeout 60s \
  --service-account-id ${SERVICE_ACCOUNT_ID} \
  --environment TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN} \
  --environment TELEGRAM_BOT_USERNAME=${TELEGRAM_BOT_USERNAME} \
  --environment ADMIN_SECRET_CODE=${ADMIN_SECRET_CODE}

# Сделайте контейнер публичным
yc serverless container allow-unauthenticated-invoke loyalty-bot
```

---

## Вариант 3: Docker Compose на обычной VM

### Шаг 1: Создайте VM

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

### Шаг 2: Подключитесь к VM

```bash
# Получите внешний IP
yc compute instance list

# Подключитесь по SSH (замените YOUR_IP)
ssh ubuntu@YOUR_IP
```

### Шаг 3: Установите Docker

```bash
# Обновите систему
sudo apt update && sudo apt upgrade -y

# Установите Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER

# Установите Docker Compose
sudo apt install docker-compose -y

# Перелогиньтесь
exit
ssh ubuntu@YOUR_IP
```

### Шаг 4: Разверните приложение

```bash
# Склонируйте репозиторий или загрузите файлы
git clone <your-repo-url>
cd plstk-wrld-bot

# Создайте .env файл
nano .env
# Заполните переменные:
# TELEGRAM_BOT_TOKEN=...
# TELEGRAM_BOT_USERNAME=...
# ADMIN_SECRET_CODE=...
# DB_PASSWORD=...

# Запустите через Docker Compose
docker-compose up -d

# Проверьте логи
docker-compose logs -f app
```

---

## 🔒 Настройка PostgreSQL в Yandex Cloud (опционально)

Если хотите использовать Managed PostgreSQL:

```bash
# Создайте кластер PostgreSQL
yc managed-postgresql cluster create \
  --name loyalty-bot-db \
  --environment production \
  --network-name default \
  --resource-preset s2.micro \
  --disk-size 10 \
  --disk-type network-ssd \
  --postgresql-version 15 \
  --user name=admin,password=YOUR_SECURE_PASSWORD \
  --database name=loyalty_db,owner=admin

# Получите хост БД
yc managed-postgresql cluster list-hosts loyalty-bot-db

# Обновите переменные окружения:
# DATABASE_URL=jdbc:postgresql://HOST:6432/loyalty_db
# DATABASE_USERNAME=admin
# DATABASE_PASSWORD=YOUR_SECURE_PASSWORD
```

---

## 📊 Мониторинг

### Просмотр логов

```bash
# Через Docker Compose
docker-compose logs -f app

# Через Yandex Cloud
yc logging read --group-id=<LOG_GROUP_ID>
```

### Проверка здоровья

```bash
curl http://YOUR_IP:8080/actuator/health
```

---

## 🔄 Обновление приложения

### Docker Compose на VM:

```bash
cd plstk-wrld-bot
git pull
docker-compose down
docker-compose build --no-cache
docker-compose up -d
```

### Serverless Container:

```bash
# Пересоберите и загрузите образ (с правильной архитектурой!)
docker build --platform linux/amd64 \
  -t cr.yandex/${REGISTRY_ID}/loyalty-bot:latest .
docker push cr.yandex/${REGISTRY_ID}/loyalty-bot:latest

# Создайте новую ревизию
yc serverless container revision deploy \
  --container-name loyalty-bot \
  --image cr.yandex/${REGISTRY_ID}/loyalty-bot:latest \
  ... # остальные параметры
```

---

## 💰 Примерная стоимость

### Вариант 1: Compute Instance (VM)
- **2 vCPU, 2 GB RAM, 20% vCPU**: ~500₽/месяц
- **Трафик**: ~100₽/месяц
- **Итого**: ~600₽/месяц

### Вариант 2: Serverless Containers
- **Pay-as-you-go**: зависит от нагрузки
- **При малой нагрузке**: 100-300₽/месяц
- **Рекомендуется для старта**

### Вариант 3: Managed PostgreSQL
- **s2.micro кластер**: ~1500₽/месяц
- **Альтернатива**: используйте H2 в файловом режиме (бесплатно)

---

## ✅ Чеклист деплоя

- [ ] Создан Container Registry
- [ ] Собран и загружен Docker образ
- [ ] Создана VM или Serverless Container
- [ ] Настроены переменные окружения
- [ ] Приложение запущено и работает
- [ ] Бот отвечает в Telegram
- [ ] Настроен мониторинг логов
- [ ] Создан backup базы данных (если используется)

---

## 🆘 Troubleshooting

### Бот не отвечает
```bash
# Проверьте логи
docker-compose logs -f app

# Проверьте что порт открыт
curl http://localhost:8080/actuator/health
```

### Ошибки базы данных
```bash
# Проверьте подключение к PostgreSQL
docker-compose exec postgres psql -U postgres -d loyalty_db -c "SELECT 1;"

# Или используйте H2 в dev режиме
# Измените SPRING_PROFILES_ACTIVE=dev
```

### Out of Memory
```bash
# Увеличьте память VM или измените JAVA_OPTS в Dockerfile
ENV JAVA_OPTS="-Xms512m -Xmx1024m ..."
```

---

**Готово! Ваш бот работает в облаке! 🎉**


