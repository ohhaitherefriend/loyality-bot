#!/bin/bash

# ============================================================
# Заботик - Ngrok Installation Script
# ============================================================
#
# Этот скрипт устанавливает и настраивает ngrok
#
# Использование:
#   ./scripts/setup-ngrok.sh
# ============================================================

set -e

# Цвета
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

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

echo ""
echo "============================================================"
echo "  Ngrok Installation Script"
echo "============================================================"
echo ""

# Определяем ОС
OS=$(uname -s)
ARCH=$(uname -m)

log_info "Определена ОС: $OS ($ARCH)"

# Проверяем, установлен ли уже ngrok
if command -v ngrok &> /dev/null; then
    log_success "ngrok уже установлен: $(ngrok version)"
    echo ""
    read -p "Хотите переустановить? (y/n): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        log_info "Установка пропущена"
        exit 0
    fi
fi

# Установка в зависимости от ОС
case "$OS" in
    Darwin)
        log_info "Установка для macOS..."
        
        # Проверяем Homebrew
        if command -v brew &> /dev/null; then
            log_info "Используем Homebrew..."
            brew install ngrok/ngrok/ngrok
        else
            log_warn "Homebrew не найден. Устанавливаем вручную..."
            
            # Определяем архитектуру
            if [ "$ARCH" = "arm64" ]; then
                NGROK_URL="https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-darwin-arm64.zip"
            else
                NGROK_URL="https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-darwin-amd64.zip"
            fi
            
            curl -L $NGROK_URL -o /tmp/ngrok.zip
            unzip -o /tmp/ngrok.zip -d /tmp
            sudo mv /tmp/ngrok /usr/local/bin/ngrok
            rm /tmp/ngrok.zip
        fi
        ;;
    
    Linux)
        log_info "Установка для Linux..."
        
        # Проверяем apt
        if command -v apt &> /dev/null; then
            curl -s https://ngrok-agent.s3.amazonaws.com/ngrok.asc | sudo tee /etc/apt/trusted.gpg.d/ngrok.asc >/dev/null
            echo "deb https://ngrok-agent.s3.amazonaws.com buster main" | sudo tee /etc/apt/sources.list.d/ngrok.list
            sudo apt update && sudo apt install ngrok
        else
            log_warn "apt не найден. Устанавливаем вручную..."
            
            if [ "$ARCH" = "x86_64" ]; then
                NGROK_URL="https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-amd64.tgz"
            elif [ "$ARCH" = "aarch64" ]; then
                NGROK_URL="https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-arm64.tgz"
            else
                NGROK_URL="https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-386.tgz"
            fi
            
            curl -L $NGROK_URL -o /tmp/ngrok.tgz
            tar -xzf /tmp/ngrok.tgz -C /tmp
            sudo mv /tmp/ngrok /usr/local/bin/ngrok
            rm /tmp/ngrok.tgz
        fi
        ;;
    
    *)
        log_error "Неподдерживаемая ОС: $OS"
        echo "Пожалуйста, установите ngrok вручную: https://ngrok.com/download"
        exit 1
        ;;
esac

# Проверяем установку
if command -v ngrok &> /dev/null; then
    log_success "ngrok успешно установлен: $(ngrok version)"
else
    log_error "Установка не удалась"
    exit 1
fi

echo ""
echo "============================================================"
echo "  Настройка авторизации"
echo "============================================================"
echo ""
echo "Для полноценной работы ngrok требуется авторизация:"
echo ""
echo "1. Зарегистрируйтесь на https://ngrok.com (бесплатно)"
echo "2. Скопируйте токен с https://dashboard.ngrok.com/get-started/your-authtoken"
echo "3. Выполните команду:"
echo ""
echo "   ngrok config add-authtoken YOUR_TOKEN_HERE"
echo ""
echo "После этого запустите ngrok командой:"
echo ""
echo "   ./scripts/start-ngrok.sh"
echo ""
echo "============================================================"
