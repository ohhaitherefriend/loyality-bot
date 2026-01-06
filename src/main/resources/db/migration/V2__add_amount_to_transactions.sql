-- Flyway Migration V2: Добавление поля amount в transactions
-- Автоматически выполняется при запуске приложения после V1

-- ========================================
-- Добавление колонки amount
-- ========================================

DO $$
BEGIN
    -- Создаем колонку amount, если её нет
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'transactions' AND column_name = 'amount'
    ) THEN
        ALTER TABLE transactions ADD COLUMN amount DOUBLE PRECISION;
        RAISE NOTICE 'V2 Migration: Создана колонка amount в transactions';
    ELSE
        RAISE NOTICE 'V2 Migration: Колонка amount уже существует';
    END IF;
END $$;

-- ========================================
-- Заполнение amount из description
-- ========================================

-- Парсим существующие транзакции и извлекаем сумму из description
-- Формат: "Покупка на 12345.67 руб. (скидка ...)"
UPDATE transactions
SET amount = (
    regexp_match(description, 'Покупка на ([0-9.]+) руб')
)[1]::numeric
WHERE description ~ 'Покупка на [0-9.]+ руб'
  AND amount IS NULL;

-- Логируем результат
DO $$
DECLARE
    updated_count INTEGER;
    total_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO total_count FROM transactions;
    SELECT COUNT(*) INTO updated_count FROM transactions WHERE amount IS NOT NULL;
    
    RAISE NOTICE 'V2 Migration: Обновлено транзакций: % из %', updated_count, total_count;
END $$;


