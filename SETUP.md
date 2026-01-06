# 🚀 Инструкция по установке и запуску

## Быстрый старт

### 1. Создайте Telegram бота

1. Откройте Telegram и найдите бота [@BotFather](https://t.me/BotFather)
2. Отправьте команду `/newbot`
3. Следуйте инструкциям:
   - Введите имя бота (например: "My Loyalty Bot")
   - Введите username бота (должен заканчиваться на "bot", например: "my_loyalty_bot")
4. Сохраните полученный **token** и **username**

### 2. Настройте проект

```bash
# Клонируйте или перейдите в директорию проекта
cd plstk-wrld-bot

# Скопируйте пример конфигурации
cp .env.example .env

# Откройте .env и заполните данными вашего бота
nano .env  # или используйте любой текстовый редактор
```

В файле `.env` замените:
```
TELEGRAM_BOT_TOKEN=your_bot_token_here
TELEGRAM_BOT_USERNAME=your_bot_username_here
```

На реальные значения, полученные от BotFather.

### 3. Запустите приложение

#### Вариант A: С помощью скрипта (Linux/Mac)

```bash
# Сделайте скрипт исполняемым
chmod +x run.sh

# Запустите
./run.sh
```

#### Вариант B: Вручную

```bash
# Загрузите переменные окружения (Linux/Mac)
export $(cat .env | grep -v '^#' | xargs)

# Или для Windows PowerShell:
# Get-Content .env | ForEach-Object { if($_ -notmatch '^#') { $name, $value = $_.Split('='); [Environment]::SetEnvironmentVariable($name, $value) } }

# Соберите проект
mvn clean install

# Запустите
mvn spring-boot:run
```

### 4. Проверьте работу

1. Найдите вашего бота в Telegram по username
2. Отправьте `/start`
3. Следуйте инструкциям для регистрации

Приложение запущено! 🎉

---

## Создание первого администратора

После регистрации в боте как обычный пользователь:

### Способ 1: Через H2 Console (для разработки)

1. Откройте http://localhost:8080/h2-console
2. Введите параметры подключения:
   - JDBC URL: `jdbc:h2:file:./data/loyaltydb`
   - Username: `sa`
   - Password: (оставьте пустым)
3. Нажмите "Connect"
4. Выполните SQL:
```sql
UPDATE USERS SET ROLE = 'ADMIN' WHERE PHONE_NUMBER = '+79001234567';
```
(замените номер телефона на свой)

### Способ 2: Узнать свой Chat ID

1. Напишите боту [@userinfobot](https://t.me/userinfobot) в Telegram
2. Он вернет ваш Chat ID
3. Используйте этот ID для создания админа через SQL

### Способ 3: Из логов приложения

После отправки `/start` вашему боту, посмотрите в консоль - там будет строка с вашим chat_id.

---

## Запуск с PostgreSQL (Production)

### 1. Запустите PostgreSQL

#### Вариант A: С Docker Compose

```bash
# Запустить только БД
docker-compose up -d postgres

# Или всё приложение с БД
docker-compose up -d
```

#### Вариант B: Локальная установка PostgreSQL

```bash
# Создайте базу данных
createdb loyalty_db

# Или через psql
psql -U postgres -c "CREATE DATABASE loyalty_db;"
```

### 2. Обновите application.yml

Используйте профиль `prod`:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

Или установите переменные окружения в `.env`:
```
DATABASE_URL=jdbc:postgresql://localhost:5432/loyalty_db
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=your_password
```

---

## Развертывание на сервере

### Docker (рекомендуется)

```bash
# Создайте .env файл на сервере с вашими токенами

# Запустите
docker-compose up -d

# Проверьте логи
docker-compose logs -f app
```

### Обычный запуск

```bash
# Соберите JAR
mvn clean package

# Запустите
java -jar target/loyalty-bot-1.0.0.jar --spring.profiles.active=prod
```

### Systemd Service (Linux)

Создайте файл `/etc/systemd/system/loyalty-bot.service`:

```ini
[Unit]
Description=Loyalty Telegram Bot
After=network.target

[Service]
Type=simple
User=loyaltybot
WorkingDirectory=/opt/loyalty-bot
ExecStart=/usr/bin/java -jar /opt/loyalty-bot/loyalty-bot-1.0.0.jar --spring.profiles.active=prod
Restart=on-failure
Environment="TELEGRAM_BOT_TOKEN=your_token"
Environment="TELEGRAM_BOT_USERNAME=your_username"

[Install]
WantedBy=multi-user.target
```

Затем:
```bash
sudo systemctl daemon-reload
sudo systemctl enable loyalty-bot
sudo systemctl start loyalty-bot
sudo systemctl status loyalty-bot
```

---

## Решение проблем

### Бот не отвечает

1. Проверьте, что приложение запущено: `curl http://localhost:8080/actuator/health` (если включен actuator)
2. Проверьте логи на наличие ошибок
3. Убедитесь, что токен бота правильный
4. Проверьте, что бот не заблокирован в Telegram

### Ошибки базы данных

1. Убедитесь, что директория `data/` существует
2. Проверьте права доступа к файлу БД
3. При переходе на PostgreSQL убедитесь, что БД создана и доступна

### Ошибки при сборке

```bash
# Очистите кеш Maven
mvn clean

# Обновите зависимости
mvn dependency:purge-local-repository

# Пересоберите
mvn clean install
```

---

## Полезные команды

```bash
# Просмотр логов
tail -f logs/spring.log

# Проверка состояния БД (H2)
ls -lh data/

# Бэкап БД (PostgreSQL)
pg_dump -U postgres loyalty_db > backup.sql

# Восстановление БД (PostgreSQL)
psql -U postgres loyalty_db < backup.sql

# Просмотр активных пользователей
# Выполните в H2 Console или psql:
# SELECT * FROM USERS WHERE STATE = 'REGISTERED';
```

---

## Что дальше?

✅ Протестируйте все функции бота
✅ Создайте администратора
✅ Настройте количество баллов за покупку (в коде)
✅ Кастомизируйте сообщения бота
✅ Настройте production БД
✅ Настройте бэкапы
✅ Добавьте мониторинг

**Готово! Ваш бот программы лояльности работает! 🎉**