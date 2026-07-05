# 🤖 Заботик — Telegram-бот программы лояльности

Телеграм-бот программы лояльности на Spring Boot для автоматизации системы начисления баллов и управления скидками.

## ✨ Функционал

### Для пользователей:
- 📱 **Регистрация** - быстрая регистрация по номеру телефона
- 🛍 **Покупки** - генерация уникального кода для покупки (действует 10 минут)
- 💰 **Баллы** - просмотр текущего баланса баллов
- 🏆 **Уровни лояльности** - Бронзовый (0+), Серебряный (500+), Золотой (1000+)
- 📜 **История** - просмотр последних транзакций
- 🎁 **Скидки** - получение персональных промокодов

### Для администраторов:
- 🔑 **Активация кодов** - ввод кода покупки для начисления баллов клиенту
- 📢 **Рассылка скидок** - отправка уникальных промокодов всем пользователям
- 📊 **Статистика** - просмотр подробной статистики программы лояльности (количество пользователей, распределение по уровням скидок, общая сумма покупок)

## 🚀 Технологии

- **Java 17**
- **Spring Boot 3.1.5**
- **Spring Data JPA**
- **H2 Database** (или PostgreSQL для продакшена)
- **Telegram Bots API 6.8.0**
- **Lombok**
- **ZXing** (для генерации QR-кодов - опционально)

## 📋 Предварительные требования

- Java 17 или выше
- Maven 3.6+
- Telegram Bot Token (получить у [@BotFather](https://t.me/BotFather))

## ⚙️ Настройка

### 1. Создание Telegram бота

1. Откройте [@BotFather](https://t.me/BotFather) в Telegram
2. Отправьте команду `/newbot`
3. Следуйте инструкциям для создания бота
4. Сохраните полученный **token**
5. Сохраните **username** бота

### 2. Конфигурация приложения

Создайте файл `src/main/resources/application-local.yml`:

```yaml
telegram:
  bot:
    token: "YOUR_BOT_TOKEN_HERE"
    username: "YOUR_BOT_USERNAME_HERE"

spring:
  datasource:
    url: jdbc:h2:file:./data/loyaltydb
    driver-class-name: org.h2.Driver
    username: sa
    password: 
```

Или установите переменные окружения:

```bash
export TELEGRAM_BOT_TOKEN="your_bot_token"
export TELEGRAM_BOT_USERNAME="your_bot_username"
```

### 3. Запуск приложения

```bash
# Сборка
mvn clean install

# Запуск
mvn spring-boot:run

# Или с профилем
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## 📱 Использование

### Для клиента:

1. Найдите вашего бота в Telegram
2. Отправьте `/start`
3. Поделитесь номером телефона для регистрации
4. Используйте кнопки меню:
   - **🛍 Я совершаю покупку** - получить код для администратора
   - **💰 Мои баллы** - посмотреть баланс
   - **📜 История операций** - история начисления/списания
   - **🎁 Мои скидки** - активные промокоды

### Для администратора:

1. Вручную присвойте роль ADMIN пользователю в базе данных:
```sql
UPDATE users SET role = 'ADMIN' WHERE phone_number = '+79001234567';
```

2. Используйте админские кнопки:
   - **🔑 Ввести код покупки** - активировать код клиента и начислить баллы
   - **📢 Отправить скидку** - создать промокоды для всех пользователей
   - **📊 Статистика** - просмотр статистики (в разработке)

## 🗄️ База данных

### H2 Console (для разработки)

Доступна по адресу: `http://localhost:8080/h2-console`

- JDBC URL: `jdbc:h2:file:./data/loyaltydb`
- Username: `sa`
- Password: (пустой)

### Переход на PostgreSQL (для продакшена)

Обновите `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/loyalty_db
    username: your_username
    password: your_password
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
```

## 🔧 Настройка параметров

В `application.yml` можно настроить:

```yaml
loyalty:
  purchase-code:
    expiration-minutes: 10  # Время действия кода покупки
    length: 6               # Длина кода
  discount-code:
    length: 8               # Длина промокода
  points:
    bronze-threshold: 0     # Порог для бронзового уровня
    silver-threshold: 500   # Порог для серебряного уровня
    gold-threshold: 1000    # Порог для золотого уровня
```

## 📊 Структура базы данных

- **users** - пользователи (клиенты и администраторы)
- **purchase_codes** - коды покупок
- **discount_codes** - промокоды со скидками
- **transactions** - история всех операций с баллами

## 🐳 Docker и деплой

### Локальный запуск через Docker

```bash
# Создайте .env файл
cp env.example .env
nano .env  # Заполните переменные

# Запустите через Docker Compose
docker-compose up -d

# Проверьте логи
docker-compose logs -f
```

### Деплой в Яндекс Облако

**⚡ Быстрый старт через Docker Hub (рекомендуется):**

```bash
# 1. Соберите и опубликуйте в Docker Hub
./docker-build-push.sh

# 2. На виртуалке в Яндекс Облаке:
docker pull your-username/loyalty-bot:latest
docker-compose up -d
```

**📚 Детальные инструкции:**
- **[DEPLOY_DOCKER_HUB.md](DEPLOY_DOCKER_HUB.md)** - ⭐ Деплой через Docker Hub (самый простой)
- **[DOCKER_BUILD_ARCH.md](DOCKER_BUILD_ARCH.md)** - Про архитектуры и совместимость (важно для Mac M1/M2!)
- **[DEPLOY_YANDEX_CLOUD.md](DEPLOY_YANDEX_CLOUD.md)** - Деплой через Yandex Container Registry
- **[QUICK_DEPLOY_YANDEX.md](QUICK_DEPLOY_YANDEX.md)** - Быстрый деплой через Yandex Registry

**⚠️ Важно:** Если вы на Mac M1/M2, скрипт `docker-build-push.sh` автоматически собирает образ для AMD64 архитектуры (совместимо с облаком).

### Другие варианты деплоя

```bash
# Docker Hub
./docker-build-push.sh

# Docker Compose на любом сервере
docker-compose -f docker-compose.prod.yml up -d
```

## 🛠️ Дальнейшее развитие

### Реализовано:

- [x] ✅ Статистика для администраторов
- [x] ✅ Уведомления о новых акциях
- [x] ✅ Система накопительных скидок

### Планируемые улучшения:

- [ ] QR-коды вместо текстовых кодов
- [ ] Настраиваемое количество баллов за покупку
- [ ] Система достижений и бонусов
- [ ] Экспорт данных в Excel/CSV
- [ ] Возможность обмена баллов на скидки
- [ ] Multi-tenant поддержка (несколько магазинов)
- [ ] REST API для интеграции с кассовыми системами
- [ ] Web-панель администратора
- [ ] Расширенная аналитика (графики, тренды)
- [ ] История изменения статистики по дням/неделям

### Идеи для улучшения UX:

- Геолокация для проверки покупок в магазине
- Интеграция с платежными системами
- Push-уведомления о специальных предложениях
- Реферальная программа
- Игрофикация (челленджи, бейджи)

## 🤝 Вклад

Предложения и pull requests приветствуются!

## 📄 Лицензия

MIT License

## 📞 Контакты

При возникновении вопросов создайте issue в репозитории.

---

**Разработано с ❤️ на Spring Boot**