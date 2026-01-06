# Очистка пользователей с невалидными номерами телефонов

## Проблема

Некоторые пользователи ввели **коды покупки** (6 цифр) вместо номера телефона при регистрации. Это создало проблемы:
- Дублирующиеся "номера телефонов" в базе
- Краши при поиске пользователей
- Невозможность зарегистрироваться заново

## Решение

Используйте скрипт `scripts/cleanup-invalid-phones.sql` для очистки таких пользователей.

---

## 🔴 ВАЖНО: Сделайте backup!

```bash
# Backup H2 базы данных
cp data/loyaltydb.mv.db data/loyaltydb.mv.db.backup-$(date +%Y%m%d)
cp data/loyaltydb.trace.db data/loyaltydb.trace.db.backup-$(date +%Y%m%d)

# Или для PostgreSQL
pg_dump -h localhost -U username -d dbname > backup-$(date +%Y%m%d).sql
```

---

## Шаги по очистке

### 1️⃣ Анализ данных

Сначала посмотрите, сколько проблемных пользователей и какие "телефоны" они ввели:

```sql
-- Шаг 1: Список всех пользователей с невалидными номерами
SELECT 
    id, chat_id, phone_number, first_name, created_at
FROM users
WHERE 
    phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
ORDER BY created_at DESC;

-- Шаг 2: Подсчет
SELECT 
    COUNT(*) as invalid_users_count,
    COUNT(DISTINCT phone_number) as unique_invalid_phones
FROM users
WHERE 
    phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10;

-- Шаг 3: Какие "телефоны" введены
SELECT 
    phone_number,
    COUNT(*) as user_count
FROM users
WHERE 
    phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10
GROUP BY phone_number
ORDER BY user_count DESC;
```

### 2️⃣ Выберите вариант очистки

## Вариант A: Мягкое удаление (РЕКОМЕНДУЕТСЯ) ✅

**Что делает:**
- Очищает номер телефона
- Сбрасывает состояние на NEW
- Пользователи остаются в системе
- При следующем `/start` смогут зарегистрироваться заново

**Когда использовать:**
- Если хотите дать пользователям второй шанс
- Если не хотите терять историю
- Безопасный вариант

```sql
UPDATE users
SET 
    phone_number = '',
    state = 'NEW',
    updated_at = CURRENT_TIMESTAMP
WHERE 
    phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10;
```

## Вариант B: Полное удаление ⚠️

**Что делает:**
- Удаляет пользователей полностью
- Удаляет все связанные данные (коды, транзакции)
- Освобождает место в БД

**Когда использовать:**
- Если пользователи не совершали покупок
- Если хотите полностью очистить БД
- **ОСТОРОЖНО!** Необратимо

```sql
-- Сначала удаляем связанные данные
DELETE FROM purchase_codes WHERE user_id IN (...);
DELETE FROM discount_codes WHERE user_id IN (...);
DELETE FROM transactions WHERE user_id IN (...);

-- Затем самих пользователей
DELETE FROM users WHERE [...невалидные номера...];
```

*См. полный код в `scripts/cleanup-invalid-phones.sql` (ВАРИАНТ B)*

## Вариант C: Выборочное удаление 🎯

**Что делает:**
- Удаляет пользователей с конкретным "номером"

**Когда использовать:**
- Если нужно удалить только один проблемный "телефон"
- Для точечной очистки

```sql
-- Пример: удалить всех с "телефоном" 123456
DELETE FROM purchase_codes WHERE user_id IN (SELECT id FROM users WHERE phone_number = '123456');
DELETE FROM discount_codes WHERE user_id IN (SELECT id FROM users WHERE phone_number = '123456');
DELETE FROM transactions WHERE user_id IN (SELECT id FROM users WHERE phone_number = '123456');
DELETE FROM users WHERE phone_number = '123456';
```

## Вариант D: Очистка только дубликатов 🔧

**Что делает:**
- Оставляет одного (самого старого) пользователя с каждым невалидным номером
- Удаляет остальных дубликатов

**Когда использовать:**
- Когда нужно убрать только дубликаты
- Сохранить хотя бы одного пользователя

*См. полный код в `scripts/cleanup-invalid-phones.sql` (ВАРИАНТ D)*

---

### 3️⃣ Проверка после очистки

```sql
-- Должно вернуть 0
SELECT COUNT(*) as remaining_invalid_users
FROM users
WHERE 
    phone_number IS NOT NULL 
    AND phone_number != ''
    AND LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) < 10;
```

### 4️⃣ Деплой обновленного кода

После очистки БД разверните обновленный код с валидацией:

```bash
# Пересоберите приложение
mvn clean package

# Перезапустите
docker-compose down
docker-compose up --build -d

# Или
./run.sh
```

---

## Как подключиться к базе данных

### H2 Database (Development)

1. Запустите приложение
2. Откройте: `http://localhost:8080/h2-console`
3. Введите:
   - JDBC URL: `jdbc:h2:file:./data/loyaltydb`
   - Username: (из `application.yml`)
   - Password: (из `application.yml`)

### PostgreSQL (Production)

```bash
# Через docker-compose
docker-compose exec postgres psql -U postgres -d loyalty_db

# Локально
psql -h localhost -U your_username -d your_database
```

---

## Что происходит после очистки

### После Варианта A (мягкое удаление):

1. Пользователь открывает бота
2. Нажимает `/start`
3. Бот просит ввести номер телефона заново
4. С новой валидацией код покупки не пройдет!
5. Пользователь вводит настоящий номер
6. Успешная регистрация ✅

### После Варианта B (полное удаление):

1. Пользователь открывает бота
2. Нажимает `/start`
3. Бот создает нового пользователя (как при первом запуске)
4. Просит ввести номер телефона
5. С новой валидацией код покупки не пройдет!
6. Успешная регистрация ✅

---

## Проверка результата

После деплоя обновленного кода проверьте:

```sql
-- Все пользователи должны иметь валидные номера (10+ цифр) или пустые
SELECT 
    id,
    chat_id,
    phone_number,
    LENGTH(REGEXP_REPLACE(phone_number, '[^0-9]', '')) as digits,
    state
FROM users
ORDER BY created_at DESC
LIMIT 20;
```

---

## FAQ

**Q: Потеряют ли пользователи свои покупки?**
- Вариант A: Нет, история сохранится
- Вариант B: Да, все данные будут удалены

**Q: Нужно ли останавливать бота?**
- Для Варианта A: Желательно, но не обязательно
- Для Варианта B: Да, остановите бота перед удалением

**Q: Что если пользователь администратор?**
- Скрипт не различает. Будьте осторожны!
- Можете добавить условие: `AND role != 'ADMIN'`

**Q: Можно ли отменить?**
- Вариант A: Да, можно вернуть номера из backup
- Вариант B: Нет, только из backup

---

## Рекомендуемый порядок действий

1. ✅ **Backup базы данных**
2. ✅ **Анализ** (STEP 1-3)
3. ✅ **Выбор варианта** (рекомендуется A)
4. ✅ **Выполнение очистки**
5. ✅ **Проверка** (STEP 4)
6. ✅ **Деплой нового кода** с валидацией
7. ✅ **Тестирование регистрации**
8. ✅ **Мониторинг логов**

---

## Итого

После выполнения этих шагов:
- ✅ Невалидные "номера телефонов" очищены
- ✅ Пользователи могут зарегистрироваться заново
- ✅ Новая валидация не даст ввести код вместо телефона
- ✅ Unique constraint предотвратит дубликаты
- ✅ Проблема решена навсегда!



