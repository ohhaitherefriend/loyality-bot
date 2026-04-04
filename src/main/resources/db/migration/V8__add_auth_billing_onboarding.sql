-- ========================================
-- V8: Auth, Billing, Onboarding tables
-- ========================================

-- Admin Users (Web UI authentication)
CREATE TABLE IF NOT EXISTS admin_users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_admin_user_email ON admin_users(email);

-- Shops (Business entities)
CREATE TABLE IF NOT EXISTS shops (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    timezone VARCHAR(64) DEFAULT 'Europe/Moscow',
    owner_id BIGINT NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_shop_shop_id ON shops(shop_id);

-- Shop Members (User-Shop association with roles)
CREATE TABLE IF NOT EXISTS shop_members (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    shop_id VARCHAR(36) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'STAFF',
    created_at TIMESTAMP,
    UNIQUE(user_id, shop_id)
);

CREATE INDEX IF NOT EXISTS idx_shop_member_user_id ON shop_members(user_id);
CREATE INDEX IF NOT EXISTS idx_shop_member_shop_id ON shop_members(shop_id);

-- Plans (Subscription plans)
CREATE TABLE IF NOT EXISTS plans (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1024),
    price_amount INTEGER NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'RUB',
    period_days INTEGER NOT NULL DEFAULT 30,
    is_stub BOOLEAN NOT NULL DEFAULT TRUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_plan_code ON plans(code);

-- Subscriptions
CREATE TABLE IF NOT EXISTS subscriptions (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'TRIALING',
    plan_code VARCHAR(64) NOT NULL DEFAULT 'FREE_TRIAL',
    provider VARCHAR(32) NOT NULL DEFAULT 'STUB',
    trial_start_at TIMESTAMP,
    trial_end_at TIMESTAMP,
    current_period_start_at TIMESTAMP,
    current_period_end_at TIMESTAMP,
    external_subscription_id VARCHAR(255),
    expiration_notified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_subscription_shop_id ON subscriptions(shop_id);
CREATE INDEX IF NOT EXISTS idx_subscription_status ON subscriptions(status);

-- Onboarding States
CREATE TABLE IF NOT EXISTS onboarding_states (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    shop_id VARCHAR(36),
    step VARCHAR(32) NOT NULL DEFAULT 'START',
    data_json TEXT,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_onboarding_user_id ON onboarding_states(user_id);
CREATE INDEX IF NOT EXISTS idx_onboarding_shop_id ON onboarding_states(shop_id);

-- Insert default plans
INSERT INTO plans (code, name, description, price_amount, currency, period_days, is_stub, is_active, created_at)
VALUES 
    ('FREE_TRIAL', 'Пробный период', '7 дней бесплатного использования', 0, 'RUB', 7, true, true, NOW()),
    ('BASIC_MONTHLY', 'Базовый (месяц)', 'Базовый тариф на месяц', 99000, 'RUB', 30, true, true, NOW()),
    ('BASIC_YEARLY', 'Базовый (год)', 'Базовый тариф на год', 990000, 'RUB', 365, true, true, NOW())
ON CONFLICT (code) DO NOTHING;
