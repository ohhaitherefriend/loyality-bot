# 🚀 Настройка Ngrok для Telegram Webhooks

Этот гайд описывает настройку ngrok для работы с Telegram webhook в режиме разработки.

## 📋 Оглавление

- [Что такое ngrok и зачем он нужен](#что-такое-ngrok-и-зачем-он-нужен)
- [Установка ngrok](#установка-ngrok)
- [Авторизация ngrok](#авторизация-ngrok)
- [Запуск ngrok](#запуск-ngrok)
- [Настройка webhook для ботов](#настройка-webhook-для-ботов)
- [Автоматизация](#автоматизация)
- [Troubleshooting](#troubleshooting)

---

## Что такое ngrok и зачем он нужен

**ngrok** — это сервис, который создаёт защищённый туннель между интернетом и вашим локальным сервером.

**Telegram Webhook** требует публичный HTTPS URL для отправки уведомлений о сообщениях. Без ngrok вам бы пришлось:
- Развернуть приложение на VPS/облаке
- Настроить SSL сертификат
- Настроить домен

С ngrok вы получаете публичный HTTPS URL за секунды, прямо на localhost.

### Архитектура

```
[Telegram] --> [ngrok URL] --> [ngrok tunnel] --> [localhost:8080]
                 |
                 v
   https://abc123.ngrok-free.app
```

---

## Установка ngrok

### Автоматическая установка

```bash
# Запустите скрипт установки
./scripts/setup-ngrok.sh
```

### Ручная установка

#### macOS (Homebrew)
```bash
brew install ngrok/ngrok/ngrok
```

#### macOS (без Homebrew)
```bash
# Apple Silicon (M1/M2/M3)
curl -L https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-darwin-arm64.zip -o ngrok.zip

# Intel
curl -L https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-darwin-amd64.zip -o ngrok.zip

unzip ngrok.zip
sudo mv ngrok /usr/local/bin/
```

#### Linux (apt)
```bash
curl -s https://ngrok-agent.s3.amazonaws.com/ngrok.asc | \
  sudo tee /etc/apt/trusted.gpg.d/ngrok.asc >/dev/null

echo "deb https://ngrok-agent.s3.amazonaws.com buster main" | \
  sudo tee /etc/apt/sources.list.d/ngrok.list

sudo apt update && sudo apt install ngrok
```

#### Проверка установки
```bash
ngrok version
# ngrok version 3.x.x
```

---

## Авторизация ngrok

Для стабильной работы **настоятельно рекомендуется** авторизоваться:

1. **Регистрация** — бесплатно на [ngrok.com](https://ngrok.com)

2. **Получите authtoken** — скопируйте с [dashboard.ngrok.com](https://dashboard.ngrok.com/get-started/your-authtoken)

3. **Добавьте токен**:
   ```bash
   ngrok config add-authtoken YOUR_AUTHTOKEN
   ```

### Преимущества авторизации

| Без авторизации | С авторизацией |
|-----------------|----------------|
| URL меняется при перезапуске | Стабильный URL (платный план) |
| Лимит 40 подключений/мин | Больше лимиты |
| Нет истории | История запросов в dashboard |

---

## Запуск ngrok

### Быстрый старт

```bash
# Убедитесь, что приложение запущено на порту 8080
./mvnw spring-boot:run &

# Запустите ngrok
./scripts/start-ngrok.sh
```

### Ручной запуск

```bash
# 1. Запустите приложение
./mvnw spring-boot:run

# 2. В другом терминале запустите ngrok
ngrok http 8080
```

### Что происходит при запуске

1. ngrok создаёт туннель к `localhost:8080`
2. Выдаёт публичный URL вида `https://abc123.ngrok-free.app`
3. Все запросы на этот URL проксируются на ваш localhost

### Панель ngrok

После запуска доступна веб-панель: **http://localhost:4040**

Здесь можно:
- Видеть все входящие запросы
- Инспектировать request/response
- Replay запросы для отладки

---

## Настройка webhook для ботов

### Single-tenant режим (один бот)

Если вы используете один бот с Long Polling, webhook не нужен — бот сам опрашивает Telegram.

Но если хотите использовать webhook:

```bash
# Получите URL ngrok
NGROK_URL=$(curl -s http://localhost:4040/api/tunnels | jq -r '.tunnels[0].public_url')

# Установите webhook для бота
curl -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
  -H "Content-Type: application/json" \
  -d "{\"url\": \"${NGROK_URL}/tg/webhook/1/your-secret\"}"
```

### Multi-tenant режим (BYOB — Bring Your Own Bot)

В BYOB режиме webhook устанавливается автоматически при подключении бота.

**Важно:** Установите `SERVER_BASE_URL` перед подключением ботов!

#### Вариант 1: Через переменную окружения

```bash
# Получите URL ngrok
NGROK_URL=$(curl -s http://localhost:4040/api/tunnels | jq -r '.tunnels[0].public_url')

# Экспортируйте переменную
export SERVER_BASE_URL=$NGROK_URL

# Перезапустите приложение
./mvnw spring-boot:run
```

#### Вариант 2: Через API (без перезапуска)

```bash
# Получите URL ngrok
NGROK_URL=$(curl -s http://localhost:4040/api/tunnels | jq -r '.tunnels[0].public_url')

# Обновите webhook для всех ботов
curl -X POST "http://localhost:8080/api/admin/webhooks/update-all" \
  -H "Content-Type: application/json" \
  -d "{\"baseUrl\": \"$NGROK_URL\"}"
```

#### Вариант 3: Через application.yml

```yaml
server:
  port: 8080
  base-url: https://abc123.ngrok-free.app  # ваш ngrok URL
```

### Проверка webhook

```bash
# Проверить статус webhook бота
curl http://localhost:8080/tg/webhook/health

# Проверить webhook конкретного бота
curl http://localhost:8080/tg/webhook/{botInstanceId}/status
```

---

## Автоматизация

### Скрипт start-ngrok.sh

Скрипт `./scripts/start-ngrok.sh` автоматически:

1. ✅ Проверяет установку ngrok
2. ✅ Проверяет авторизацию
3. ✅ Убивает предыдущий ngrok процесс
4. ✅ Запускает ngrok
5. ✅ Получает публичный URL
6. ✅ Обновляет webhook для всех ботов через API
7. ✅ Сохраняет URL в `/tmp/ngrok-url.txt`

```bash
# Запуск с портом по умолчанию (8080)
./scripts/start-ngrok.sh

# Запуск с кастомным портом
./scripts/start-ngrok.sh 9090
```

### Автозапуск при старте приложения

Для production-like разработки добавьте в `.bashrc` / `.zshrc`:

```bash
# Алиас для запуска проекта с ngrok
alias loyalty-dev='cd ~/IdeaProjects/loyalty-bot && ./scripts/start-ngrok.sh & ./mvnw spring-boot:run'
```

---

## Troubleshooting

### ❌ ngrok: command not found

```bash
# Проверьте PATH
echo $PATH

# Добавьте путь к ngrok
export PATH=$PATH:/usr/local/bin
```

### ❌ ERR_NGROK_105: session limit reached

Бесплатный план позволяет только 1 активную сессию.

```bash
# Убейте все процессы ngrok
pkill ngrok

# Запустите заново
./scripts/start-ngrok.sh
```

### ❌ Webhook не работает

1. **Проверьте, что приложение запущено:**
   ```bash
   curl http://localhost:8080/tg/webhook/health
   ```

2. **Проверьте ngrok туннель:**
   ```bash
   curl -s http://localhost:4040/api/tunnels | jq .
   ```

3. **Проверьте логи ngrok:**
   - Откройте http://localhost:4040
   - Смотрите входящие запросы

4. **Проверьте статус webhook в Telegram:**
   ```bash
   curl "https://api.telegram.org/bot${TOKEN}/getWebhookInfo" | jq .
   ```

### ❌ URL ngrok изменился

При перезапуске ngrok URL меняется. Нужно обновить webhook:

```bash
# Автоматически через скрипт
./scripts/start-ngrok.sh

# Или вручную
NGROK_URL=$(curl -s http://localhost:4040/api/tunnels | jq -r '.tunnels[0].public_url')
curl -X POST "http://localhost:8080/api/admin/webhooks/update-all" \
  -H "Content-Type: application/json" \
  -d "{\"baseUrl\": \"$NGROK_URL\"}"
```

### ❌ 502 Bad Gateway от ngrok

Приложение не запущено или упало. Проверьте:

```bash
# Логи приложения
tail -f nohup.out

# Или перезапустите
./mvnw spring-boot:run
```

---

## 📌 Полезные команды

```bash
# Получить текущий URL ngrok
curl -s http://localhost:4040/api/tunnels | jq -r '.tunnels[0].public_url'

# Или из файла (если использовали скрипт)
cat /tmp/ngrok-url.txt

# Убить ngrok
pkill ngrok

# Проверить логи ngrok
cat /tmp/ngrok.log

# Список всех ботов
curl http://localhost:8080/api/bots | jq .

# Статус webhook бота
curl http://localhost:8080/tg/webhook/1/status | jq .
```

---

## 🔐 Production vs Development

| Аспект | Development (ngrok) | Production |
|--------|---------------------|------------|
| URL | Временный | Постоянный домен |
| SSL | Автоматический | Let's Encrypt / CloudFlare |
| Стоимость | Бесплатно | Зависит от хостинга |
| Надёжность | Для тестов | 99.9% uptime |

**Для production** рекомендуется:
1. Развернуть на VPS (DigitalOcean, Hetzner, etc.)
2. Настроить Nginx reverse proxy
3. Получить SSL через Let's Encrypt
4. Использовать постоянный домен

---

## 📞 Поддержка

- Telegram Webhooks: https://core.telegram.org/bots/webhooks
- Ngrok Documentation: https://ngrok.com/docs
