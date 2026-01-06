# 💡 Примеры использования

## 📱 Сценарии использования

### Сценарий 1: Регистрация нового клиента

**Действия клиента:**
1. Находит бота в Telegram
2. Отправляет `/start`
3. Видит сообщение:
   ```
   👋 Добро пожаловать в программу лояльности!
   
   Для регистрации, пожалуйста, поделитесь своим номером телефона,
   нажав на кнопку ниже.
   ```
4. Нажимает кнопку "📱 Отправить номер телефона"
5. Получает подтверждение:
   ```
   ✅ Регистрация успешна!
   
   📱 Телефон: +79001234567
   💰 Баллы: 0
   🏆 Уровень: Бронзовый
   
   Используйте кнопки меню для навигации.
   ```

---

### Сценарий 2: Совершение покупки

**Действия клиента:**
1. В магазине перед оплатой нажимает "🛍 Я совершаю покупку"
2. Получает код:
   ```
   🛍 Код для покупки создан!
   
   📋 Ваш код: ABC123
   
   ⏰ Код действителен до: 30.09.2025 15:30
   
   Назовите этот код администратору в магазине для начисления баллов.
   ```
3. Называет код **ABC123** кассиру

**Действия кассира/администратора:**
1. Нажимает в своем боте "🔑 Ввести код покупки"
2. Вводит: `ABC123`
3. Получает подтверждение:
   ```
   ✅ Код успешно активирован!
   
   👤 Клиент: Иван
   💰 Начислено: 10 баллов
   📊 Всего у клиента: 10 баллов
   ```

**Клиент получает уведомление:**
```
✅ Вам начислено 10 баллов!

💰 Ваш баланс: 10 баллов
🏆 Уровень: Бронзовый
```

---

### Сценарий 3: Проверка баланса

**Действия клиента:**
1. Нажимает "💰 Мои баллы"
2. Видит:
   ```
   💰 Ваш баланс баллов
   
   🥉 Уровень: Бронзовый
   💎 Баллы: 450
   
   До серебряного уровня: 50 баллов
   ```

---

### Сценарий 4: Просмотр истории

**Действия клиента:**
1. Нажимает "📜 История операций"
2. Видит последние 10 транзакций:
   ```
   📜 Последние операции:
   
   30.09.2025 14:25
   Начисление: +10 баллов
   Покупка в магазине
   
   29.09.2025 18:30
   Начисление: +10 баллов
   Покупка в магазине
   
   28.09.2025 12:15
   Начисление: +10 баллов
   Покупка в магазине
   ```

---

### Сценарий 5: Получение промокода

**Действия администратора:**
1. Создает акцию "Скидка 15% на все товары"
2. Нажимает "📢 Отправить скидку"
3. Указывает процент скидки и срок действия

**Все клиенты получают:**
```
🎁 Новая скидка для вас!

🏷 Код: SALE2025
💵 Скидка: 15%
📝 Скидка 15% на все товары
⏰ Действует до: 05.10.2025 23:59

Назовите этот код при покупке!
```

**Клиент проверяет свои скидки:**
1. Нажимает "🎁 Мои скидки"
2. Видит все активные промокоды:
   ```
   🎁 Ваши активные скидки:
   
   🏷 Код: SALE2025
   💵 Скидка: 15%
   📝 Скидка 15% на все товары
   ⏰ Действует до: 05.10.2025 23:59
   
   🏷 Код: VIP10OFF
   💵 Скидка: 10%
   📝 VIP скидка для постоянных клиентов
   ⏰ Действует до: 31.12.2025 23:59
   ```

---

## 🎓 Уровни лояльности

### 🥉 Бронзовый (0+ баллов)
- Стандартные условия
- Начисление 10 баллов за покупку
- Доступ к общим акциям

### 🥈 Серебряный (500+ баллов)
- Расширенные привилегии
- Дополнительные промокоды
- Приоритетное обслуживание

### 🥇 Золотой (1000+ баллов)
- VIP статус
- Эксклюзивные предложения
- Максимальные привилегии

---

## 🔧 Кастомизация

### Изменение количества баллов за покупку

В файле `LoyaltyBot.java` (строка ~275):
```java
// Было:
int pointsToAdd = 10;

// Можно сделать динамически:
int pointsToAdd = calculatePoints(purchaseAmount);

// Или зависимость от уровня:
int pointsToAdd = customer.getLoyaltyLevel() == User.LoyaltyLevel.GOLD ? 20 : 10;
```

### Изменение порогов уровней

В файле `application.yml`:
```yaml
loyalty:
  points:
    bronze-threshold: 0      # Бронза с 0 баллов
    silver-threshold: 500    # Серебро с 500 баллов
    gold-threshold: 1000     # Золото с 1000 баллов
```

### Изменение времени действия кода

В файле `application.yml`:
```yaml
loyalty:
  purchase-code:
    expiration-minutes: 15  # Код действует 15 минут вместо 10
```

---

## 📊 SQL запросы для аналитики

### Топ-10 клиентов по баллам
```sql
SELECT first_name, last_name, phone_number, points, loyalty_level
FROM users
WHERE role = 'USER'
ORDER BY points DESC
LIMIT 10;
```

### Количество активных пользователей
```sql
SELECT COUNT(*) as active_users
FROM users
WHERE state = 'REGISTERED' AND role = 'USER';
```

### Статистика по покупкам за сегодня
```sql
SELECT COUNT(*) as purchases_today, SUM(points) as points_earned
FROM transactions
WHERE DATE(created_at) = CURRENT_DATE
AND type = 'EARN';
```

### Распределение по уровням
```sql
SELECT loyalty_level, COUNT(*) as user_count
FROM users
WHERE role = 'USER'
GROUP BY loyalty_level;
```

---

## 🎨 Идеи для улучшения

### 1. Динамическое начисление баллов
```java
// В зависимости от суммы покупки
public int calculatePoints(double purchaseAmount) {
    if (purchaseAmount >= 5000) return 50;
    if (purchaseAmount >= 2000) return 30;
    if (purchaseAmount >= 1000) return 20;
    return 10;
}
```

### 2. Бонусы за день рождения
```java
// Автоматическое начисление в день рождения
@Scheduled(cron = "0 0 9 * * *") // Каждый день в 9:00
public void checkBirthdays() {
    LocalDate today = LocalDate.now();
    users.stream()
        .filter(u -> isBirthday(u, today))
        .forEach(u -> {
            addPoints(u, 50);
            sendBirthdayMessage(u);
        });
}
```

### 3. Реферальная программа
```java
// Код друга при регистрации
public void registerWithReferral(User newUser, String referralCode) {
    User referrer = findByReferralCode(referralCode);
    if (referrer != null) {
        addPoints(referrer, 50);  // Бонус пригласившему
        addPoints(newUser, 25);    // Бонус новому пользователю
    }
}
```

### 4. Обмен баллов на скидку
```java
// 100 баллов = 10% скидка
public DiscountCode exchangePointsForDiscount(User user, int points) {
    if (user.getPoints() < points) {
        throw new IllegalStateException("Недостаточно баллов");
    }
    subtractPoints(user, points);
    int discount = points / 10; // 1 балл = 0.1% скидки
    return createPersonalDiscount(user, discount);
}
```

---

## 🚀 Интеграция с кассовой системой

### REST API endpoint (пример будущей функциональности)

```java
@PostMapping("/api/purchase")
public ResponseEntity<?> registerPurchase(
    @RequestParam String phoneNumber,
    @RequestParam double amount) {
    
    User user = userService.findByPhoneNumber(phoneNumber)
        .orElseThrow(() -> new UserNotFoundException());
    
    int points = calculatePoints(amount);
    userService.addPoints(user, points);
    
    transactionService.createTransaction(
        user, points, TransactionType.EARN, 
        "Покупка на сумму " + amount + " руб.", null, null
    );
    
    return ResponseEntity.ok(Map.of(
        "points_added", points,
        "total_points", user.getPoints(),
        "level", user.getLoyaltyLevel().getDisplayName()
    ));
}
```

---

**Экспериментируйте и адаптируйте бота под свои нужды! 🎯**