#!/bin/bash

# ============================================================
# Заботик - Ngrok Startup Script
# ============================================================
#
# Этот скрипт запускает ngrok и автоматически обновляет
# webhook для всех подключённых Telegram ботов.
#
# Использование:
#   ./scripts/start-ngrok.sh [port]
#
# Примеры:
#   ./scripts/start-ngrok.sh        # порт 8080 по умолчанию
#   ./scripts/start-ngrok.sh 8081   # кастомный порт
# ============================================================

set -e

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Порт приложения (по умолчанию 8080)
PORT=${1:-8080}

# Функция для красивого вывода
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[OK]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Проверка установки ngrok
check_ngrok() {
    if ! command -v ngrok &> /dev/null; then
        log_error "ngrok не установлен!"
        echo ""
        echo "Установите ngrok одним из способов:"
        echo ""
        echo "  macOS (Homebrew):"
        echo "    brew install ngrok/ngrok/ngrok"
        echo ""
        echo "  Linux (apt):"
        echo "    curl -s https://ngrok-agent.s3.amazonaws.com/ngrok.asc | sudo tee /etc/apt/trusted.gpg.d/ngrok.asc >/dev/null"
        echo "    echo 'deb https://ngrok-agent.s3.amazonaws.com buster main' | sudo tee /etc/apt/sources.list.d/ngrok.list"
        echo "    sudo apt update && sudo apt install ngrok"
        echo ""
        echo "  Или скачайте с https://ngrok.com/download"
        echo ""
        exit 1
    fi
    log_success "ngrok найден: $(which ngrok)"
}

# Проверка авторизации ngrok
check_ngrok_auth() {
    if ! ngrok config check &> /dev/null; then
        log_warn "ngrok не авторизован"
        echo ""
        echo "Для авторизации выполните:"
        echo "  1. Зарегистрируйтесь на https://ngrok.com"
        echo "  2. Скопируйте authtoken из https://dashboard.ngrok.com/get-started/your-authtoken"
        echo "  3. Выполните: ngrok config add-authtoken YOUR_TOKEN"
        echo ""
        read -p "Хотите продолжить без авторизации? (y/n): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    else
        log_success "ngrok авторизован"
    fi
}

# Убить предыдущий процесс ngrok
kill_existing_ngrok() {
    if pgrep -x "ngrok" > /dev/null; then
        log_warn "Найден запущенный ngrok, останавливаем..."
        pkill -x ngrok 2>/dev/null || true
        sleep 2
    fi
}

# Запуск ngrok в фоне
start_ngrok() {
    log_info "Запускаем ngrok на порту $PORT..."
    
    # Запускаем ngrok в фоне
    ngrok http $PORT --log=stdout > /tmp/ngrok.log 2>&1 &
    NGROK_PID=$!
    
    # Даём время на запуск
    sleep 3
    
    # Проверяем, что ngrok запустился
    if ! kill -0 $NGROK_PID 2>/dev/null; then
        log_error "ngrok не запустился. Проверьте логи: /tmp/ngrok.log"
        cat /tmp/ngrok.log
        exit 1
    fi
    
    log_success "ngrok запущен (PID: $NGROK_PID)"
}

# Получение публичного URL
get_ngrok_url() {
    log_info "Получаем публичный URL..."
    
    # Пробуем получить URL через API ngrok
    for i in {1..10}; do
        NGROK_URL=$(curl -s http://localhost:4040/api/tunnels 2>/dev/null | grep -o '"public_url":"https://[^"]*' | cut -d'"' -f4 | head -1)
        
        if [ -n "$NGROK_URL" ]; then
            break
        fi
        
        sleep 1
    done
    
    if [ -z "$NGROK_URL" ]; then
        log_error "Не удалось получить URL ngrok"
        log_info "Проверьте http://localhost:4040 в браузере"
        exit 1
    fi
    
    log_success "Публичный URL: $NGROK_URL"
    echo ""
}

# Обновление webhook через API приложения
update_webhooks() {
    log_info "Обновляем webhook для всех ботов..."
    
    # Вызываем эндпоинт обновления webhook
    RESPONSE=$(curl -s -X POST "http://localhost:$PORT/api/admin/webhooks/update-all" \
        -H "Content-Type: application/json" \
        -d "{\"baseUrl\": \"$NGROK_URL\"}" \
        2>/dev/null || echo "ERROR")
    
    if [[ "$RESPONSE" == *"ERROR"* ]] || [[ "$RESPONSE" == *"error"* ]]; then
        log_warn "Не удалось автоматически обновить webhooks"
        echo ""
        echo "Вручную установите переменную окружения и перезапустите приложение:"
        echo ""
        echo "  export SERVER_BASE_URL=$NGROK_URL"
        echo ""
        echo "Или добавьте в application.yml:"
        echo ""
        echo "  server:"
        echo "    base-url: $NGROK_URL"
        echo ""
    else
        log_success "Webhooks обновлены!"
        echo "$RESPONSE"
    fi
}

# Вывод информации
print_info() {
    echo ""
    echo "============================================================"
    echo -e "${GREEN}🚀 NGROK ЗАПУЩЕН!${NC}"
    echo "============================================================"
    echo ""
    echo "📡 Публичный URL:  $NGROK_URL"
    echo "🔗 Webhook path:   $NGROK_URL/tg/webhook/{botId}/{secret}"
    echo "📊 Ngrok панель:   http://localhost:4040"
    echo "🔧 Локальный порт: $PORT"
    echo ""
    echo "============================================================"
    echo ""
    echo "Для остановки ngrok нажмите Ctrl+C или выполните: pkill ngrok"
    echo ""
}

# Сохранение URL в файл
save_url() {
    echo "$NGROK_URL" > /tmp/ngrok-url.txt
    log_info "URL сохранён в /tmp/ngrok-url.txt"
}

# Экспорт переменной окружения
export_env() {
    echo ""
    echo "Для использования в текущей сессии терминала выполните:"
    echo ""
    echo "  export SERVER_BASE_URL=$NGROK_URL"
    echo ""
}

# Обработка Ctrl+C
cleanup() {
    echo ""
    log_info "Останавливаем ngrok..."
    pkill -x ngrok 2>/dev/null || true
    log_success "ngrok остановлен"
    exit 0
}

trap cleanup SIGINT SIGTERM

# ============================================================
# MAIN
# ============================================================

echo ""
echo "============================================================"
echo "  Заботик - Ngrok Setup"
echo "============================================================"
echo ""

check_ngrok
check_ngrok_auth
kill_existing_ngrok
start_ngrok
get_ngrok_url
save_url
update_webhooks
print_info
export_env

# Ждём завершения
log_info "Ngrok работает. Нажмите Ctrl+C для остановки."
wait $NGROK_PID
