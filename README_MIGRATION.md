# 🚀 Быстрый гайд по обновлению системы скидок

## Что изменилось?

**Старая система:** Скидки сбрасывались каждый месяц 📅  
**Новая система:** Скидки действуют 30 дней с момента активации и продлеваются при повторном накоплении 🔄

## Установка обновления

### ⚡ Быстрый способ (рекомендуется)

```bash
# 1. Остановите бота
docker-compose down

# 2. Обновите код
git pull

# 3. Пересоберите и запустите
docker-compose up --build -d

# 4. Проверьте логи
docker-compose logs -f | grep -i flyway
```

**Готово!** 🎉 Flyway автоматически восстановит скидки пользователям.

### 📋 Что происходит автоматически:

1. **Hibernate** создаст новые колонки в таблице `users` (ddl-auto: update):
   - `accumulated_amount` - накопленная сумма
   - `discount_earned_at` - дата активации скидки
   - `discount_level` - уровень скидки (5, 7 или 10)

2. **Flyway** создаст baseline для существующей БД:
   - Создаст таблицу `flyway_schema_history`
   - Добавит запись "baseline version 0" (существующая структура)
   - Это безопасно для существующей БД!

3. **Flyway** выполнит миграцию V1:
   - Проверит наличие новых колонок (защита от ошибок)
   - Найдет пользователей с октябрьскими покупками >= 20,000 руб
   - Восстановит их скидки
   - Установит дату активации на 1 ноября 2024

4. **Бот** начнет работать с новой логикой:
   - Пользователи видят срок действия скидки
   - Накопление и продление работает автоматически

### ⚠️ Важно для существующей БД:
- `baseline-on-migrate: true` уже настроен в конфигурации
- Flyway безопасно работает с существующими таблицами
- Миграция проверяет наличие колонок перед выполнением
- Подробнее: см. `FLYWAY_EXISTING_DB.md`

## Проверка

### Проверить миграцию:

```sql
-- Посмотреть статус Flyway
SELECT * FROM flyway_schema_history;

-- Проверить восстановленные скидки
SELECT 
    phone_number,
    first_name,
    discount_level,
    discount_earned_at,
    (discount_earned_at + INTERVAL '30 days') as expires_at
FROM users 
WHERE discount_level IS NOT NULL 
  AND discount_earned_at >= '2024-11-01'::timestamp;
```

### Проверить в боте:

1. Откройте бота как пользователь
2. Нажмите "📊 Мой статус"
3. Должны увидеть:
   - Текущую скидку
   - Дату истечения
   - Накопленную сумму

## Откат (если нужно)

```bash
# Остановите бота
docker-compose down

# Откатите код
git revert HEAD

# Пересоберите
docker-compose up --build -d
```

Или откатите только данные:
```sql
UPDATE users 
SET discount_level = NULL, 
    discount_earned_at = NULL 
WHERE discount_earned_at >= '2024-11-01'::timestamp;
```

## Документация

- 📚 **Полная инструкция:** `DISCOUNT_SYSTEM_UPDATE.md`
- 🔧 **Flyway миграции:** `FLYWAY_MIGRATION.md`
- 📝 **SQL скрипт (ручной):** `scripts/restore-discounts.sql`

## Помощь

**Проблемы с миграцией?**
```bash
# Посмотрите логи
docker-compose logs -f app

# Проверьте статус Flyway
docker-compose exec db psql -U postgres -d loyalty_db -c "SELECT * FROM flyway_schema_history;"
```

**Вопросы?** См. раздел Troubleshooting в `FLYWAY_MIGRATION.md`

