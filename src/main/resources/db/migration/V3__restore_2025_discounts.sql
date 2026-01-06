-- Flyway Migration V3: Восстановление скидок за октябрь-ноябрь 2025
-- Применяется автоматически при запуске приложения

-- ========================================
-- Восстанавливаем скидки за октябрь-ноябрь 2025
-- ========================================

-- Создаем временную таблицу с суммами покупок за октябрь-ноябрь 2025
CREATE TEMP TABLE purchases_2025_temp AS
SELECT 
    user_id,
    SUM(amount) as total_amount,
    MAX(created_at) as last_purchase_date
FROM transactions
WHERE created_at >= '2025-10-01'::timestamp 
  AND created_at < '2025-12-01'::timestamp
  AND amount IS NOT NULL
GROUP BY user_id
HAVING SUM(amount) >= 20000;

-- Логируем найденных пользователей
DO $$
DECLARE
    user_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO user_count FROM purchases_2025_temp;
    RAISE NOTICE 'V3 Migration: Найдено пользователей для восстановления скидок: %', user_count;
END $$;

-- Восстанавливаем скидки
UPDATE users u
SET 
    discount_level = CASE 
        WHEN p.total_amount >= 30000 THEN 10
        WHEN p.total_amount >= 25000 THEN 7
        WHEN p.total_amount >= 20000 THEN 5
        ELSE NULL
    END,
    -- Устанавливаем дату активации на дату последней покупки
    discount_earned_at = p.last_purchase_date
FROM purchases_2025_temp p
WHERE u.id = p.user_id
  AND u.state = 'REGISTERED'
  AND (u.discount_level IS NULL OR u.discount_earned_at IS NULL OR u.discount_earned_at < '2025-10-01'::timestamp);

-- Очищаем временную таблицу
DROP TABLE IF EXISTS purchases_2025_temp;

-- Логируем результат
DO $$
DECLARE
    restored_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO restored_count 
    FROM users 
    WHERE discount_level IS NOT NULL 
      AND discount_earned_at >= '2025-10-01'::timestamp;
    
    RAISE NOTICE 'V3 Migration: Восстановлено скидок: % пользователям', restored_count;
    RAISE NOTICE 'V3 Migration: Завершено успешно';
END $$;


