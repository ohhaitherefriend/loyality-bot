-- =====================================================
-- V4: Coffee-friendly функционал
-- - ShopSettings (конфигурация магазина)
-- - CustomerProfile поля в users
-- - StampWallet (штамп-карта)
-- - RedeemCode (код погашения награды)
-- - Расширение purchase_codes
-- =====================================================

-- 1. Таблица настроек магазина (singleton)
CREATE TABLE IF NOT EXISTS shop_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_name VARCHAR(255) NOT NULL DEFAULT 'Магазин',
    default_location_id VARCHAR(255),
    telegram_channel_url VARCHAR(500),
    
    -- Накопительная скидка
    discount_tiers_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    
    -- Fast Checkout
    fast_checkout_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    fast_checkout_type VARCHAR(50) DEFAULT 'STAMP',
    fast_checkout_value INT NOT NULL DEFAULT 1,
    fast_checkout_cooldown_minutes INT NOT NULL DEFAULT 5,
    fast_checkout_daily_limit_per_customer INT NOT NULL DEFAULT 10,
    
    -- Штампы
    stamps_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    stamps_per_fast_purchase INT NOT NULL DEFAULT 1,
    stamps_required_for_reward INT NOT NULL DEFAULT 10,
    reward_title VARCHAR(255) NOT NULL DEFAULT 'Бесплатный напиток',
    reward_description VARCHAR(500),
    redeem_requires_cashier_confirm BOOLEAN NOT NULL DEFAULT TRUE,
    redeem_code_ttl_minutes INT NOT NULL DEFAULT 10,
    
    -- Статусы клиентов
    regular_threshold_purchases INT NOT NULL DEFAULT 3,
    vip_threshold_purchases INT NOT NULL DEFAULT 10,
    vip_threshold_total_spend DOUBLE,
    lost_days_since_last_purchase INT NOT NULL DEFAULT 30,
    
    -- Авто-сообщения
    auto_messages_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auto_messages_daily_limit_per_customer INT NOT NULL DEFAULT 3,
    
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 2. Расширение таблицы users (CustomerProfile поля)
ALTER TABLE users ADD COLUMN IF NOT EXISTS first_purchase_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_purchase_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS purchases_count INT DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS visits_count INT DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS total_spend DOUBLE DEFAULT 0.0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS customer_status VARCHAR(50) DEFAULT 'NEW';
ALTER TABLE users ADD COLUMN IF NOT EXISTS status_updated_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_fast_checkout_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS fast_checkout_today_count INT DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS fast_checkout_count_reset_at TIMESTAMP;

-- 3. Таблица штамп-кошельков
CREATE TABLE IF NOT EXISTS stamp_wallets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stamps_count INT NOT NULL DEFAULT 0,
    rewards_earned INT NOT NULL DEFAULT 0,
    rewards_available INT NOT NULL DEFAULT 0,
    rewards_redeemed INT NOT NULL DEFAULT 0,
    total_stamps_earned INT NOT NULL DEFAULT 0,
    last_stamp_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    
    CONSTRAINT fk_stamp_wallet_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_stamp_wallet_user UNIQUE (user_id)
);

-- 4. Таблица кодов погашения наград
CREATE TABLE IF NOT EXISTS redeem_codes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    stamp_wallet_id BIGINT NOT NULL,
    reward_title VARCHAR(255) NOT NULL,
    reward_description VARCHAR(500),
    status VARCHAR(50) DEFAULT 'ACTIVE',
    created_at TIMESTAMP,
    expires_at TIMESTAMP,
    used_at TIMESTAMP,
    used_by_admin_id BIGINT,
    
    CONSTRAINT fk_redeem_code_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_redeem_code_wallet FOREIGN KEY (stamp_wallet_id) REFERENCES stamp_wallets(id),
    CONSTRAINT fk_redeem_code_admin FOREIGN KEY (used_by_admin_id) REFERENCES users(id)
);

-- 5. Расширение purchase_codes
ALTER TABLE purchase_codes ADD COLUMN IF NOT EXISTS location_id VARCHAR(255);
ALTER TABLE purchase_codes ADD COLUMN IF NOT EXISTS from_deep_link BOOLEAN DEFAULT FALSE;
ALTER TABLE purchase_codes ADD COLUMN IF NOT EXISTS fast_checkout BOOLEAN DEFAULT FALSE;
ALTER TABLE purchase_codes ADD COLUMN IF NOT EXISTS purchase_amount DOUBLE;

-- 6. Индексы для производительности
CREATE INDEX IF NOT EXISTS idx_users_customer_status ON users(customer_status);
CREATE INDEX IF NOT EXISTS idx_users_last_purchase_at ON users(last_purchase_at);
CREATE INDEX IF NOT EXISTS idx_stamp_wallets_user_id ON stamp_wallets(user_id);
CREATE INDEX IF NOT EXISTS idx_redeem_codes_status ON redeem_codes(status);
CREATE INDEX IF NOT EXISTS idx_redeem_codes_code ON redeem_codes(code);
CREATE INDEX IF NOT EXISTS idx_redeem_codes_expires_at ON redeem_codes(expires_at);

-- 7. Вставка настроек магазина по умолчанию (если нет)
INSERT INTO shop_settings (shop_name, discount_tiers_enabled, fast_checkout_enabled, stamps_enabled, created_at, updated_at)
SELECT 'Магазин', TRUE, FALSE, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM shop_settings);



