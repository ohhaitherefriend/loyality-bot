-- V6: Добавление ручных бейджей (Итерация 3)

-- Таблица определений бейджей
CREATE TABLE IF NOT EXISTS manual_badge_definitions (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    emoji VARCHAR(10) NOT NULL DEFAULT '🎖',
    perk_type VARCHAR(50) DEFAULT 'NONE',
    perk_value INT,
    valid_days INT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    max_awards_per_month INT,
    max_per_customer INT DEFAULT 1,
    display_order INT DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- Таблица выданных бейджей
CREATE TABLE IF NOT EXISTS customer_badges (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    badge_id BIGINT NOT NULL,
    awarded_by_id BIGINT,
    awarded_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    reason VARCHAR(500),
    metadata TEXT,
    notification_sent BOOLEAN DEFAULT FALSE,
    
    CONSTRAINT fk_cb_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_cb_badge FOREIGN KEY (badge_id) REFERENCES manual_badge_definitions(id) ON DELETE CASCADE,
    CONSTRAINT fk_cb_awarded_by FOREIGN KEY (awarded_by_id) REFERENCES users(id) ON DELETE SET NULL
);

-- Индексы для customer_badges
CREATE INDEX IF NOT EXISTS idx_customer_badge_user ON customer_badges(user_id);
CREATE INDEX IF NOT EXISTS idx_customer_badge_def ON customer_badges(badge_id);
CREATE INDEX IF NOT EXISTS idx_customer_badge_status ON customer_badges(status);
CREATE INDEX IF NOT EXISTS idx_customer_badge_expires ON customer_badges(expires_at);

-- Добавляем колонку канала в shop_settings (если ещё нет)
-- Примечание: эта колонка может уже существовать из V4
-- В H2 можно использовать IF NOT EXISTS для колонок через MERGE или игнорировать ошибку

-- Создаём несколько бейджей по умолчанию
INSERT INTO manual_badge_definitions (title, description, emoji, perk_type, perk_value, valid_days, is_active, display_order, created_at, updated_at)
VALUES 
    ('VIP Клиент', 'Особый статус для лучших клиентов', '⭐', 'BONUS_STAMPS_MULTIPLIER', 2, NULL, TRUE, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('День рождения', 'Праздничный бейдж', '🎂', 'BONUS_STAMPS_FLAT', 3, 7, TRUE, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Ранняя пташка', 'За покупки до 9:00', '🌅', 'PRIORITY_STATUS', NULL, 30, TRUE, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Друг магазина', 'Рекомендовал друзьям', '🤝', 'BONUS_POINTS_FLAT', 100, NULL, TRUE, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Первопроходец', 'Один из первых клиентов', '🚀', 'STATUS_PROTECTION', NULL, NULL, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);



