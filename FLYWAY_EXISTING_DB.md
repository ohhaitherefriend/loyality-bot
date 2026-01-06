# Flyway и существующая база данных

## Проблема

У нас уже есть работающая база данных с данными и структурой, созданной Hibernate через `ddl-auto: update`. Запуск Flyway впервые может вызвать проблемы.

## Решение

### ✅ Уже настроено в конфигурации:

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true    # ← ЭТО КЛЮЧЕВОЙ ПАРАМЕТР!
    locations: classpath:db/migration
    baseline-version: 0           # ← Существующая БД = версия 0
```

### Что делает `baseline-on-migrate: true`?

При первом запуске Flyway:
1. Обнаружит, что БД существует, но нет таблицы `flyway_schema_history`
2. Создаст `flyway_schema_history`
3. Добавит запись с `version = 0` и `description = << Flyway Baseline >>`
4. Пометит все существующие объекты БД как "baseline" (базовое состояние)
5. Выполнит только миграции с версией > 0 (т.е. V1, V2, V3...)

### Схема работы:

```
Существующая БД (без Flyway)
    ↓
Первый запуск с Flyway
    ↓
baseline-on-migrate: true срабатывает
    ↓
Создается flyway_schema_history с baseline version=0
    ↓
Выполняется V1__restore_october_discounts.sql
    ↓
Успех! БД обновлена, данные восстановлены
```

## Порядок выполнения при старте

### 1. Spring Boot запускается
### 2. Hibernate (ddl-auto: update) работает ПЕРВЫМ
- Проверяет структуру таблиц
- Добавляет новые колонки: `accumulated_amount`, `discount_level`, `discount_earned_at`
- НЕ трогает существующие данные

### 3. Flyway запускается ВТОРЫМ
- Видит существующую БД без `flyway_schema_history`
- Создает baseline (version 0)
- Выполняет миграцию V1 (работает только с данными)

### ⚠️ Важно:
Миграция V1 **НЕ меняет структуру** - только обновляет данные! Hibernate уже создал все нужные колонки.

## Безопасность

### Проверка перед развертыванием:

Убедитесь, что миграция идемпотентна (безопасна при повторном выполнении):

```sql
-- В нашей миграции уже есть проверки:
UPDATE users u
SET ...
WHERE u.id = op.user_id
  AND u.state = 'REGISTERED'
  AND op.total_amount >= 20000
  AND (u.discount_level IS NULL                      -- ← НЕТ скидки
       OR u.discount_earned_at IS NULL               -- ← НЕТ даты
       OR u.discount_earned_at < '2024-10-01'::timestamp); -- ← Старая скидка
```

Это значит, что даже если миграция выполнится дважды (чего не произойдет), она не испортит данные.

## Тестирование на копии БД (рекомендуется)

### Вариант 1: Локальная копия (если есть доступ к продакшен БД)

```bash
# 1. Создайте дамп продакшен БД
pg_dump -h prod_host -U prod_user -d loyalty_db > prod_backup.sql

# 2. Восстановите локально
createdb loyalty_db_test
psql -d loyalty_db_test < prod_backup.sql

# 3. Обновите application-prod.yml для теста
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/loyalty_db_test

# 4. Запустите приложение и проверьте логи
mvn spring-boot:run -Dspring.profiles.active=prod

# 5. Проверьте результат
psql -d loyalty_db_test -c "SELECT * FROM flyway_schema_history;"
psql -d loyalty_db_test -c "SELECT COUNT(*) FROM users WHERE discount_level IS NOT NULL;"
```

### Вариант 2: Staging окружение

Если есть staging сервер - сначала обновите его и проверьте работу.

## Что проверить после развертывания

### 1. Flyway выполнился успешно:

```sql
SELECT * FROM flyway_schema_history ORDER BY installed_rank;
```

Должно быть:
```
version | description              | script                           | success
--------+--------------------------+----------------------------------+---------
0       | << Flyway Baseline >>    | << Flyway Baseline >>            | true
1       | restore october discounts| V1__restore_october_discounts.sql| true
```

### 2. Скидки восстановлены:

```sql
SELECT 
    COUNT(*) as "Всего пользователей со скидками",
    COUNT(CASE WHEN discount_level = 5 THEN 1 END) as "5%",
    COUNT(CASE WHEN discount_level = 7 THEN 1 END) as "7%",
    COUNT(CASE WHEN discount_level = 10 THEN 1 END) as "10%"
FROM users 
WHERE discount_level IS NOT NULL 
  AND discount_earned_at >= '2024-11-01'::timestamp;
```

### 3. Приложение работает:

```bash
# Проверьте health endpoint
curl http://localhost:8080/actuator/health

# Проверьте, что бот отвечает
# Откройте бота и нажмите "📊 Мой статус"
```

## Возможные проблемы и решения

### Проблема 1: "Found non-empty schema(s) without schema history table"

**Это нормально!** Flyway говорит: "Вижу таблицы, но нет моей истории".

**Решение:** `baseline-on-migrate: true` автоматически решает это.

### Проблема 2: Миграция не выполнилась

**Проверка:**
```bash
docker-compose logs -f | grep -i flyway
```

Если видите ошибку - проверьте:
1. Существуют ли колонки `accumulated_amount`, `discount_level`, `discount_earned_at`?
2. Есть ли права у пользователя БД на создание temp таблиц?

**Решение:**
```sql
-- Проверьте колонки
\d users

-- Если колонок нет, Hibernate их создаст при запуске
-- Перезапустите приложение
```

### Проблема 3: "Migration checksum mismatch"

**Причина:** Файл миграции был изменен после выполнения.

**Решение:**
```sql
-- Вариант 1: Исправить контрольную сумму (если изменения безопасны)
UPDATE flyway_schema_history 
SET checksum = <new_checksum> 
WHERE version = '1';

-- Вариант 2: Удалить и выполнить заново (ОСТОРОЖНО!)
DELETE FROM flyway_schema_history WHERE version = '1';
-- Затем перезапустите приложение
```

### Проблема 4: Нет транзакций в октябре

**Симптом:** Миграция выполнилась, но скидок 0.

**Это нормально!** Значит у пользователей не было покупок >= 20,000 руб в октябре.

**Проверка:**
```sql
SELECT COUNT(*) FROM transactions 
WHERE created_at >= '2024-10-01'::timestamp 
  AND created_at < '2024-11-01'::timestamp;
```

## Откат в случае проблем

### Быстрый откат данных:

```sql
-- Откатить все изменения миграции V1
UPDATE users 
SET discount_level = NULL, 
    discount_earned_at = NULL,
    accumulated_amount = 0.0
WHERE discount_earned_at >= '2024-11-01'::timestamp;

-- Удалить запись о миграции (ОСТОРОЖНО!)
DELETE FROM flyway_schema_history WHERE version = '1';
```

### Откат кода:

```bash
docker-compose down
git revert HEAD
docker-compose up --build -d
```

## Отключение Flyway (временно)

Если нужно запустить приложение без миграций:

```yaml
# application-prod.yml
spring:
  flyway:
    enabled: false  # ← Отключить Flyway
```

**Внимание:** При повторном включении Flyway попытается выполнить миграцию!

## Дополнительная защита (опционально)

Если очень беспокоитесь, можно добавить проверку в миграцию:

<function_calls>
<invoke name="search_replace">
<parameter name="file_path">/Users/ohhaithere/plstk-wrld-bot/src/main/resources/db/migration/V1__restore_october_discounts.sql

