# Рефакторинг: Хранение суммы покупок в транзакциях

## 📅 Дата: 10 ноября 2025

## 🎯 Что изменилось

### ❌ Было (НЕПРАВИЛЬНО):
- Сумма покупок хранилась в поле `User.accumulatedAmount`
- При каждой покупке счетчик увеличивался
- При активации/продлении скидки счетчик сбрасывался в 0
- **Проблема**: Дублирование данных, сложная логика, потеря истории

### ✅ Стало (ПРАВИЛЬНО):
- Сумма покупки хранится в `Transaction.amount`
- Накопленная сумма **считается динамически** из транзакций
- Единый источник правды - таблица `transactions`
- **Преимущества**: Нет дублирования, простая логика, полная история

## 🔄 Миграции Flyway

### V1: Восстановление скидок за октябрь
**Файл**: `V1__restore_october_discounts.sql`

- Создает колонки `discount_level`, `discount_earned_at` если их нет
- Восстанавливает скидки пользователям на основе покупок за октябрь 2024
- Устанавливает дату активации на 1 ноября 2024

### V2: Добавление поля amount в транзакции
**Файл**: `V2__add_amount_to_transactions.sql`

- Создает колонку `amount` в таблице `transactions`
- **Парсит существующие транзакции** и заполняет `amount` из description
- Формат description: `"Покупка на 12345.67 руб. (скидка ...)"`
- Использует regexp: `'Покупка на ([0-9.]+) руб'`

## 📊 Изменения в коде

### 1. Entity: User.java
- ❌ Удалено: `accumulatedAmount` (Double)
- ❌ Deprecated: `monthlySpent`, `lastMonthReset`
- ✅ Остается: `discountLevel`, `discountEarnedAt`

### 2. Entity: Transaction.java  
- ✅ Добавлено: `amount` (Double) - сумма покупки

### 3. Repository: TransactionRepository.java
```java
// Считает сумму покупок пользователя с даты
Double sumAmountByUserAndCreatedAtAfter(User user, LocalDateTime since);

// Считает общую сумму покупок за период
Double sumAmountBetween(LocalDateTime since, LocalDateTime until);
```

### 4. Service: TransactionService.java
```java
// Возвращает накопленную сумму пользователя
double getAccumulatedAmountSince(User user, LocalDateTime since);

// Возвращает общую сумму покупок за текущий месяц
double getTotalAmountForCurrentMonth();
```

### 5. Service: UserService.java
```java
// Проверяет транзакции и активирует/продлевает скидку
User checkAndUpdateDiscount(User user);

// Возвращает накопленную сумму пользователя
double getAccumulatedAmount(User user);

// Обрабатывает покупку (транзакция должна быть создана ДО вызова!)
double processPurchase(User user);
```

### 6. Bot: LoyaltyBot.java
**Порядок операций при покупке**:
```java
// 1. Создаем транзакцию С СУММОЙ
transactionService.createTransaction(
    customer, 0, EARN, description, 
    purchaseAmount,  // ← Сумма покупки!
    purchaseCode, admin
);

// 2. Проверяем и обновляем скидку
userService.processPurchase(customer);

// 3. Получаем обновленные данные
double accumulated = userService.getAccumulatedAmount(customer);
```

## 📈 Статистика

### ❌ Было:
```
💰 Накопления пользователей:
  • Общая накопленная сумма: X руб.
  ℹ️ Сумма идет на получение/продление скидки
```
- Считалась индивидуально для каждого с начала его периода
- Непонятная метрика

### ✅ Стало:
```
💰 Продажи за текущий месяц:
  • Общая сумма: X руб.
  • Средняя на пользователя: Y руб.
```
- Считается с 1-го числа текущего месяца
- Все транзакции за календарный месяц
- Понятная бизнес-метрика

## 🚀 Развертывание

### 1. Обновление кода
```bash
git pull
```

### 2. Сборка
```bash
mvn clean package -DskipTests
```

### 3. Обновление Docker образа
```bash
docker-compose down
docker-compose up --build -d
```

### 4. Проверка миграций
```bash
docker-compose logs -f | grep -i "V2 Migration"
```

Должны увидеть:
```
V2 Migration: Создана колонка amount в transactions
V2 Migration: Обновлено транзакций: X из Y
```

## ✅ Проверка работоспособности

### 1. Проверьте статистику
`/stats` в боте должно показывать:
- "Продажи за текущий месяц" (не "Накопления")
- Реальную сумму покупок за месяц

### 2. Проверьте статус пользователя
`Мой статус` должен показывать:
- Накопленную сумму с начала периода
- Активную скидку (если есть)
- Дату истечения скидки

### 3. Проверьте покупку
1. Создайте код покупки
2. Введите сумму
3. Проверьте, что накопленная сумма обновилась
4. Проверьте, что скидка активировалась/продлилась при достижении порога

## 🐛 Troubleshooting

### Миграция V2 не применилась
```bash
# Проверьте статус миграций
docker-compose exec app psql -U loyalty_user -d loyalty_db -c "SELECT * FROM flyway_schema_history ORDER BY installed_on DESC LIMIT 5;"

# Если V2 нет, проверьте ошибки
docker-compose logs -f | grep -i "error"
```

### Старые транзакции без amount
```sql
-- Вручную заполните amount из description
UPDATE transactions
SET amount = (regexp_match(description, 'Покупка на ([0-9.]+) руб'))[1]::numeric
WHERE description ~ 'Покупка на [0-9.]+ руб'
  AND amount IS NULL;
```

### Накопленная сумма не считается
- Проверьте, что V2 миграция применилась
- Проверьте, что в transactions есть поле `amount`
- Проверьте логи: `docker-compose logs -f | grep -i transaction`

## 📝 Технические детали

### Как работает getAccumulationStartDate()
```java
if (discountEarnedAt != null && isDiscountValid()) {
    // Скидка активна - копим с момента активации
    return discountEarnedAt;
} else if (discountEarnedAt != null) {
    // Скидка истекла - копим с момента истечения
    return discountEarnedAt.plusDays(30);
} else {
    // Скидки никогда не было - копим с регистрации
    return createdAt;
}
```

### Как считается накопленная сумма
```java
LocalDateTime startDate = user.getAccumulationStartDate();
double accumulated = transactionService.getAccumulatedAmountSince(user, startDate);
```

### Когда активируется/продлевается скидка
```java
Integer newDiscountLevel = User.calculateDiscountLevel(accumulated);

if (newDiscountLevel != null) {
    if (!wasDiscountActive) {
        // Активируем новую скидку
        user.setDiscountLevel(newDiscountLevel);
        user.setDiscountEarnedAt(LocalDateTime.now());
    } else if (newDiscountLevel >= oldDiscountLevel) {
        // Продлеваем/повышаем скидку
        user.setDiscountLevel(newDiscountLevel);
        user.setDiscountEarnedAt(LocalDateTime.now());
    }
}
```

## ✨ Итог

Теперь система работает правильно:
- ✅ Все транзакции хранятся в БД с суммой
- ✅ Накопленная сумма считается динамически
- ✅ Нет дублирования данных
- ✅ Единый источник правды
- ✅ Понятная статистика
- ✅ Полная история покупок


