# rembg background removal service

Local FastAPI wrapper around [rembg](https://github.com/danielgatis/rembg) for product image background removal.

## Start

```bash
docker compose -f docker-compose.rembg.yml up --build
```

## Health check

```bash
curl http://localhost:7000/health
```

## Manual test

```bash
curl -X POST http://localhost:7000/remove \
  -F "image=@sample.jpg" \
  --output out.png
```

## Backend configuration

Set environment variables (do not commit real keys):

```bash
COMMERCE_BACKGROUND_REMOVAL_PROVIDER=rembg
REMBG_URL=http://localhost:7000/remove
REMBG_HEALTH_URL=http://localhost:7000/health
```
