#!/usr/bin/env bash
# Lightweight prod deploy: build locally, copy JAR + frontend dist into running containers.
# Does NOT run mvn inside Docker on the server (avoids OOM on 2GB VM).
set -euo pipefail

SERVER="${DEPLOY_SERVER:-ohhaithere@89.169.138.246}"
SSH_KEY="${DEPLOY_SSH_KEY:-$HOME/.ssh/yandex_plstk_bot}"
SHOP_ID="${DEPLOY_SHOP_ID:-4552be5d-870e-4b94-a706-baafff3290a0}"
REMOTE_DIR="${DEPLOY_REMOTE_DIR:-~/loyalty-bot}"

SSH=(ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=30 "$SERVER")
SCP=(scp -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=30)

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/target/loyalty-bot-1.0.0.jar"
DIST="$ROOT/admin-panel/dist"

echo ">>> Building backend..."
(cd "$ROOT" && mvn -q package -DskipTests)

echo ">>> Building frontend..."
(cd "$ROOT/admin-panel" && npm run build --silent)

echo ">>> Uploading artifacts..."
"${SCP[@]}" "$JAR" "$SERVER:$REMOTE_DIR/deploy.jar"
"${SCP[@]}" "$ROOT/admin-panel/nginx.conf" "$SERVER:$REMOTE_DIR/admin-panel/nginx.conf"
rsync -az --delete -e "ssh -i $SSH_KEY -o BatchMode=yes" "$DIST/" "$SERVER:$REMOTE_DIR/frontend-dist/"

echo ">>> Applying DB migration + env (idempotent)..."
"${SSH[@]}" bash -s <<'REMOTE'
set -euo pipefail
cd ~/loyalty-bot
grep -q COMMERCE_MINI_APP_ENABLED .env || cat >> .env <<'EOF'

# Telegram Mini App storefront
COMMERCE_MINI_APP_ENABLED=true
COMMERCE_MINI_APP_PUBLIC_URL=https://za-botik.ru
COMMERCE_MINI_APP_REQUIRE_TELEGRAM_AUTH=true
COMMERCE_MINI_APP_INIT_DATA_MAX_AGE_SECONDS=86400
EOF
docker exec loyalty-db psql -U postgres -d loyalty_db -c \
  "ALTER TABLE customer_orders ADD COLUMN IF NOT EXISTS source VARCHAR(32) DEFAULT 'BOT';"
REMOTE

echo ">>> Deploying into containers..."
"${SSH[@]}" bash -s <<'REMOTE'
set -euo pipefail
cd ~/loyalty-bot

# Copy artifacts into running containers (do not force-recreate: that resets the image layer).
docker cp deploy.jar loyalty-backend:/app/app.jar
docker cp frontend-dist/. loyalty-frontend:/usr/share/nginx/html/

if [ -f admin-panel/nginx.conf ]; then
  docker cp admin-panel/nginx.conf loyalty-frontend:/etc/nginx/conf.d/default.conf
  docker exec loyalty-frontend nginx -t
  docker exec loyalty-frontend nginx -s reload
fi

docker restart loyalty-backend
docker restart loyalty-frontend

echo "waiting backend..."
for i in $(seq 1 24); do
  if curl -sf http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then break; fi
  sleep 5
done

curl -sf http://127.0.0.1:8080/actuator/health; echo
curl -s -o /dev/null -w "storefront_settings:%{http_code}\n" \
  "http://127.0.0.1:8080/api/storefront/4552be5d-870e-4b94-a706-baafff3290a0/settings"
curl -s -o /dev/null -w "store_route:%{http_code}\n" \
  "http://127.0.0.1:3000/store/4552be5d-870e-4b94-a706-baafff3290a0"
docker ps --format 'table {{.Names}}\t{{.Status}}' | head -5
REMOTE

echo ">>> Done. Test: https://za-botik.ru/store/$SHOP_ID"
