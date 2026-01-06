# Исправление ошибки Flyway

## Проблема

Ошибка:
```
ERROR: Колонка accumulated_amount не найдена! 
Hibernate должен её создать до миграции.
```

**Причина:** Flyway запускается до Hibernate, поэтому колонок еще нет.

## Решение

Миграция теперь **сама создает колонки**, если их нет. Это безопасно и идемпотентно.

### Что изменилось в миграции:

```sql
-- Вместо RAISE EXCEPTION
-- Теперь миграция создает колонки:
IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
               WHERE table_name = 'users' 
               AND column_name = 'accumulated_amount') THEN
    ALTER TABLE users ADD COLUMN accumulated_amount DOUBLE PRECISION NOT NULL DEFAULT 0.0;
END IF;
```

### Порядок выполнения:

1. **Flyway** создает колонки (если их нет)
2. **Flyway** восстанавливает данные
3. **Hibernate** проверяет структуру (ddl-auto: update)
4. Если Hibernate создал колонки раньше - ничего страшного, миграция просто пропустит CREATE

## Как применить исправление

### Вариант 1: Чистое развертывание (рекомендуется)

```bash
# 1. Остановите приложение
docker-compose down

# 2. Очистите состояние Flyway в БД
docker-compose exec db psql -U postgres -d loyalty_db -c \
  "DELETE FROM flyway_schema_history WHERE version = '1';"

# 3. Обновите код
git pull

# 4. Пересоберите и запустите
docker-compose up --build -d

# 5. Проверьте логи
docker-compose logs -f | grep -i "V1 Migration"
```

### Вариант 2: Если миграция уже частично выполнена

```bash
# 1. Остановите приложение
docker-compose down

# 2. Проверьте состояние Flyway
docker-compose exec db psql -U postgres -d loyalty_db -c \
  "SELECT * FROM flyway_schema_history WHERE version = '1';"

# Если success = false:
docker-compose exec db psql -U postgres -d loyalty_db -c \
  "DELETE FROM flyway_schema_history WHERE version = '1' AND success = false;"

# 3. Обновите код и перезапустите
git pull
docker-compose up --build -d
```

### Вариант 3: Создать колонки вручную (если нужно)

```sql
-- Подключитесь к БД
psql -h <host> -U <username> -d <database>

-- Создайте колонки
ALTER TABLE users ADD COLUMN IF NOT EXISTS accumulated_amount DOUBLE PRECISION NOT NULL DEFAULT 0.0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS discount_level INTEGER;
ALTER TABLE users ADD COLUMN IF NOT EXISTS discount_earned_at TIMESTAMP;

-- Очистите неудачную миграцию
DELETE FROM flyway_schema_history WHERE version = '1' AND success = false;

-- Перезапустите приложение
```

## Проверка работы

После перезапуска смотрите логи:

```bash
docker-compose logs -f | grep "V1 Migration"
```

Должны увидеть:
```
V1 Migration: Создана колонка accumulated_amount
V1 Migration: Создана колонка discount_level  
V1 Migration: Создана колонка discount_earned_at
V1 Migration: Все необходимые колонки готовы
...
V1 Migration: Восстановлено скидок: X
```

Или если колонки уже были:
```
V1 Migration: Колонка accumulated_amount уже существует
V1 Migration: Колонка discount_level уже существует
V1 Migration: Колонка discount_earned_at уже существует
V1 Migration: Все необходимые колонки готовы
...
V1 Migration: Восстановлено скидок: X
```

## Проверка результата

```sql
-- Проверьте миграцию
SELECT * FROM flyway_schema_history WHERE version = '1';
-- Должно быть: success = true

-- Проверьте колонки
\d users
-- Должны быть: accumulated_amount, discount_level, discount_earned_at

-- Проверьте восстановленные скидки
SELECT COUNT(*) FROM users 
WHERE discount_level IS NOT NULL 
  AND discount_earned_at >= '2024-11-01'::timestamp;
```

## Почему теперь безопасно

✅ **Идемпотентность:** Миграция проверяет существование колонок  
✅ **Независимость:** Не зависит от порядка запуска Hibernate/Flyway  
✅ **Откатываемость:** Можно удалить запись из flyway_schema_history и повторить  
✅ **Логирование:** Видно в логах что именно произошло  

## Если всё равно ошибка

### 1. Проверьте права доступа:

```sql
-- Проверьте права пользователя БД
SELECT * FROM information_schema.role_table_grants 
WHERE grantee = 'your_db_user' AND table_name = 'users';
```

Пользователь должен иметь `ALTER` права.

### 2. Проверьте подключение к БД:

```bash
docker-compose exec db psql -U postgres -d loyalty_db -c "\dt"
```

### 3. Отключите Flyway временно:

```yaml
# application-prod.yml
spring:
  flyway:
    enabled: false
```

Создайте колонки вручную через SQL, затем включите Flyway обратно.

## Итог

Теперь миграция **полностью автономна** и не зависит от Hibernate. 
Просто обновите код и перезапустите - всё сработает! 🚀


