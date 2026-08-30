# Image pipeline — local development

## 1. Start rembg service

```bash
docker compose -f docker-compose.rembg.yml up --build
```

Health check:

```bash
curl http://localhost:7000/health
```

## 2. Configure backend env (`.env`, not committed)

```bash
COMMERCE_IMAGE_SEARCH_ENABLED=true
COMMERCE_IMAGE_SEARCH_PROVIDER=brave
BRAVE_SEARCH_API_KEY=your_key

COMMERCE_IMAGE_RANKER=deepseek
DEEPSEEK_API_KEY=your_key

COMMERCE_BACKGROUND_REMOVAL_PROVIDER=rembg
REMBG_URL=http://localhost:7000/remove
REMBG_HEALTH_URL=http://localhost:7000/health
```

## 3. Restart backend with env loaded

```bash
export $(grep -v '^#' .env | xargs)
mvn spring-boot:run
```

## 4. Admin workflow

1. Open **Каталог** → check provider status panel
2. Click **Поиск изображений (страница)** for bulk search
3. Open product → review normalized preview → **Одобрить**
4. Publish product (`visible=true`) → check Telegram catalog

## 5. API

- `GET /api/shops/{shopId}/products/images/search-status`
- `POST /api/shops/{shopId}/products/images/search-bulk`

Never commit real API keys.
