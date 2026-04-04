-- =====================================================
-- V5: Achievements, Owner Signals, Message Composer
-- - AchievementDefinition (определения ачивок)
-- - CustomerAchievement (полученные ачивки)
-- - OwnerSignal (сигналы владельцу)
-- - MessageTemplate (шаблоны сообщений)
-- - MessageLog (лог сообщений)
-- =====================================================

-- 1. Таблица определений ачивок
CREATE TABLE IF NOT EXISTS achievement_definitions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    emoji VARCHAR(10) NOT NULL DEFAULT '🏆',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    trigger_type VARCHAR(50) NOT NULL,
    trigger_value_int INT,
    trigger_value_period_days INT,
    trigger_params TEXT,
    reward_type VARCHAR(50) DEFAULT 'NONE',
    reward_value INT,
    cooldown_days INT,
    max_awards_per_customer INT,
    display_order INT DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 2. Таблица полученных ачивок
CREATE TABLE IF NOT EXISTS customer_achievements (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    achievement_id BIGINT NOT NULL,
    awarded_at TIMESTAMP NOT NULL,
    metadata TEXT,
    reward_applied BOOLEAN DEFAULT FALSE,
    reward_applied_at TIMESTAMP,
    notification_sent BOOLEAN DEFAULT FALSE,
    
    CONSTRAINT fk_customer_achievement_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_customer_achievement_def FOREIGN KEY (achievement_id) REFERENCES achievement_definitions(id)
);

CREATE INDEX IF NOT EXISTS idx_customer_achievement_user ON customer_achievements(user_id);
CREATE INDEX IF NOT EXISTS idx_customer_achievement_def ON customer_achievements(achievement_id);
CREATE INDEX IF NOT EXISTS idx_customer_achievement_awarded ON customer_achievements(awarded_at);

-- 3. Таблица сигналов владельцу
CREATE TABLE IF NOT EXISTS owner_signals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    signal_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL DEFAULT 'INFO',
    customer_id BIGINT,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    payload TEXT,
    suggested_action VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    is_seen BOOLEAN DEFAULT FALSE,
    seen_at TIMESTAMP,
    is_dismissed BOOLEAN DEFAULT FALSE,
    dismissed_at TIMESTAMP,
    expires_at TIMESTAMP,
    
    CONSTRAINT fk_owner_signal_customer FOREIGN KEY (customer_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_owner_signal_type ON owner_signals(signal_type);
CREATE INDEX IF NOT EXISTS idx_owner_signal_severity ON owner_signals(severity);
CREATE INDEX IF NOT EXISTS idx_owner_signal_created ON owner_signals(created_at);
CREATE INDEX IF NOT EXISTS idx_owner_signal_seen ON owner_signals(is_seen);

-- 4. Таблица шаблонов сообщений
CREATE TABLE IF NOT EXISTS message_templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(50) DEFAULT 'GENERAL',
    auto_trigger VARCHAR(50),
    is_active BOOLEAN DEFAULT TRUE,
    is_system BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 5. Таблица логов сообщений
CREATE TABLE IF NOT EXISTS message_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    message_type VARCHAR(50) NOT NULL,
    template_id BIGINT,
    content TEXT NOT NULL,
    sent_by_id BIGINT,
    sent_at TIMESTAMP NOT NULL,
    status VARCHAR(20) DEFAULT 'SENT',
    error_message VARCHAR(500),
    achievement_id BIGINT,
    signal_id BIGINT,
    
    CONSTRAINT fk_message_log_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_message_log_template FOREIGN KEY (template_id) REFERENCES message_templates(id),
    CONSTRAINT fk_message_log_sent_by FOREIGN KEY (sent_by_id) REFERENCES users(id),
    CONSTRAINT fk_message_log_achievement FOREIGN KEY (achievement_id) REFERENCES customer_achievements(id),
    CONSTRAINT fk_message_log_signal FOREIGN KEY (signal_id) REFERENCES owner_signals(id)
);

CREATE INDEX IF NOT EXISTS idx_message_log_user ON message_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_message_log_sent ON message_logs(sent_at);
CREATE INDEX IF NOT EXISTS idx_message_log_type ON message_logs(message_type);

-- 6. Вставка дефолтных ачивок
INSERT INTO achievement_definitions (title, description, emoji, trigger_type, trigger_value_int, max_awards_per_customer, display_order, created_at, updated_at)
SELECT 'Первая покупка', 'Добро пожаловать в программу лояльности!', '🎉', 'FIRST_PURCHASE', NULL, 1, 100, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM achievement_definitions WHERE trigger_type = 'FIRST_PURCHASE');

INSERT INTO achievement_definitions (title, description, emoji, trigger_type, trigger_value_int, max_awards_per_customer, display_order, created_at, updated_at)
SELECT '5 покупок', 'Совершено 5 покупок', '⭐', 'N_PURCHASES_TOTAL', 5, 1, 90, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM achievement_definitions WHERE trigger_type = 'N_PURCHASES_TOTAL' AND trigger_value_int = 5);

INSERT INTO achievement_definitions (title, description, emoji, trigger_type, trigger_value_int, max_awards_per_customer, display_order, created_at, updated_at)
SELECT '10 покупок', 'Совершено 10 покупок', '🌟', 'N_PURCHASES_TOTAL', 10, 1, 80, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM achievement_definitions WHERE trigger_type = 'N_PURCHASES_TOTAL' AND trigger_value_int = 10);

INSERT INTO achievement_definitions (title, description, emoji, trigger_type, trigger_value_int, max_awards_per_customer, display_order, created_at, updated_at)
SELECT 'Постоянный клиент', 'Достигнут статус REGULAR', '⭐', 'BECAME_REGULAR', NULL, 1, 70, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM achievement_definitions WHERE trigger_type = 'BECAME_REGULAR');

INSERT INTO achievement_definitions (title, description, emoji, trigger_type, trigger_value_int, max_awards_per_customer, display_order, created_at, updated_at)
SELECT 'VIP клиент', 'Достигнут статус VIP', '👑', 'BECAME_VIP', NULL, 1, 60, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM achievement_definitions WHERE trigger_type = 'BECAME_VIP');

INSERT INTO achievement_definitions (title, description, emoji, trigger_type, trigger_value_int, max_awards_per_customer, display_order, created_at, updated_at)
SELECT 'Ранняя пташка', 'Покупка с 6 до 9 утра', '🌅', 'PURCHASE_TIME_WINDOW', NULL, NULL, 50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM achievement_definitions WHERE title = 'Ранняя пташка');

-- Обновляем параметры для "Ранняя пташка"
UPDATE achievement_definitions 
SET trigger_params = '{"startHour": 6, "endHour": 9}'
WHERE title = 'Ранняя пташка' AND trigger_params IS NULL;

-- 7. Вставка системных шаблонов сообщений
INSERT INTO message_templates (name, content, category, auto_trigger, is_active, is_system, created_at, updated_at)
SELECT 'Первая покупка', '🎉 Поздравляем с первой покупкой, {name}!\n\nДобро пожаловать в нашу программу лояльности!', 'WELCOME', 'FIRST_PURCHASE', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM message_templates WHERE auto_trigger = 'FIRST_PURCHASE');

INSERT INTO message_templates (name, content, category, auto_trigger, is_active, is_system, created_at, updated_at)
SELECT 'Остался 1 штамп', '☕ {name}, до награды остался всего 1 штамп!\n\nПриходите скорее! 🎁', 'REMINDER', 'ONE_STAMP_LEFT', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM message_templates WHERE auto_trigger = 'ONE_STAMP_LEFT');

INSERT INTO message_templates (name, content, category, auto_trigger, is_active, is_system, created_at, updated_at)
SELECT 'Стал постоянным', '⭐ Поздравляем, {name}!\n\nВы стали нашим постоянным клиентом!', 'STATUS_CHANGE', 'BECAME_REGULAR', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM message_templates WHERE auto_trigger = 'BECAME_REGULAR');

INSERT INTO message_templates (name, content, category, auto_trigger, is_active, is_system, created_at, updated_at)
SELECT 'Стал VIP', '👑 {name}, добро пожаловать в VIP клуб!\n\nБлагодарим за вашу лояльность!', 'STATUS_CHANGE', 'BECAME_VIP', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM message_templates WHERE auto_trigger = 'BECAME_VIP');



