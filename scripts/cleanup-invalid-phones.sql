-- Скрипт для очистки пользователей с невалидными номерами телефонов
-- Это пользователи, которые ввели коды покупки вместо номеров
-- 
-- ВАЖНО: Сделайте backup базы данных перед выполнением!

-- ===================================
-- STEP 1: Найти пользователей с невалидными номерами
-- ===================================
-- Показывает всех пользователей, у которых номер телефона короче 10 цифр
-- (это коды покупки 6-8 цифр, а не настоящие телефоны)

SELECT 
    id,
    chat_id,
    phone_number,
    LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) as digits_count,
    first_name,
    last_name,
    role,
    state,
    created_at
FROM users
WHERE 
    phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
ORDER BY created_at DESC;

-- ===================================
-- STEP 2: Подсчитать количество проблемных пользователей
-- ===================================
SELECT 
    COUNT(*) as invalid_users_count,
    COUNT(DISTINCT phone_number) as unique_invalid_phones
FROM users
WHERE 
    phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10;

-- ===================================
-- STEP 3: Посмотреть какие именно "телефоны" введены
-- ===================================
SELECT 
    phone_number,
    COUNT(*) as user_count,
    LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) as digits_count
FROM users
WHERE 
    phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
GROUP BY phone_number
ORDER BY user_count DESC;

-- ===================================
-- ВАРИАНТ A: МЯГКОЕ УДАЛЕНИЕ (РЕКОМЕНДУЕТСЯ)
-- ===================================
-- Сбрасываем состояние пользователей на NEW и очищаем номер телефона
-- Пользователи останутся в системе, но при следующем /start смогут зарегистрироваться заново

UPDATE users
SET 
    phone_number = '',
    state = 'NEW',
    updated_at = CURRENT_TIMESTAMP
WHERE 
    phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10;

-- После этого пользователи смогут нажать /start и пройти регистрацию заново

-- ===================================
-- ВАРИАНТ B: ПОЛНОЕ УДАЛЕНИЕ (ОСТОРОЖНО!)
-- ===================================
-- Удаляет пользователей с невалидными номерами полностью из базы
-- ВНИМАНИЕ: Также удалятся все связанные данные (транзакции, коды и т.д.)

-- B.1: Сначала удаляем связанные данные (если есть foreign keys)

-- Удаляем коды покупок этих пользователей
DELETE FROM purchase_codes
WHERE user_id IN (
    SELECT id FROM users
    WHERE phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
);

-- Удаляем коды скидок этих пользователей
DELETE FROM discount_codes
WHERE user_id IN (
    SELECT id FROM users
    WHERE phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
);

-- Удаляем транзакции этих пользователей
DELETE FROM transactions
WHERE user_id IN (
    SELECT id FROM users
    WHERE phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
);

-- B.2: Теперь удаляем самих пользователей
DELETE FROM users
WHERE 
    phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10;

-- ===================================
-- ВАРИАНТ C: ВЫБОРОЧНОЕ УДАЛЕНИЕ
-- ===================================
-- Удалить только пользователей с конкретным "номером"
-- Полезно, если вы хотите удалить только дубликаты с одним и тем же кодом

-- Пример: удалить всех пользователей с "телефоном" 123456
DELETE FROM purchase_codes WHERE user_id IN (SELECT id FROM users WHERE phone_number = '123456');
DELETE FROM discount_codes WHERE user_id IN (SELECT id FROM users WHERE phone_number = '123456');
DELETE FROM transactions WHERE user_id IN (SELECT id FROM users WHERE phone_number = '123456');
DELETE FROM users WHERE phone_number = '123456';

-- ===================================
-- ВАРИАНТ D: ОЧИСТКА ТОЛЬКО ДУБЛИКАТОВ
-- ===================================
-- Удаляет только пользователей с дублирующимися невалидными номерами
-- Оставляет одного пользователя с каждым номером (самого старого)

-- D.1: Посмотреть, кто будет удален
SELECT u.*
FROM users u
WHERE 
    phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
    AND id NOT IN (
        -- Оставляем только самого старого пользователя для каждого номера
        SELECT MIN(id)
        FROM users
        WHERE phone_number IS NOT NULL 
        AND phone_number != ''
        AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
        GROUP BY phone_number
    );

-- D.2: Удалить дубликаты (оставив самого старого)
DELETE FROM purchase_codes WHERE user_id IN (
    SELECT u.id FROM users u
    WHERE phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
    AND id NOT IN (
        SELECT MIN(id) FROM users
        WHERE phone_number IS NOT NULL 
        AND phone_number != ''
        AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
        GROUP BY phone_number
    )
);

DELETE FROM discount_codes WHERE user_id IN (
    SELECT u.id FROM users u
    WHERE phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
    AND id NOT IN (
        SELECT MIN(id) FROM users
        WHERE phone_number IS NOT NULL 
        AND phone_number != ''
        AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
        GROUP BY phone_number
    )
);

DELETE FROM transactions WHERE user_id IN (
    SELECT u.id FROM users u
    WHERE phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
    AND id NOT IN (
        SELECT MIN(id) FROM users
        WHERE phone_number IS NOT NULL 
        AND phone_number != ''
        AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
        GROUP BY phone_number
    )
);

DELETE FROM users
WHERE 
    phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
    AND id NOT IN (
        SELECT MIN(id)
        FROM users
        WHERE phone_number IS NOT NULL 
        AND phone_number != ''
        AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
        GROUP BY phone_number
    );

-- ===================================
-- STEP 4: Проверка после очистки
-- ===================================
-- Должно вернуть 0 строк после успешной очистки
SELECT COUNT(*) as remaining_invalid_users
FROM users
WHERE 
    phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10;

-- ===================================
-- РЕКОМЕНДАЦИИ ПО ИСПОЛЬЗОВАНИЮ
-- ===================================
-- 1. BACKUP БАЗЫ ДАННЫХ ОБЯЗАТЕЛЬНО!
-- 2. Сначала выполните STEP 1-3 для анализа
-- 3. Рекомендуется ВАРИАНТ A (мягкое удаление)
-- 4. Если используете ВАРИАНТ B или D, будьте осторожны с foreign keys
-- 5. После очистки выполните STEP 4 для проверки
-- 6. Перезапустите приложение после изменений

-- ===================================
-- ДОПОЛНИТЕЛЬНО: Отправка уведомлений
-- ===================================
-- Можно добавить в код бота функцию для отправки уведомлений
-- этим пользователям о необходимости перерегистрации

-- Список chat_id пользователей с невалидными номерами:
SELECT DISTINCT chat_id
FROM users
WHERE 
    phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10;



