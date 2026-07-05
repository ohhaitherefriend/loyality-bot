# Заботик — Админ-панель

Веб-интерфейс для управления Telegram-ботом программы лояльности.

## Возможности

- 🤖 **Подключение бота** — подключите своего Telegram-бота через BotFather token
- ⚙️ **Настройки магазина** — настройка штампов, скидок, Fast Checkout
- 🔗 **QR и ссылки** — генерация QR-кодов и deep-links для клиентов
- 📊 **Статус** — мониторинг состояния webhook и бота
- 📈 **Отчёты** — статистика по клиентам и транзакциям

## Технологический стек

- React 18 + TypeScript
- Vite
- TanStack Query (React Query)
- React Hook Form + Zod
- Tailwind CSS + shadcn/ui components
- Framer Motion
- qrcode.react

## Запуск

### Требования

- Node.js 18+
- npm или yarn
- Запущенный backend на http://localhost:8080 (или настроенный VITE_API_BASE_URL)

### Установка

```bash
# Установка зависимостей
npm install

# Запуск в режиме разработки
npm run dev

# Сборка для production
npm run build
```

### Переменные окружения

Скопируйте `env.example` в `.env` и настройте:

```bash
# API Base URL (для production)
VITE_API_BASE_URL=https://your-api-domain.com
```

В режиме разработки proxy настроен автоматически на localhost:8080.

## Структура проекта

```
src/
├── api/                    # API клиент и типы
│   ├── client.ts          # HTTP клиент с auth
│   └── types.ts           # TypeScript типы для API
├── components/
│   ├── ui/                # shadcn/ui компоненты
│   └── Layout.tsx         # Основной layout с навигацией
├── features/
│   ├── connect/           # Страница подключения бота
│   ├── settings/          # Настройки магазина
│   ├── links/             # QR-коды и ссылки
│   ├── status/            # Статус подключения
│   └── reports/           # Отчёты и статистика
├── lib/
│   ├── store.ts           # Zustand store
│   ├── utils.ts           # Утилиты
│   └── validators.ts      # Zod схемы валидации
├── App.tsx                # Роутинг
├── main.tsx               # Entry point
└── index.css              # Стили Tailwind
```

## API Endpoints

Админка использует следующие endpoints backend:

### Боты
- `POST /api/bots/connect` — подключение нового бота
- `GET /api/bots/{id}` — информация о боте
- `GET /api/bots` — список ботов
- `DELETE /api/bots/{id}` — отключение бота
- `POST /api/bots/{id}/webhook` — обновление webhook

### Настройки магазина
- `GET /api/shops/{shopId}/settings` — получение настроек
- `PUT /api/shops/{shopId}/settings` — обновление настроек

### Ссылки
- `GET /api/shops/{shopId}/deeplink?locationId=...` — получение deep-links

### Статистика
- `GET /api/stats` — общая статистика платформы
- `GET /api/shops/{shopId}/reports/weekly` — еженедельный отчёт (опционально)

## Авторизация

Админка поддерживает два режима авторизации:

1. **Bearer Token (JWT)** — токен передаётся в заголовке `Authorization: Bearer <token>`
2. **Cookie-based Session** — используется `credentials: 'include'`

При получении 401 происходит событие `auth:unauthorized` для redirect на login.

## Безопасность

- Токен бота никогда не сохраняется в localStorage
- Токен передаётся только на backend при подключении
- В localStorage сохраняются только идентификаторы (shopId, botInstanceId, botUsername)

## Разработка

```bash
# Запуск dev сервера
npm run dev

# Линтинг
npm run lint

# Сборка
npm run build

# Превью production build
npm run preview
```

## Интеграция с Backend

Если backend endpoints отличаются от предложенных, отредактируйте:

1. `src/api/client.ts` — методы API клиента
2. `src/api/types.ts` — типы данных

Все API вызовы централизованы в `api/client.ts` для простоты изменений.

## Лицензия

Private / Internal Use

