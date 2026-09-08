-- V7: Multi-tenant support for BYOB (Bring Your Own Bot)
-- Добавляет поддержку нескольких ботов/магазинов в одном приложении

-- ========== 1. Создаём таблицу bot_instances ==========

CREATE TABLE IF NOT EXISTS bot_instances (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL UNIQUE,
    bot_token VARCHAR(512) NOT NULL,
    bot_username VARCHAR(64) NOT NULL,
    telegram_bot_id BIGINT NOT NULL,
    webhook_secret VARCHAR(64) NOT NULL,
    webhook_url VARCHAR(512),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    business_type VARCHAR(20) NOT NULL DEFAULT 'COFFEE',
    business_name VARCHAR(255),
    owner_email VARCHAR(255),
    owner_chat_id BIGINT,
    last_webhook_at TIMESTAMP,
    updates_processed BIGINT DEFAULT 0,
    last_error VARCHAR(1024),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bot_instance_shop_id ON bot_instances(shop_id);
CREATE INDEX IF NOT EXISTS idx_bot_instance_bot_username ON bot_instances(bot_username);
CREATE INDEX IF NOT EXISTS idx_bot_instance_telegram_bot_id ON bot_instances(telegram_bot_id);

-- ========== 2. Добавляем shop_id в существующие таблицы ==========

-- Users
ALTER TABLE users ADD COLUMN IF NOT EXISTS shop_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_user_shop_id ON users(shop_id);
CREATE INDEX IF NOT EXISTS idx_user_chat_id_shop_id ON users(chat_id, shop_id);

-- Убираем старый unique constraint на chat_id (если есть)
-- ALTER TABLE users DROP CONSTRAINT IF EXISTS users_chat_id_key;

-- ShopSettings
ALTER TABLE shop_settings ADD COLUMN IF NOT EXISTS shop_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_shop_settings_shop_id ON shop_settings(shop_id);

-- PurchaseCodes
ALTER TABLE purchase_codes ADD COLUMN IF NOT EXISTS shop_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_purchase_codes_shop_id ON purchase_codes(shop_id);

-- Transactions
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS shop_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_transactions_shop_id ON transactions(shop_id);

-- DiscountCodes
ALTER TABLE discount_codes ADD COLUMN IF NOT EXISTS shop_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_discount_codes_shop_id ON discount_codes(shop_id);

-- Promotions
ALTER TABLE promotions ADD COLUMN IF NOT EXISTS shop_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_promotions_shop_id ON promotions(shop_id);

-- StampWallets
ALTER TABLE stamp_wallets ADD COLUMN IF NOT EXISTS shop_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_stamp_wallets_shop_id ON stamp_wallets(shop_id);

-- RedeemCodes
ALTER TABLE redeem_codes ADD COLUMN IF NOT EXISTS shop_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_redeem_codes_shop_id ON redeem_codes(shop_id);

-- ClientNotes
ALTER TABLE client_notes ADD COLUMN IF NOT EXISTS shop_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_client_notes_shop_id ON client_notes(shop_id);

-- CustomerAchievements
ALTER TABLE customer_achievements ADD COLUMN IF NOT EXISTS shop_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_customer_achievements_shop_id ON customer_achievements(shop_id);

-- CustomerBadges
ALTER TABLE customer_badges ADD COLUMN IF NOT EXISTS shop_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_customer_badges_shop_id ON customer_badges(shop_id);

-- OwnerSignals
ALTER TABLE owner_signals ADD COLUMN IF NOT EXISTS shop_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_owner_signals_shop_id ON owner_signals(shop_id);

-- MessageLogs
ALTER TABLE message_logs ADD COLUMN IF NOT EXISTS shop_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_message_logs_shop_id ON message_logs(shop_id);

-- ========== 3. Миграция данных (если есть) ==========
-- Для существующих данных устанавливаем default shop_id
-- UPDATE users SET shop_id = 'default' WHERE shop_id IS NULL;
-- UPDATE shop_settings SET shop_id = 'default' WHERE shop_id IS NULL;
-- ... и т.д.

-- ========== 4. Комментарий ==========
-- После миграции можно добавить NOT NULL constraint на shop_id
-- если требуется строгий multi-tenant режим:
-- ALTER TABLE users ALTER COLUMN shop_id SET NOT NULL;

