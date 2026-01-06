#!/bin/bash

# Скрипт для сборки Docker образа для Яндекс Облака (AMD64)
set -e

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Конфигурация
IMAGE_NAME="loyalty-bot"
VERSION="1.0.0"

# Функции для вывода
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_step() {
    echo -e "${BLUE}▶${NC} $1"
}

# Проверка Docker
print_info "Проверка Docker..."
if ! docker info > /dev/null 2>&1; then
    print_error "Docker daemon не запущен!"
    exit 1
fi

# Определяем текущую архитектуру
CURRENT_ARCH=$(uname -m)
print_info "Текущая архитектура: ${CURRENT_ARCH}"

if [[ "$CURRENT_ARCH" == "arm64" ]]; then
    print_warning "⚠️  Вы на ARM64 (M1/M2 Mac), но собираем для AMD64 (x86_64) для Яндекс Облака"
fi

# Выбор: Yandex Container Registry или Docker Hub
echo ""
print_step "Выберите реестр для публикации:"
echo "  1) Yandex Container Registry (рекомендуется для Яндекс Облака)"
echo "  2) Docker Hub"
echo "  3) Только локальная сборка (без публикации)"
read -p "Выбор (1/2/3): " REGISTRY_CHOICE

case $REGISTRY_CHOICE in
    1)
        print_info "Используем Yandex Container Registry"
        
        # Проверка yc CLI
        if ! command -v yc &> /dev/null; then
            print_error "Yandex Cloud CLI не установлен!"
            print_info "Установите: curl https://storage.yandexcloud.net/yandexcloud-yc/install.sh | bash"
            exit 1
        fi
        
        # Получаем список реестров
        print_info "Получение списка реестров..."
        REGISTRIES=$(yc container registry list --format json 2>/dev/null)
        
        if [ -z "$REGISTRIES" ] || [ "$REGISTRIES" == "[]" ]; then
            print_warning "Реестры не найдены. Создайте реестр:"
            print_info "yc container registry create --name loyalty-bot-registry"
            exit 1
        fi
        
        # Показываем реестры
        echo "$REGISTRIES" | jq -r '.[] | "\(.id) - \(.name)"'
        echo ""
        read -p "Введите ID реестра: " REGISTRY_ID
        
        if [ -z "$REGISTRY_ID" ]; then
            print_error "ID реестра не может быть пустым!"
            exit 1
        fi
        
        FULL_IMAGE_NAME="cr.yandex/${REGISTRY_ID}/${IMAGE_NAME}"
        USE_REGISTRY="yandex"
        ;;
    2)
        print_info "Используем Docker Hub"
        read -p "Введите ваш Docker Hub username: " DOCKER_USERNAME
        
        if [ -z "$DOCKER_USERNAME" ]; then
            print_error "Username не может быть пустым!"
            exit 1
        fi
        
        FULL_IMAGE_NAME="${DOCKER_USERNAME}/${IMAGE_NAME}"
        USE_REGISTRY="dockerhub"
        ;;
    3)
        print_info "Локальная сборка без публикации"
        FULL_IMAGE_NAME="${IMAGE_NAME}"
        USE_REGISTRY="none"
        ;;
    *)
        print_error "Неверный выбор!"
        exit 1
        ;;
esac

print_info "Образ: ${FULL_IMAGE_NAME}:${VERSION}"
print_info "Также будет создан тег: ${FULL_IMAGE_NAME}:latest"

echo ""
read -p "Продолжить? (y/n) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    print_warning "Отменено пользователем"
    exit 0
fi

# ========================================
# Сборка образа для AMD64 (x86_64)
# ========================================
print_step "Шаг 1/3: Сборка Docker образа для AMD64 архитектуры..."
print_warning "Это может занять 5-10 минут (особенно первый раз)..."

# Используем buildx для кросс-платформенной сборки
docker buildx version > /dev/null 2>&1
if [ $? -eq 0 ]; then
    print_info "Используем docker buildx для оптимальной сборки"
    
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

print_info "✓ Образ успешно собран для AMD64 (x86_64)"

# Проверка архитектуры собранного образа
print_info "Проверка архитектуры образа..."
IMAGE_ARCH=$(docker inspect ${FULL_IMAGE_NAME}:${VERSION} --format='{{.Architecture}}')
print_info "Архитектура образа: ${IMAGE_ARCH}"

if [ "$IMAGE_ARCH" != "amd64" ]; then
    print_error "⚠️  ВНИМАНИЕ: Образ собран для ${IMAGE_ARCH}, а не для amd64!"
    print_error "Этот образ НЕ будет работать на виртуалках в Яндекс Облаке!"
    exit 1
fi

print_info "✓ Архитектура корректна (amd64)"

# Проверка размера образа
IMAGE_SIZE=$(docker images ${FULL_IMAGE_NAME}:${VERSION} --format "{{.Size}}")
print_info "Размер образа: ${IMAGE_SIZE}"

# ========================================
# Публикация образа
# ========================================
if [ "$USE_REGISTRY" == "none" ]; then
    print_info "════════════════════════════════════════"
    print_info "✓ Локальная сборка завершена!"
    print_info "════════════════════════════════════════"
    print_info "Образ: ${FULL_IMAGE_NAME}:${VERSION}"
    print_info "Архитектура: amd64 (совместим с Яндекс Облаком)"
    exit 0
fi

# Настройка аутентификации
if [ "$USE_REGISTRY" == "yandex" ]; then
    print_step "Шаг 2/3: Настройка аутентификации Yandex Container Registry..."
    yc container registry configure-docker
    
    if [ $? -ne 0 ]; then
        print_error "Ошибка при настройке аутентификации!"
        exit 1
    fi
elif [ "$USE_REGISTRY" == "dockerhub" ]; then
    print_step "Шаг 2/3: Вход в Docker Hub..."
    docker login
    
    if [ $? -ne 0 ]; then
        print_error "Ошибка при входе в Docker Hub!"
        exit 1
    fi
fi

# Публикация
print_step "Шаг 3/3: Публикация образа..."
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

# ========================================
# Итоговая информация
# ========================================
print_info "════════════════════════════════════════"
print_info "✓ Образ успешно опубликован!"
print_info "════════════════════════════════════════"
print_info ""
print_info "Образ доступен по адресу:"
print_info "  • ${FULL_IMAGE_NAME}:${VERSION}"
print_info "  • ${FULL_IMAGE_NAME}:latest"
print_info ""
print_info "Архитектура: amd64 (совместим с Яндекс Облаком)"
print_info ""

if [ "$USE_REGISTRY" == "yandex" ]; then
    print_info "════════ Для использования в Яндекс Облаке ════════"
    print_info ""
    print_info "1. На виртуалке в Яндекс Облаке:"
    print_info "   yc container registry configure-docker"
    print_info "   docker pull ${FULL_IMAGE_NAME}:latest"
    print_info ""
    print_info "2. Или в docker-compose.yml:"
    print_info "   image: ${FULL_IMAGE_NAME}:latest"
    print_info ""
    print_info "3. Или в Serverless Container:"
    print_info "   yc serverless container revision deploy \\"
    print_info "     --container-name loyalty-bot \\"
    print_info "     --image ${FULL_IMAGE_NAME}:latest \\"
    print_info "     --cores 1 --memory 512MB"
elif [ "$USE_REGISTRY" == "dockerhub" ]; then
    print_info "════════ Для использования на сервере ════════"
    print_info ""
    print_info "docker pull ${FULL_IMAGE_NAME}:latest"
    print_info ""
    print_info "docker run -d \\"
    print_info "  --name loyalty-bot \\"
    print_info "  -p 8080:8080 \\"
    print_info "  -e TELEGRAM_BOT_TOKEN=your_token \\"
    print_info "  -e TELEGRAM_BOT_USERNAME=your_username \\"
    print_info "  ${FULL_IMAGE_NAME}:latest"
fi

print_info "════════════════════════════════════════"


