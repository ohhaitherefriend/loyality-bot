# 🚀 Деплой V2: Транзакции с суммами

## ⚡ Быстрый старт

```bash
# 1. Обновить код
git add .
git commit -m "refactor: store amount in transactions, calculate from DB"
git push

# 2. На сервере
cd /path/to/plstk-wrld-bot
git pull

# 3. Пересобрать и запустить
docker-compose down
docker-compose up --build -d

# 4. Проверить миграции
docker-compose logs -f | grep "Migration"
```

## ✅ Что должно произойти

### Миграция V1 (если еще не применена):
```
V1 Migration: Создана колонка discount_level
V1 Migration: Создана колонка discount_earned_at
V1 Migration: Восстановлено скидок: X пользователям
```

### Миграция V2 (новая):
```
V2 Migration: Создана колонка amount в transactions
V2 Migration: Обновлено транзакций: X из Y
```

## 🔍 Проверка

### 1. Проверьте БД
```bash
docker-compose exec app psql -U loyalty_user -d loyalty_db
```

```sql
-- Проверьте колонку amount
SELECT COUNT(*), SUM(amount), AVG(amount) 
FROM transactions 
WHERE amount IS NOT NULL;

-- Проверьте что description распарсился
SELECT description, amount 
FROM transactions 
LIMIT 5;

-- Проверьте статус миграций
SELECT * FROM flyway_schema_history ORDER BY installed_on DESC;
```

### 2. Проверьте бота
- `/stats` - должна показываться "Продажи за текущий месяц"
- `Мой статус` - должна показываться накопленная сумма
- Создайте тестовую покупку - проверьте что сумма обновляется

## ⚠️ Проблемы

### amount = NULL в новых транзакциях
**Причина**: Код не обновлен, старая версия без amount

**Решение**:
```bash
docker-compose down
docker-compose up --build -d
```

### Старые транзакции без amount
**Причина**: description имеет другой формат

**Решение**: Вручную заполните amount
```sql
-- Проверьте формат description
SELECT DISTINCT LEFT(description, 50) FROM transactions WHERE amount IS NULL;

-- Если формат другой, измените regexp в V2 миграции
```

### Скидки не активируются
**Причина**: amount не заполнен

**Решение**:
```sql
-- Проверьте transactions
SELECT user_id, COUNT(*), SUM(amount) 
FROM transactions 
GROUP BY user_id 
HAVING SUM(amount) >= 20000;

-- Если amount=NULL, примените V2 миграцию вручную
```

## 📋 Checklist

- [ ] Код закоммичен и запушен
- [ ] Docker контейнер пересобран
- [ ] V1 миграция применена
- [ ] V2 миграция применена
- [ ] Колонка `amount` создана
- [ ] Старые транзакции заполнены
- [ ] Новые транзакции содержат amount
- [ ] Статистика показывает "Продажи за текущий месяц"
- [ ] Накопленная сумма считается правильно
- [ ] Скидки активируются/продлеваются

## 🎯 Итоговая проверка

```bash
# Проверьте что приложение работает
docker-compose ps

# Проверьте логи
docker-compose logs -f --tail=50

# Проверьте БД
docker-compose exec app psql -U loyalty_user -d loyalty_db -c "\
SELECT \
  (SELECT COUNT(*) FROM users WHERE discount_level IS NOT NULL) as users_with_discount, \
  (SELECT COUNT(*) FROM transactions) as total_transactions, \
  (SELECT COUNT(*) FROM transactions WHERE amount IS NOT NULL) as transactions_with_amount, \
  (SELECT SUM(amount) FROM transactions WHERE created_at >= date_trunc('month', CURRENT_DATE)) as month_sales;"
```

Должно показать:
- `users_with_discount` > 0 (есть пользователи со скидками)
- `total_transactions` > 0 (есть транзакции)
- `transactions_with_amount` = `total_transactions` (все транзакции с amount)
- `month_sales` > 0 (есть продажи за месяц)

## ✨ Готово!

Если все проверки прошли - система работает правильно! 🎉


