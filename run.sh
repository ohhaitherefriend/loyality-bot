#!/bin/bash

# Скрипт для запуска приложения

echo "🤖 Запуск Заботик..."

# Проверка наличия Java
if ! command -v java &> /dev/null; then
    echo "❌ Java не установлена. Установите Java 17 или выше."
    exit 1
fi

# Проверка наличия Maven
if ! command -v mvn &> /dev/null; then
    echo "❌ Maven не установлен. Установите Maven 3.6+."
    exit 1
fi

# Загрузка переменных окружения из .env файла, если он существует
if [ -f .env ]; then
    echo "📁 Загрузка переменных из .env файла..."
    export $(cat .env | grep -v '^#' | xargs)
else
    echo "⚠️  Файл .env не найден. Скопируйте .env.example в .env и заполните его."
    echo "   cp .env.example .env"
    exit 1
fi

# Проверка наличия токена бота
if [ -z "$TELEGRAM_BOT_TOKEN" ] || [ "$TELEGRAM_BOT_TOKEN" = "your_bot_token_here" ]; then
    echo "❌ TELEGRAM_BOT_TOKEN не установлен. Заполните файл .env"
    exit 1
fi

if [ -z "$TELEGRAM_BOT_USERNAME" ] || [ "$TELEGRAM_BOT_USERNAME" = "your_bot_username_here" ]; then
    echo "❌ TELEGRAM_BOT_USERNAME не установлен. Заполните файл .env"
    exit 1
fi

# Создание директории для базы данных
mkdir -p data

echo "🔨 Сборка приложения..."
mvn clean install -DskipTests

if [ $? -eq 0 ]; then
    echo "✅ Сборка успешна!"
    echo "🚀 Запуск приложения..."
    mvn spring-boot:run
else
    echo "❌ Ошибка при сборке приложения"
    exit 1
fi