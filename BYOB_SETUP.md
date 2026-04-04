# BYOB (Bring Your Own Bot) — Multi-tenant Telegram Loyalty Platform

## Обзор

BYOB позволяет подключать несколько Telegram-ботов к одному Spring Boot приложению. Каждый бизнес получает свой бот с уникальными настройками программы лояльности.

## Архитектура

```
┌─────────────────────────────────────────────────────────────┐
│                     Spring Boot Application                  │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  /tg/webhook/{botId}/{secret}  →  TelegramWebhookController │
│                                          │                  │
│                                          ▼                  │
│                               TelegramUpdateRouter          │
│                                          │                  │
│                                          ▼                  │
│                               LoyaltyBotService             │
│                              (с TelegramContext)            │
│                                                             │
│  /api/bots/connect           →  AdminApiController          │
│  /api/shops/{id}/settings    →  AdminApiController          │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                       База данных (multi-tenant)            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ BotInstance │  │ ShopSettings│  │    Users    │         │
│  │  (shopId)   │  │  (shopId)   │  │  (shopId)   │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
└─────────────────────────────────────────────────────────────┘
```

## Быстрый старт

### 1. Конфигурация сервера

```yaml
# application.yml
server:
  port: 8080
  base-url: https://your-domain.com  # ОБЯЗАТЕЛЬНО для webhook!

security:
  encryption:
    enabled: true
    key: ${ENCRYPTION_KEY}  # Сгенерируйте: TokenEncryptionService.generateKey()
```

### 2. Генерация ключа шифрования

```java
// В любом месте кода или через main:
String key = TokenEncryptionService.generateKey();
System.out.println("ENCRYPTION_KEY=" + key);
```

### 3. Подключение бота через API

```bash
curl -X POST https://your-domain.com/api/bots/connect \
  -H "Content-Type: application/json" \
  -d '{
    "botToken": "YOUR_BOT_TOKEN_HERE",
    "businessType": "COFFEE",
    "businessName": "Моя Кофейня",
    "ownerEmail": "owner@example.com"
  }'
```

**Ответ:**
```json
{
  "success": true,
  "shopId": "550e8400-e29b-41d4-a716-446655440000",
  "botUsername": "MyCoffeeBot",
  "buyDeepLink": "https://t.me/MyCoffeeBot?start=buy_550e8400-e29b-41d4-a716-446655440000_default",
  "adminDeepLink": "https://t.me/MyCoffeeBot?start=admin_550e8400-e29b-41d4-a716-446655440000",
  "botInstanceId": 1
}
```

## API Endpoints

### Боты

| Method | Endpoint | Описание |
|--------|----------|----------|
| POST | `/api/bots/connect` | Подключить новый бот |
| GET | `/api/bots` | Список всех ботов |
| GET | `/api/bots/{id}` | Информация о боте |
| DELETE | `/api/bots/{id}` | Отключить бот |
| POST | `/api/bots/{id}/webhook` | Обновить webhook URL |

### Настройки магазина

| Method | Endpoint | Описание |
|--------|----------|----------|
| GET | `/api/shops/{shopId}/settings` | Получить настройки |
| PUT | `/api/shops/{shopId}/settings` | Обновить настройки |
| GET | `/api/shops/{shopId}/deeplink` | Получить QR/deep-links |

### Webhook (для Telegram)

| Method | Endpoint | Описание |
|--------|----------|----------|
| POST | `/tg/webhook/{botId}/{secret}` | Приём updates от Telegram |
| GET | `/tg/webhook/health` | Health check |

## Типы бизнеса

При подключении бота выбирается тип бизнеса, который определяет начальные настройки:

### COFFEE (Кофейня)
- ✅ Fast Checkout
- ✅ Штампы (10 штампов = бесплатный напиток)
- ❌ Накопительные скидки

### RETAIL (Розничный магазин)
- ❌ Fast Checkout
- ❌ Штампы
- ✅ Накопительные скидки (5%/7%/10%)

### SERVICE (Услуги)
- ✅ Fast Checkout (баллы)
- ❌ Штампы
- ❌ Накопительные скидки

### HYBRID (Гибридный)
- ✅ Fast Checkout
- ✅ Штампы
- ✅ Накопительные скидки

## Настройки магазина

```json
{
  "shopName": "Моя Кофейня",
  "fastCheckoutEnabled": true,
  "stampsEnabled": true,
  "stampsRequiredForReward": 10,
  "rewardTitle": "Бесплатный капучино",
  "discountTiersEnabled": false,
  "discountTier1Amount": 20000,
  "discountTier1Percent": 5,
  "telegramChannelUrl": "https://t.me/mycoffeenews"
}
```

## Deep Links

### QR для покупки
```
https://t.me/{botUsername}?start=buy_{shopId}_{locationId}
```

Клиент сканирует QR → открывает бот → автоматически генерируется код покупки.

### Deep Link для админа
```
https://t.me/{botUsername}?start=admin_{shopId}
```

## Безопасность

### Шифрование токенов
Все bot tokens хранятся в БД в зашифрованном виде (AES-256-GCM).

### Валидация webhook
Каждый webhook endpoint защищён уникальным secret:
```
/tg/webhook/{botInstanceId}/{webhookSecret}
```

### Рекомендации для production
1. Используйте HTTPS обязательно
2. Храните `ENCRYPTION_KEY` в секретах (Vault, Kubernetes Secrets)
3. Настройте rate limiting на nginx/cloudflare
4. Включите Flyway миграции вместо ddl-auto

## Миграция с single-tenant

Если у вас уже есть данные:

1. Включите Flyway:
```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
```

2. Запустите миграцию V7:
```sql
-- Установите default shopId для существующих данных
UPDATE users SET shop_id = 'default' WHERE shop_id IS NULL;
UPDATE shop_settings SET shop_id = 'default' WHERE shop_id IS NULL;
```

3. Подключите существующий бот через API.

## Мониторинг

### Статус бота
```bash
curl https://your-domain.com/tg/webhook/{botId}/status
```

### Webhook info
```bash
curl https://your-domain.com/api/bots/{botId}
```

## Troubleshooting

### Webhook не работает
1. Проверьте `server.base-url` в конфиге
2. Убедитесь что HTTPS настроен корректно
3. Проверьте статус бота: `GET /api/bots/{id}`

### Ошибка шифрования
1. Проверьте что `ENCRYPTION_KEY` установлен
2. Ключ должен быть Base64-encoded 32 байта

### Бот не отвечает
1. Проверьте логи: `grep "shopId=" logs/app.log`
2. Проверьте `lastWebhookAt` в статусе бота
3. Проверьте `pendingUpdateCount` через API

## Примеры использования

### JavaScript (Web Admin)
```javascript
// Подключение бота
const response = await fetch('/api/bots/connect', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    botToken: prompt('Bot Token:'),
    businessType: 'COFFEE',
    businessName: 'My Coffee Shop'
  })
});

const data = await response.json();
if (data.success) {
  // Показываем QR код
  generateQR(data.buyDeepLink);
}
```

### Обновление настроек
```javascript
await fetch(`/api/shops/${shopId}/settings`, {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    stampsRequiredForReward: 8,
    rewardTitle: 'Бесплатный латте'
  })
});
```

