# ========================================
# Stage 1: Build
# ========================================
FROM maven:3.8.7-eclipse-temurin-17 AS build

WORKDIR /build

# Копируем pom.xml и загружаем зависимости (для кеширования слоя)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Копируем исходники
COPY src ./src

# Собираем приложение (создается fat JAR)
RUN mvn clean package -DskipTests

# Проверяем что JAR создан
RUN ls -lah /build/target/

# ========================================
# Stage 2: Runtime
# ========================================
FROM eclipse-temurin:17-jre-jammy

# Метаданные образа
LABEL maintainer="your-email@example.com"
LABEL description="Loyalty Bot - Telegram bot for loyalty program"
LABEL version="1.0.0"

# Создаем непривилегированного пользователя для безопасности
RUN groupadd -r spring && useradd -r -g spring spring

WORKDIR /app

# Копируем собранный JAR из build stage
# Spring Boot создает loyalty-bot-1.0.0.jar (fat JAR со всеми зависимостями)
COPY --from=build /build/target/loyalty-bot-*.jar app.jar

# Создаем директорию для данных (если используется H2 file-based)
RUN mkdir -p /app/data && chown -R spring:spring /app

# Переключаемся на непривилегированного пользователя
USER spring:spring

# Открываем порт приложения
EXPOSE 8080

# JVM параметры для оптимальной работы в контейнере
ENV JAVA_OPTS="\
    -Xms256m \
    -Xmx512m \
    -XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC \
    -XX:+UseStringDeduplication \
    -Djava.security.egd=file:/dev/./urandom"

# Точка входа (БЕЗ sh -c для избежания exec format error)
ENTRYPOINT ["java", "-jar", "app.jar"]

# Можно передать JAVA_OPTS через CMD
CMD []
