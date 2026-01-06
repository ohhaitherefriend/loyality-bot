# 🔍 Как система определяет код и пользователя?

## ✅ Да, всё автоматически!

Админ **просто вводит код**, система **сама определяет всё**:

### 🎯 Что определяется автоматически:
1. ✅ **Тип операции** (покупка или списание)
2. ✅ **Пользователь** (кому принадлежит код)
3. ✅ **Количество баллов** (для списания)
4. ✅ **Валидность** (не истек ли, не использован ли)

---

## 📊 Структура в БД

### Таблица `purchase_codes` (коды покупки)
```
| id | code   | user_id | status | expires_at          |
|----|--------|---------|--------|---------------------|
| 1  | 483926 | 123     | ACTIVE | 2025-10-01 12:30:00 |
| 2  | 719034 | 456     | ACTIVE | 2025-10-01 12:35:00 |
```
👆 **user_id** - ссылка на пользователя!

### Таблица `spend_codes` (коды списания)
```
| id | code   | user_id | points_to_spend | status | expires_at          |
|----|--------|---------|-----------------|--------|---------------------|
| 1  | 598234 | 123     | 50              | ACTIVE | 2025-10-01 12:40:00 |
| 2  | 812467 | 789     | 100             | ACTIVE | 2025-10-01 12:45:00 |
```
👆 **user_id** + **points_to_spend**!

---

## 🔄 Алгоритм работы

### Клиент создает код:

```
Клиент (ID: 123): нажимает "💳 Потратить баллы"
                 ↓
Бот: "Сколько баллов?"
                 ↓
Клиент: "50"
                 ↓
Система генерирует код "598234"
              И СОХРАНЯЕТ:
              {
                code: "598234",
                user_id: 123,          ← СВЯЗЬ!
                points_to_spend: 50,
                status: ACTIVE
              }
                 ↓
Клиент получает: "Ваш код: 598234"
```

### Админ активирует код:

```
Админ: вводит "598234"
          ↓
┌─────────────────────────────────────────┐
│ Система ищет код в базе данных:         │
│                                         │
│ 1. Ищет в purchase_codes               │
│    WHERE code = '598234'                │
│    ❌ Не найден                         │
│                                         │
│ 2. Ищет в spend_codes                  │
│    WHERE code = '598234'                │
│    ✅ НАЙДЕН!                           │
│                                         │
│    Получен объект:                      │
│    SpendCode {                          │
│      code: "598234",                    │
│      user: User(id=123, name="Иван"),  │  ← ВОТ ОНА!
│      pointsToSpend: 50                  │
│    }                                    │
└─────────────────────────────────────────┘
          ↓
Система АВТОМАТИЧЕСКИ:
  1. Определяет: это код СПИСАНИЯ
  2. Извлекает: user = User(id=123)
  3. Списывает: 50 баллов у пользователя 123
  4. Создает транзакцию
  5. Уведомляет клиента
```

---

## 💻 Код в LoyaltyBot.java

### Админ вводит код:
```java
private void handleAdminCodeInput(Long chatId, String code, User admin) {
    String trimmedCode = code.trim();
    
    // 1️⃣ Сначала ищем в кодах покупки
    Optional<PurchaseCode> purchaseCodeOpt = 
        purchaseCodeService.findByCode(trimmedCode);
    
    if (purchaseCodeOpt.isPresent()) {
        PurchaseCode purchaseCode = purchaseCodeOpt.get();
        User customer = purchaseCode.getUser(); // ← СВЯЗЬ!
        // Начисляем баллы customer
    }
    
    // 2️⃣ Если нет, ищем в кодах списания
    Optional<SpendCode> spendCodeOpt = 
        spendCodeService.findByCode(trimmedCode);
    
    if (spendCodeOpt.isPresent()) {
        SpendCode spendCode = spendCodeOpt.get();
        User customer = spendCode.getUser(); // ← СВЯЗЬ!
        int points = spendCode.getPointsToSpend(); // ← СУММА!
        // Списываем points у customer
    }
}
```

---

## 🎯 Примеры работы

### Пример 1: Код покупки

```
Клиент Иван (ID: 123):
  "🛍 Я совершаю покупку"
  → Код: 483926

БД: INSERT INTO purchase_codes 
    (code, user_id, status) 
    VALUES ('483926', 123, 'ACTIVE')

---

Админ:
  "🔑 Ввести код"
  → Вводит: 483926

Система:
  1. SELECT * FROM purchase_codes WHERE code = '483926'
     ✅ Найден! user_id = 123
  2. SELECT * FROM users WHERE id = 123
     ✅ Иван
  3. UPDATE users SET points = points + 10 WHERE id = 123
  4. Уведомление Ивану: "+10 баллов!"
```

### Пример 2: Код списания

```
Клиент Мария (ID: 456, баллы: 150):
  "💳 Потратить баллы"
  → "Сколько?" → "50"
  → Код: 598234

БД: INSERT INTO spend_codes 
    (code, user_id, points_to_spend, status) 
    VALUES ('598234', 456, 50, 'ACTIVE')

---

Админ:
  "🔑 Ввести код"
  → Вводит: 598234

Система:
  1. SELECT * FROM purchase_codes WHERE code = '598234'
     ❌ Не найден
  2. SELECT * FROM spend_codes WHERE code = '598234'
     ✅ Найден! user_id = 456, points = 50
  3. SELECT * FROM users WHERE id = 456
     ✅ Мария
  4. UPDATE users SET points = points - 50 WHERE id = 456
     (150 - 50 = 100)
  5. Уведомление Марии: "Списано 50 баллов! Остаток: 100"
```

---

## 🔒 Безопасность

### ✅ Гарантии системы:

1. **Уникальность кода**
   - Каждый код уникален в БД (UNIQUE constraint)
   
2. **Невозможно подделать**
   - Коды генерируются криптографически стойким генератором
   
3. **Нельзя использовать дважды**
   - После активации: status = USED
   
4. **Автоматическое истечение**
   - Через 10 минут: status = EXPIRED
   
5. **Правильный пользователь**
   - Связь в БД гарантирует, что баллы спишутся/начислятся правильному человеку

---

## 📝 SQL запросы для проверки

### Посмотреть активные коды покупки:
```sql
SELECT 
    pc.code,
    u.first_name,
    u.phone_number,
    pc.status,
    pc.expires_at
FROM purchase_codes pc
JOIN users u ON pc.user_id = u.id
WHERE pc.status = 'ACTIVE';
```

### Посмотреть активные коды списания:
```sql
SELECT 
    sc.code,
    u.first_name,
    u.phone_number,
    sc.points_to_spend,
    sc.status,
    sc.expires_at
FROM spend_codes sc
JOIN users u ON sc.user_id = u.id
WHERE sc.status = 'ACTIVE';
```

---

## 🎉 Итого

**Админу не нужно знать ничего**, кроме самого кода!

Система автоматически:
✅ Определит тип операции  
✅ Найдет пользователя  
✅ Выполнит правильное действие  
✅ Уведомит клиента  
✅ Создаст транзакцию  

**Это и есть магия связей в реляционной БД! 🪄**
