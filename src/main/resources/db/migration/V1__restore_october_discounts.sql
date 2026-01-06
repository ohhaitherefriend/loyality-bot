-- Flyway Migration V1: Восстановление скидок пользователям (октябрь 2024)
-- Автоматически выполняется при запуске приложения

-- Этот скрипт восстанавливает скидки пользователям, которые:
-- 1. Имели покупки на сумму >= 20,000 руб в октябре 2024
-- 2. Потеряли скидку в начале ноября из-за месячного сброса

-- ========================================
-- Создание новых колонок (если их еще нет)
-- ========================================

DO $$
BEGIN
    -- Создаем колонку accumulated_amount, если её нет
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'users' AND column_name = 'accumulated_amount'
    ) THEN
        ALTER TABLE users ADD COLUMN accumulated_amount DOUBLE PRECISION NOT NULL DEFAULT 0.0;
        RAISE NOTICE 'V1 Migration: Создана колонка accumulated_amount';
    ELSE
        RAISE NOTICE 'V1 Migration: Колонка accumulated_amount уже существует';
    END IF;
    
    -- Создаем колонку discount_level, если её нет
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'users' AND column_name = 'discount_level'
    ) THEN
        ALTER TABLE users ADD COLUMN discount_level INTEGER;
        RAISE NOTICE 'V1 Migration: Создана колонка discount_level';
    ELSE
        RAISE NOTICE 'V1 Migration: Колонка discount_level уже существует';
    END IF;
    
    -- Создаем колонку discount_earned_at, если её нет
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'users' AND column_name = 'discount_earned_at'
    ) THEN
        ALTER TABLE users ADD COLUMN discount_earned_at TIMESTAMP;
        RAISE NOTICE 'V1 Migration: Создана колонка discount_earned_at';
    ELSE
        RAISE NOTICE 'V1 Migration: Колонка discount_earned_at уже существует';
    END IF;
    
    RAISE NOTICE 'V1 Migration: Все необходимые колонки готовы';
END $$;

-- ========================================
-- Восстановление скидок
-- ========================================

-- Создаем временную таблицу с суммами покупок в октябре
CREATE TEMP TABLE IF NOT EXISTS october_purchases_temp AS
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

-- Восстанавливаем скидки только тем пользователям, у которых их еще нет
UPDATE users u
SET 
    discount_level = CASE 
        WHEN op.total_amount >= 30000 THEN 10
        WHEN op.total_amount >= 25000 THEN 7
        WHEN op.total_amount >= 20000 THEN 5
        ELSE NULL
    END,
    -- Устанавливаем дату активации на 1 ноября 2024
    discount_earned_at = '2024-11-01 00:00:00'::timestamp
FROM october_purchases_temp op
WHERE u.id = op.user_id
  AND u.state = 'REGISTERED'
  AND op.total_amount >= 20000
  AND (u.discount_level IS NULL OR u.discount_earned_at IS NULL OR u.discount_earned_at < '2024-10-01'::timestamp);

-- Очищаем временную таблицу
DROP TABLE IF EXISTS october_purchases_temp;

-- Логируем результат (только для проверки в логах)
-- PostgreSQL не поддерживает SELECT напрямую в миграциях, 
-- но это выполнится и покажет в логах количество обработанных записей
DO $$
DECLARE
    restored_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO restored_count
    FROM users
    WHERE state = 'REGISTERED'
      AND discount_level IS NOT NULL
      AND discount_earned_at >= '2024-11-01'::timestamp;
    
    RAISE NOTICE 'Flyway Migration V1: Восстановлено скидок: %', restored_count;
END $$;

