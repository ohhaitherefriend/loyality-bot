# 🔧 Решение проблем

## ❌ Проблема: "Telegram бот не инициализируется"

### Симптомы:
- Spring Boot запускается успешно
- База данных подключается
- НО в логах нет сообщений о регистрации бота
- Бот не отвечает в Telegram

### Причина:
Отсутствовала конфигурация для регистрации бота в Telegram API.

### Решение:
Добавлен класс `BotInitializer.java`, который регистрирует бота при старте приложения.

### Проверка:
После запуска вы должны увидеть в логах:
```
INFO  --- Initializing Telegram Bot...
INFO  --- ✅ Telegram Bot registered successfully! Username: your_bot_username
INFO  --- 🤖 Bot is ready to receive messages!
```

---

## ❌ Ошибка: "Error registering bot"

### Возможные причины:

#### 1. Неверный токен бота

**Симптомы:**
```
Error registering bot: 401 Unauthorized
```

**Решение:**
1. Проверьте токен в файле `.env` или `application.yml`
2. Убедитесь, что токен скопирован полностью без пробелов
3. Получите новый токен у [@BotFather](https://t.me/BotFather) командой `/token`

#### 2. Нет интернет-соединения

**Симптомы:**
```
Error registering bot: Connection refused
```

**Решение:**
- Проверьте интернет-соединение
- Проверьте, не блокирует ли файрвол доступ к api.telegram.org

#### 3. Токен не установлен

**Симптомы:**
```
botToken must not be empty
```

**Решение:**
1. Создайте файл `.env` из `env.example`:
   ```bash
   cp env.example .env
   ```
2. Заполните `TELEGRAM_BOT_TOKEN` в `.env`
3. Перезапустите приложение

---

## ❌ Ошибка: "not-null property references a null or transient value: User.points"

### Симптомы:
```
org.hibernate.PropertyValueException: not-null property references a null or transient value : com.plstk.loyaltybot.entity.User.points
```

### Причина:
При создании пользователя не устанавливались обязательные поля (`points`, `role`, `loyaltyLevel`).

### Решение:
**Уже исправлено!** При создании User теперь устанавливаются все обязательные поля:
```java
User newUser = User.builder()
    .chatId(chatId)
    .phoneNumber("")
    .points(0)              // ← добавлено
    .role(User.UserRole.USER)           // ← добавлено
    .loyaltyLevel(User.LoyaltyLevel.BRONZE)  // ← добавлено
    .state(User.UserState.AWAITING_PHONE)
    .build();
```

**Если у вас старая версия:** перезагрузите код из репозитория.

---

## ❌ База данных не создается

### Симптомы:
```
Error creating database
```

### Решение:
```bash
# Создайте директорию для базы данных
mkdir -p data

# Дайте права на запись
chmod 755 data

# Перезапустите приложение
```

---

## ❌ Порт 8080 занят

### Симптомы:
```
Port 8080 is already in use
```

### Решение 1: Измените порт
В `application.yml`:
```yaml
server:
  port: 8081  # Или любой другой свободный порт
```

### Решение 2: Остановите другое приложение
```bash
# Найдите процесс на порту 8080
lsof -i :8080

# Остановите процесс
kill -9 PID
```

---

## ❌ Бот не отвечает на сообщения

### Проверьте:

1. **Бот запущен?**
   ```bash
   # В логах должно быть:
   # "Bot is ready to receive messages!"
   ```

2. **Правильный username?**
   - Проверьте `TELEGRAM_BOT_USERNAME` в `.env`
   - Должен совпадать с username в @BotFather

3. **Бот не заблокирован?**
   - Напишите боту `/start`
   - Если появляется "Bot was blocked by the user" - разблокируйте бота

4. **Проверьте логи на ошибки:**
   ```bash
   tail -f logs/spring.log
   ```

---

## ❌ H2 Console не открывается

### Симптомы:
`http://localhost:8080/h2-console` не открывается

### Решение:
1. Убедитесь, что приложение запущено
2. Проверьте в `application.yml`:
   ```yaml
   spring:
     h2:
       console:
         enabled: true
         path: /h2-console
   ```

3. Попробуйте: `http://localhost:8080/h2-console/`

---

## ❌ "Failed to load ApplicationContext"

### Симптомы:
Приложение не запускается с ошибкой Spring

### Возможные причины:

#### 1. Отсутствуют зависимости
```bash
mvn clean install
```

#### 2. Ошибка в конфигурации
Проверьте `application.yml` на синтаксические ошибки (отступы!)

#### 3. Конфликт версий Java
```bash
java -version
# Должна быть Java 17 или выше
```

---

## ❌ Ошибки Hibernate/JPA

### Симптомы:
```
Schema validation failed
```

### Решение:
Удалите базу данных и перезапустите:
```bash
rm -rf data/
mkdir data
mvn spring-boot:run
```

---

## 🐛 Включить отладку

Добавьте в `application.yml`:
```yaml
logging:
  level:
    com.plstk: DEBUG
    org.telegram: DEBUG
    org.springframework: DEBUG
```

Перезапустите приложение и проверьте детальные логи.

---

## 📞 Полезные команды

### Просмотр логов в реальном времени
```bash
tail -f logs/spring.log
```

### Проверка портов
```bash
# Mac/Linux
lsof -i :8080

# Windows
netstat -ano | findstr :8080
```

### Проверка Java версии
```bash
java -version
mvn -version
```

### Очистка и пересборка
```bash
mvn clean
mvn install
mvn spring-boot:run
```

### Проверка подключения к Telegram API
```bash
curl https://api.telegram.org/bot<YOUR_TOKEN>/getMe
```

---

## ✅ Чек-лист диагностики

Если бот не работает, проверьте по порядку:

- [ ] Java 17+ установлена
- [ ] Maven установлен
- [ ] Файл `.env` создан и заполнен
- [ ] Токен бота правильный
- [ ] Username бота правильный  
- [ ] Порт 8080 свободен
- [ ] Интернет работает
- [ ] Директория `data/` существует
- [ ] В логах: "Bot registered successfully"
- [ ] В логах нет ошибок (ERROR)
- [ ] Бот не заблокирован в Telegram

---

## 📚 Дополнительная информация

- [README.md](README.md) - Основная документация
- [QUICKSTART.md](QUICKSTART.md) - Быстрый старт
- [SETUP.md](SETUP.md) - Подробная установка

---

**Если проблема не решается:**

1. Проверьте логи полностью
2. Поищите ошибку в Google
3. Проверьте [Telegram Bot API Status](https://t.me/BotNews)
4. Пересоздайте бота у @BotFather