# Flyway Миграции

## Описание

Flyway автоматически выполняет SQL миграции при запуске приложения. Это обеспечивает согласованность базы данных между всеми окружениями.

## Как это работает

1. **При старте приложения** Flyway:
   - Проверяет таблицу `flyway_schema_history` в базе данных
   - Определяет, какие миграции уже выполнены
   - Выполняет только новые миграции по порядку

2. **Именование миграций**:
   - Формат: `V{версия}__{описание}.sql`
   - Пример: `V1__restore_october_discounts.sql`
   - Версии выполняются в порядке возрастания

3. **Безопасность**:
   - Каждая миграция выполняется только один раз
   - После выполнения изменить миграцию нельзя (контрольная сумма)
   - Гарантирует идентичное состояние БД во всех окружениях

## Текущие миграции

### V1__restore_october_discounts.sql

**Назначение:** Восстановление скидок пользователям за октябрь 2024

**Что делает:**
1. Создает временную таблицу с суммами покупок в октябре
2. Обновляет поля `discount_level`, `discount_earned_at`, `accumulated_amount` для пользователей с покупками >= 20,000 руб
3. Устанавливает дату активации на 1 ноября 2024
4. Логирует количество восстановленных скидок

**Условия выполнения:**
- Только для пользователей со статусом `REGISTERED`
- Только если скидка еще не была активирована или истекла до октября
- Только при наличии транзакций в октябре 2024

**Логика определения уровня:**
- 30,000+ руб → 10% скидка
- 25,000+ руб → 7% скидка
- 20,000+ руб → 5% скидка

## Конфигурация

### application.yml (Dev/H2)
```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
    baseline-version: 0
```

### application-prod.yml (Production/PostgreSQL)
```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
    baseline-version: 0
```

### Параметры:
- `enabled: true` - включить Flyway
- `baseline-on-migrate: true` - создать baseline для существующей БД
- `locations` - путь к миграциям
- `baseline-version: 0` - начальная версия для существующих БД

## Развертывание

### Первое развертывание (с существующей БД):

1. **Остановите приложение**:
   ```bash
   docker-compose down
   # или
   ./stop.sh
   ```

2. **Обновите код**:
   ```bash
   git pull
   mvn clean package
   # или
   docker-compose build
   ```

3. **Запустите приложение**:
   ```bash
   docker-compose up -d
   # или
   ./run.sh
   ```

4. **Проверьте логи**:
   ```bash
   docker-compose logs -f | grep -i flyway
   # или
   tail -f logs/application.log | grep -i flyway
   ```

   Вы должны увидеть:
   ```
   Flyway: Migrating schema to version 1 - restore october discounts
   Flyway Migration V1: Восстановлено скидок: X
   ```

5. **Проверьте результат**:
   ```sql
   SELECT * FROM flyway_schema_history;
   
   SELECT COUNT(*) FROM users 
   WHERE discount_level IS NOT NULL 
     AND discount_earned_at >= '2024-11-01'::timestamp;
   ```

### Последующие развертывания:

При обновлении кода миграция V1 **НЕ** будет выполняться повторно - Flyway помнит, что она уже выполнена.

## Добавление новых миграций

### Шаг 1: Создайте файл миграции

```bash
# Формат: V{следующая_версия}__{описание}.sql
touch src/main/resources/db/migration/V2__add_loyalty_tiers.sql
```

### Шаг 2: Напишите SQL

```sql
-- V2__add_loyalty_tiers.sql
ALTER TABLE users ADD COLUMN loyalty_tier VARCHAR(20) DEFAULT 'BRONZE';

UPDATE users 
SET loyalty_tier = CASE 
    WHEN discount_level >= 10 THEN 'PLATINUM'
    WHEN discount_level >= 7 THEN 'GOLD'
    WHEN discount_level >= 5 THEN 'SILVER'
    ELSE 'BRONZE'
END;
```

### Шаг 3: Пересоберите и разверните

```bash
mvn clean package
docker-compose up -d
```

Flyway автоматически выполнит V2 при следующем запуске.

## Отключение миграции (если нужно)

Если хотите временно отключить Flyway:

```yaml
spring:
  flyway:
    enabled: false
```

**Внимание:** Не рекомендуется отключать Flyway в production!

## Откат миграций

Flyway Community Edition не поддерживает автоматический откат. Для отката:

### Вариант 1: Создать новую миграцию отката

```sql
-- V3__rollback_october_discounts.sql
UPDATE users 
SET discount_level = NULL, 
    discount_earned_at = NULL,
    accumulated_amount = 0.0
WHERE discount_earned_at >= '2024-11-01'::timestamp;
```

### Вариант 2: Ручной откат через SQL

```bash
psql -h <host> -U <username> -d <database>
```

```sql
-- Откатить изменения V1
UPDATE users 
SET discount_level = NULL, 
    discount_earned_at = NULL
WHERE discount_earned_at >= '2024-11-01'::timestamp;

-- Удалить запись из истории миграций (осторожно!)
DELETE FROM flyway_schema_history WHERE version = '1';
```

**Внимание:** Удаление из `flyway_schema_history` опасно! Делайте это только если понимаете последствия.

## Проверка статуса миграций

### Через SQL:

```sql
SELECT * FROM flyway_schema_history ORDER BY installed_rank;
```

Покажет:
- `installed_rank` - порядок выполнения
- `version` - версия миграции
- `description` - описание
- `script` - имя файла
- `checksum` - контрольная сумма
- `installed_on` - дата выполнения
- `execution_time` - время выполнения (мс)
- `success` - статус

### Через логи приложения:

```bash
docker-compose logs -f app | grep Flyway
```

## Troubleshooting

### Ошибка: "Validate failed: Migration checksum mismatch"

**Причина:** Изменили уже выполненную миграцию.

**Решение:**
1. Откатите изменения в файле миграции
2. Или создайте новую миграцию с исправлениями
3. Или (крайний случай) очистите `flyway_schema_history`

### Ошибка: "Found non-empty schema(s) without schema history table"

**Причина:** База существует, но не имеет истории Flyway.

**Решение:** Уже настроено через `baseline-on-migrate: true` в конфигурации.

### Миграция зависла

**Причина:** Flyway ждет завершения длительной операции или deadlock.

**Решение:**
```sql
-- Проверьте активные запросы
SELECT * FROM pg_stat_activity WHERE state = 'active';

-- Убейте зависший процесс (осторожно!)
SELECT pg_terminate_backend(pid) FROM pg_stat_activity 
WHERE state = 'active' AND query LIKE '%flyway%';

-- Проверьте блокировки
SELECT * FROM flyway_schema_history WHERE success = false;
```

## Best Practices

1. **Никогда не изменяйте выполненные миграции** - создавайте новые
2. **Тестируйте миграции локально** перед развертыванием
3. **Делайте резервные копии** перед выполнением миграций
4. **Пишите идемпотентные миграции** (безопасны при повторном выполнении)
5. **Используйте транзакции** где возможно
6. **Добавляйте логирование** через `RAISE NOTICE` в PostgreSQL

## Дополнительно

### Flyway Maven Plugin

Можно также использовать Flyway через Maven:

```xml
<plugin>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-maven-plugin</artifactId>
    <version>9.22.0</version>
    <configuration>
        <url>jdbc:postgresql://localhost:5432/loyalty_db</url>
        <user>postgres</user>
        <password>password</password>
    </configuration>
</plugin>
```

Команды:
```bash
mvn flyway:info      # Показать статус миграций
mvn flyway:migrate   # Выполнить миграции
mvn flyway:validate  # Проверить миграции
mvn flyway:baseline  # Создать baseline
```

### Flyway Pro Features (платная версия)

- Undo миграции (автоматический откат)
- Dry runs (тестирование без изменений)
- Oracle SQL*Plus support
- И другие расширенные возможности

Для текущего проекта достаточно Community Edition.


