-- Скрипт для восстановления скидок пользователям (PostgreSQL)
-- Выполнить через psql, pgAdmin или другой PostgreSQL клиент

-- Этот скрипт восстанавливает скидки пользователям, которые:
-- 1. Имели покупки на сумму >= 20,000 руб в октябре 2024
-- 2. Потеряли скидку в начале ноября из-за месячного сброса

-- ========================================
-- ШАГ 1: Анализируем транзакции и находим пользователей, 
-- у которых были покупки на нужную сумму в октябре
-- ========================================

-- Просмотр пользователей с покупками в октябре (для проверки)
SELECT 
    u.id,
    u.phone_number,
    u.first_name,
    u.chat_id,
    COALESCE(SUM(CASE 
        WHEN t.created_at >= '2024-10-01'::timestamp AND t.created_at < '2024-11-01'::timestamp 
            AND t.description ~ 'Покупка на [0-9.]+ руб'
        THEN (regexp_match(t.description, 'Покупка на ([0-9.]+) руб'))[1]::numeric
        ELSE 0 
    END), 0) as october_purchases,
    u.accumulated_amount,
    u.discount_level,
    u.discount_earned_at
FROM users u
LEFT JOIN transactions t ON t.user_id = u.id
WHERE u.state = 'REGISTERED'
GROUP BY u.id, u.phone_number, u.first_name, u.chat_id, u.accumulated_amount, u.discount_level, u.discount_earned_at
HAVING COALESCE(SUM(CASE 
        WHEN t.created_at >= '2024-10-01'::timestamp AND t.created_at < '2024-11-01'::timestamp 
            AND t.description ~ 'Покупка на [0-9.]+ руб'
        THEN (regexp_match(t.description, 'Покупка на ([0-9.]+) руб'))[1]::numeric
        ELSE 0 
    END), 0) >= 20000
ORDER BY october_purchases DESC;

-- ========================================
-- ШАГ 2: Восстанавливаем скидки
-- ========================================

-- Создаем временную таблицу с суммами покупок в октябре для упрощения запросов
CREATE TEMP TABLE october_purchases AS
SELECT 
    user_id,
    COALESCE(SUM(
        CASE 
            WHEN description ~ 'Покупка на [0-9.]+ руб'
            THEN (regexp_match(description, 'Покупка на ([0-9.]+) руб'))[1]::numeric
            ELSE 0 
        END
    ), 0) as total_amount
FROM transactions
WHERE created_at >= '2024-10-01'::timestamp 
  AND created_at < '2024-11-01'::timestamp
GROUP BY user_id;

-- Восстановление скидок на основе октябрьских покупок
UPDATE users u
SET 
    discount_level = CASE 
        WHEN op.total_amount >= 30000 THEN 10
        WHEN op.total_amount >= 25000 THEN 7
        WHEN op.total_amount >= 20000 THEN 5
        ELSE NULL
    END,
    -- Устанавливаем дату активации на 1 ноября 2024
    discount_earned_at = '2024-11-01 00:00:00'::timestamp,
    -- Обнуляем накопленную сумму (так как скидка уже активирована)
    accumulated_amount = 0.0
FROM october_purchases op
WHERE u.id = op.user_id
  AND u.state = 'REGISTERED'
  AND op.total_amount >= 20000
  AND (u.discount_level IS NULL OR u.discount_earned_at IS NULL OR u.discount_earned_at < '2024-10-01'::timestamp);

-- ========================================
-- ШАГ 3: Проверка результатов
-- ========================================

-- Проверяем восстановленные скидки
SELECT 
    u.id,
    u.phone_number,
    u.first_name,
    u.discount_level as "Уровень скидки (%)",
    u.discount_earned_at as "Дата активации",
    u.accumulated_amount as "Накоплено",
    (u.discount_earned_at + INTERVAL '30 days') as "Истекает",
    CASE 
        WHEN (u.discount_earned_at + INTERVAL '30 days') > NOW() 
        THEN 'Активна'
        ELSE 'Истекла'
    END as "Статус"
FROM users u
WHERE u.state = 'REGISTERED'
  AND u.discount_level IS NOT NULL
  AND u.discount_earned_at >= '2024-11-01'::timestamp
ORDER BY u.discount_level DESC, u.phone_number;

-- Статистика по восстановленным скидкам
SELECT 
    COUNT(*) as "Всего восстановлено",
    COUNT(CASE WHEN discount_level = 5 THEN 1 END) as "5% скидок",
    COUNT(CASE WHEN discount_level = 7 THEN 1 END) as "7% скидок",
    COUNT(CASE WHEN discount_level = 10 THEN 1 END) as "10% скидок"
FROM users
WHERE state = 'REGISTERED'
  AND discount_level IS NOT NULL
  AND discount_earned_at >= '2024-11-01'::timestamp;

-- ========================================
-- АЛЬТЕРНАТИВНЫЙ ВАРИАНТ (если нужно вручную)
-- ========================================

-- Если автоматический скрипт не работает, можно восстановить вручную для конкретных пользователей:
-- Замените PHONE_NUMBER и DISCOUNT_LEVEL на нужные значения

-- Пример для одного пользователя:
-- UPDATE users 
-- SET 
--     discount_level = 10,  -- 5, 7 или 10
--     discount_earned_at = '2024-11-01 00:00:00'::timestamp,
--     accumulated_amount = 0.0
-- WHERE phone_number = '+79991234567'  -- замените на реальный номер
--   AND state = 'REGISTERED';

-- Пример для нескольких пользователей сразу:
-- UPDATE users 
-- SET 
--     discount_level = 10,
--     discount_earned_at = '2024-11-01 00:00:00'::timestamp,
--     accumulated_amount = 0.0
-- WHERE phone_number IN ('+79991234567', '+79991234568', '+79991234569')
--   AND state = 'REGISTERED';

-- ========================================
-- ПРИМЕЧАНИЯ
-- ========================================

-- 1. Скидка будет действовать 30 дней с даты активации (1 ноября)
-- 2. Пользователи могут продлить скидку, накопив нужную сумму снова
-- 3. После восстановления пользователи увидят свою скидку в боте
-- 4. Рекомендуется выполнить этот скрипт как можно скорее после внедрения новой логики

-- ========================================
-- ОТКАТ (если что-то пошло не так)
-- ========================================

-- Чтобы откатить изменения (убрать все скидки, восстановленные этим скриптом):
-- UPDATE users 
-- SET discount_level = NULL, 
--     discount_earned_at = NULL 
-- WHERE discount_earned_at >= '2024-11-01'::timestamp;

-- Очистка временной таблицы (опционально)
DROP TABLE IF EXISTS october_purchases;

