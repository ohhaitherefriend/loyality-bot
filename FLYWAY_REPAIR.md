# 🔧 Flyway Repair - Исправление checksum mismatch

## ⚠️ Проблема

```
Migration checksum mismatch for migration version 1
-> Applied to database : 1654851798
-> Resolved locally    : 1838735447
```

**Причина**: Файл `V1__restore_october_discounts.sql` был изменен после того, как миграция уже была применена в БД.

## ✅ Решение 1: Flyway Repair (БЫСТРО)

```bash
# На сервере
cd /path/to/plstk-wrld-bot

# Запустить repair через Docker
docker-compose run --rm app /bin/sh -c "\
  java -cp 'BOOT-INF/classes:BOOT-INF/lib/*' \
  org.flywaydb.core.Flyway \
  -url=\${SPRING_DATASOURCE_URL} \
  -user=\${SPRING_DATASOURCE_USERNAME} \
  -password=\${SPRING_DATASOURCE_PASSWORD} \
  -locations=classpath:db/migration \
  repair"

# Или через psql
docker-compose exec app psql -U loyalty_user -d loyalty_db -c "\
UPDATE flyway_schema_history \
SET checksum = 1838735447 \
WHERE version = '1';"

# Перезапустить приложение
docker-compose up -d
```

## ✅ Решение 2: Удалить и применить заново (ЕСЛИ МОЖНО)

```bash
# ВНИМАНИЕ: Это удалит историю миграции!
docker-compose exec app psql -U loyalty_user -d loyalty_db -c "\
DELETE FROM flyway_schema_history WHERE version = '1';"

# Перезапустить приложение - V1 применится заново
docker-compose restart app
```

## ✅ Решение 3: Откатить изменения в V1 (САМОЕ БЕЗОПАСНОЕ)

Вернуть строку обратно в `V1__restore_october_discounts.sql`:

```sql
UPDATE users u
SET 
    discount_level = ...,
    discount_earned_at = '2024-11-01 00:00:00'::timestamp,
    accumulated_amount = 0.0  -- ← Вернуть эту строку
FROM october_purchases_temp op
WHERE ...
```

Затем:
```bash
git add src/main/resources/db/migration/V1__restore_october_discounts.sql
git commit -m "fix: restore V1 migration original state"
git push

# На сервере
git pull
docker-compose restart app
```

## 🎯 Рекомендация

**Используйте Решение 1 (Flyway Repair)** - самое быстрое и безопасное:

```bash
docker-compose exec app psql -U loyalty_user -d loyalty_db -c "\
UPDATE flyway_schema_history \
SET checksum = 1838735447 \
WHERE version = '1';"

docker-compose restart app
```

## ✅ Проверка

После применения решения:

```bash
# Проверьте что приложение запустилось
docker-compose ps

# Проверьте логи
docker-compose logs -f --tail=50
```

Должно запуститься без ошибок!

## 📝 На будущее

**НИКОГДА не изменяйте применённые миграции!**

Если нужно что-то изменить - создавайте новую миграцию (V3, V4, и т.д.)


