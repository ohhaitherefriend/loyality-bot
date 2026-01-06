# 🏗️ Сборка Docker образа для Яндекс Облака

## ⚠️ Проблема совместимости архитектур

Если собрать Docker образ на **Mac M1/M2** (ARM64) и запустить на виртуалке в **Яндекс Облаке** (AMD64/x86_64), получите ошибку:

```
exec format error
```

или 

```
standard_init_linux.go: exec user process caused: exec format error
```

## ✅ Решение

Используйте **новый скрипт** `docker-build-yandex.sh`, который собирает образ специально для AMD64 архитектуры.

## 🚀 Использование

### Быстрый старт

```bash
# Запустите скрипт
./docker-build-yandex.sh
```

Скрипт предложит выбрать:
1. **Yandex Container Registry** (рекомендуется) - образ будет в вашем приватном реестре
2. **Docker Hub** - публичный образ
3. **Локальная сборка** - только собрать, не публиковать

### Что делает скрипт?

1. ✅ Проверяет вашу текущую архитектуру
2. ✅ Собирает образ специально для **AMD64** (x86_64)
3. ✅ Проверяет, что архитектура собранного образа корректна
4. ✅ Публикует в выбранный реестр
5. ✅ Выводит команды для запуска на сервере

## 📋 Детали команд

### Вариант 1: Сборка с флагом --platform (простой)

```bash
# Для Yandex Container Registry
docker build --platform linux/amd64 \
  -t cr.yandex/YOUR_REGISTRY_ID/loyalty-bot:latest .

# Для Docker Hub
docker build --platform linux/amd64 \
  -t your-username/loyalty-bot:latest .
```

### Вариант 2: Сборка с buildx (продвинутый)

```bash
# Создание builder'а для мультиплатформенной сборки
docker buildx create --name multiarch --use

# Сборка для AMD64
docker buildx build \
  --platform linux/amd64 \
  --load \
  -t your-image:latest .

# Или сборка сразу для обеих архитектур (если нужно)
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --push \
  -t your-image:latest .
```

## 🔍 Проверка архитектуры образа

После сборки всегда проверяйте архитектуру:

```bash
# Проверка архитектуры образа
docker inspect your-image:latest --format='{{.Architecture}}'

# Должно вывести: amd64
```

## 📦 Для Yandex Container Registry

### Шаг 1: Установка YC CLI (если не установлен)

```bash
curl https://storage.yandexcloud.net/yandexcloud-yc/install.sh | bash
exec -l $SHELL  # Перезапуск shell
yc init
```

### Шаг 2: Создание реестра

```bash
# Создать реестр
yc container registry create --name loyalty-bot-registry

# Посмотреть список реестров
yc container registry list

# Настроить Docker для работы с реестром
yc container registry configure-docker
```

### Шаг 3: Сборка и публикация

```bash
# Используйте скрипт
./docker-build-yandex.sh

# Или вручную:
REGISTRY_ID="ваш_registry_id"
docker build --platform linux/amd64 \
  -t cr.yandex/${REGISTRY_ID}/loyalty-bot:latest .
docker push cr.yandex/${REGISTRY_ID}/loyalty-bot:latest
```

## 🖥️ Использование на виртуалке в Яндекс Облаке

### На виртуалке выполните:

```bash
# 1. Настройка Docker для работы с реестром
yc container registry configure-docker

# 2. Загрузка образа
docker pull cr.yandex/YOUR_REGISTRY_ID/loyalty-bot:latest

# 3. Запуск
docker run -d \
  --name loyalty-bot \
  -p 8080:8080 \
  -e TELEGRAM_BOT_TOKEN=your_token \
  -e TELEGRAM_BOT_USERNAME=your_bot \
  -e ADMIN_SECRET_CODE=your_secret \
  -v $(pwd)/data:/app/data \
  cr.yandex/YOUR_REGISTRY_ID/loyalty-bot:latest
```

### Или через docker-compose:

```yaml
version: '3.8'

services:
  app:
    image: cr.yandex/YOUR_REGISTRY_ID/loyalty-bot:latest
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

## 🎯 Частые проблемы и решения

### Проблема: exec format error

**Причина**: Образ собран для ARM64, а сервер использует AMD64.

**Решение**: Пересоберите образ с флагом `--platform linux/amd64`.

### Проблема: образ собирается очень долго

**Причина**: При кросс-платформенной сборке используется эмуляция (QEMU).

**Решение**: Это нормально. Первая сборка может занять 5-10 минут. Последующие сборки будут быстрее благодаря кешу Docker.

### Проблема: buildx не работает

```bash
# Обновите Docker Desktop до последней версии
# Или установите buildx вручную:
brew install docker-buildx  # macOS

# Создайте builder
docker buildx create --name multiarch --use
docker buildx inspect --bootstrap
```

### Проблема: нет доступа к Yandex Container Registry

```bash
# Проверьте авторизацию
yc config list

# Переавторизуйтесь
yc init

# Настройте Docker снова
yc container registry configure-docker
```

## 📊 Сравнение архитектур

| Платформа | Архитектура | Использование |
|-----------|-------------|---------------|
| Mac M1/M2 | ARM64 | Ваш локальный Mac |
| Mac Intel | AMD64 | Старые Mac |
| Яндекс Облако VM | AMD64 | Виртуальные машины |
| AWS/GCP/Azure | AMD64 | Большинство VM |
| Raspberry Pi | ARM64 | IoT устройства |

**Важно**: Виртуалки в облаках почти всегда используют AMD64!

## 🎓 Дополнительно

### Проверка текущей архитектуры:

```bash
# На Mac/Linux
uname -m
# arm64 = ARM64
# x86_64 = AMD64

# В Docker контейнере
docker run --rm alpine uname -m
```

### Мультиплатформенная сборка для обеих архитектур:

```bash
# Сборка и push для AMD64 и ARM64 одновременно
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --push \
  -t your-image:latest .
```

---

## ✅ Рекомендация

**Используйте скрипт** `./docker-build-yandex.sh` - он автоматически:
- ✅ Определит вашу архитектуру
- ✅ Соберет для правильной архитектуры (AMD64)
- ✅ Проверит результат
- ✅ Опубликует в выбранный реестр
- ✅ Покажет команды для запуска

**Проблемы с архитектурой решены!** 🎉


