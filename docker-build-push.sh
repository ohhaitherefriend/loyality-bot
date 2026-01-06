#!/bin/bash

# Скрипт для сборки и публикации Docker образа в Docker Hub
set -e

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Конфигурация
IMAGE_NAME="loyalty-bot"
VERSION="1.0.0"

# Функция для вывода цветного текста
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Проверка что пользователь залогинен в Docker Hub
print_info "Проверка авторизации в Docker Hub..."
if ! docker info > /dev/null 2>&1; then
    print_error "Docker daemon не запущен!"
    exit 1
fi

# Запрашиваем имя пользователя Docker Hub
read -p "Введите ваш Docker Hub username: " DOCKER_USERNAME

if [ -z "$DOCKER_USERNAME" ]; then
    print_error "Username не может быть пустым!"
    exit 1
fi

# Полное имя образа
FULL_IMAGE_NAME="${DOCKER_USERNAME}/${IMAGE_NAME}"

print_info "Будет создан образ: ${FULL_IMAGE_NAME}:${VERSION}"
print_info "Также будет создан тег: ${FULL_IMAGE_NAME}:latest"

read -p "Продолжить? (y/n) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    print_warning "Отменено пользователем"
    exit 0
fi

# Определяем текущую архитектуру
CURRENT_ARCH=$(uname -m)
print_info "Текущая архитектура: ${CURRENT_ARCH}"

if [[ "$CURRENT_ARCH" == "arm64" ]]; then
    print_warning "⚠️  Вы на ARM64 (M1/M2 Mac), собираем для AMD64 (x86_64) для совместимости с облачными серверами"
fi

# Сборка образа для AMD64 архитектуры
print_info "Шаг 1/3: Сборка Docker образа для AMD64 (x86_64)..."
print_warning "Сборка может занять 5-10 минут (особенно первый раз)..."

# Используем buildx если доступен
docker buildx version > /dev/null 2>&1
if [ $? -eq 0 ]; then
    print_info "Используем docker buildx для кросс-платформенной сборки"
    
    # Создаем builder если не существует
    docker buildx create --name multiarch --use 2>/dev/null || docker buildx use multiarch
    
    # Собираем для AMD64
    docker buildx build \
        --platform linux/amd64 \
        --load \
        -t ${FULL_IMAGE_NAME}:${VERSION} \
        -t ${FULL_IMAGE_NAME}:latest \
        .
else
    print_warning "docker buildx не доступен, используем обычную сборку"
    
    # Обычная сборка с указанием платформы
    docker build \
        --platform linux/amd64 \
        -t ${FULL_IMAGE_NAME}:${VERSION} \
        -t ${FULL_IMAGE_NAME}:latest \
        .
fi

if [ $? -ne 0 ]; then
    print_error "Ошибка при сборке образа!"
    exit 1
fi

# Проверка архитектуры собранного образа
print_info "Проверка архитектуры образа..."
IMAGE_ARCH=$(docker inspect ${FULL_IMAGE_NAME}:${VERSION} --format='{{.Architecture}}')
print_info "Архитектура образа: ${IMAGE_ARCH}"

if [ "$IMAGE_ARCH" != "amd64" ]; then
    print_error "⚠️  ВНИМАНИЕ: Образ собран для ${IMAGE_ARCH}, а не для amd64!"
    print_error "Этот образ может НЕ работать на облачных серверах (Яндекс, AWS, GCP и т.д.)!"
    read -p "Продолжить публикацию? (y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

print_info "✓ Образ успешно собран для AMD64 (x86_64)"

# Проверка размера образа
IMAGE_SIZE=$(docker images ${FULL_IMAGE_NAME}:${VERSION} --format "{{.Size}}")
print_info "Размер образа: ${IMAGE_SIZE}"

# Опционально: тестовый запуск
read -p "Запустить тестовый контейнер? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    print_info "Запуск тестового контейнера..."
    docker run --rm -d \
        --name loyalty-bot-test \
        -p 8080:8080 \
        -e TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN:-test} \
        -e TELEGRAM_BOT_USERNAME=${TELEGRAM_BOT_USERNAME:-test} \
        ${FULL_IMAGE_NAME}:${VERSION}
    
    print_info "Контейнер запущен. Ожидание инициализации (10 сек)..."
    sleep 10
    
    print_info "Проверка health check..."
    if curl -f http://localhost:8080/actuator/health > /dev/null 2>&1; then
        print_info "✓ Health check прошел успешно!"
    else
        print_warning "Health check не прошел, но это может быть нормально без настроенного бота"
    fi
    
    print_info "Логи контейнера:"
    docker logs loyalty-bot-test | tail -20
    
    print_info "Останавливаем тестовый контейнер..."
    docker stop loyalty-bot-test
fi

# Публикация в Docker Hub
print_info "Шаг 2/3: Вход в Docker Hub..."
docker login

if [ $? -ne 0 ]; then
    print_error "Ошибка при входе в Docker Hub!"
    exit 1
fi

print_info "Шаг 3/3: Публикация образа в Docker Hub..."
print_info "Публикация ${FULL_IMAGE_NAME}:${VERSION}..."
docker push ${FULL_IMAGE_NAME}:${VERSION}

if [ $? -ne 0 ]; then
    print_error "Ошибка при публикации образа!"
    exit 1
fi

print_info "Публикация ${FULL_IMAGE_NAME}:latest..."
docker push ${FULL_IMAGE_NAME}:latest

if [ $? -ne 0 ]; then
    print_error "Ошибка при публикации образа!"
    exit 1
fi

# Итоговая информация
print_info "════════════════════════════════════════"
print_info "✓ Образ успешно опубликован!"
print_info "════════════════════════════════════════"
print_info ""
print_info "Образ доступен по адресу:"
print_info "  • ${FULL_IMAGE_NAME}:${VERSION}"
print_info "  • ${FULL_IMAGE_NAME}:latest"
print_info ""
print_info "Архитектура: AMD64 (совместим с Яндекс Облаком, AWS, GCP)"
print_info ""
print_info "════════ Использование в Яндекс Облаке ════════"
print_info ""
print_info "1. На виртуалке выполните:"
print_info ""
print_info "   # Загрузите образ"
print_info "   docker pull ${FULL_IMAGE_NAME}:latest"
print_info ""
print_info "   # Запустите контейнер"
print_info "   docker run -d \\"
print_info "     --name loyalty-bot \\"
print_info "     -p 8080:8080 \\"
print_info "     -e TELEGRAM_BOT_TOKEN=your_token \\"
print_info "     -e TELEGRAM_BOT_USERNAME=your_bot \\"
print_info "     -e ADMIN_SECRET_CODE=your_secret \\"
print_info "     -v \$(pwd)/data:/app/data \\"
print_info "     --restart unless-stopped \\"
print_info "     ${FULL_IMAGE_NAME}:latest"
print_info ""
print_info "2. Или через docker-compose.yml:"
print_info ""
print_info "   version: '3.8'"
print_info "   services:"
print_info "     app:"
print_info "       image: ${FULL_IMAGE_NAME}:latest"
print_info "       container_name: loyalty-bot"
print_info "       environment:"
print_info "         TELEGRAM_BOT_TOKEN: \${TELEGRAM_BOT_TOKEN}"
print_info "         TELEGRAM_BOT_USERNAME: \${TELEGRAM_BOT_USERNAME}"
print_info "         ADMIN_SECRET_CODE: \${ADMIN_SECRET_CODE}"
print_info "       ports:"
print_info "         - '8080:8080'"
print_info "       volumes:"
print_info "         - ./data:/app/data"
print_info "       restart: unless-stopped"
print_info ""
print_info "   docker-compose up -d"
print_info ""
print_info "Docker Hub: https://hub.docker.com/r/${DOCKER_USERNAME}/${IMAGE_NAME}"
print_info "════════════════════════════════════════"


