# ==================== ПЕРВАЯ ЧАСТЬ: СБОРКА ====================
# Используем образ Maven с Java 17 для сборки
FROM maven:3.9.4-eclipse-temurin-17 AS builder

# Рабочая директория внутри контейнера
WORKDIR /app

# 1. Копируем только pom.xml сначала (для кеширования зависимостей)
COPY pom.xml .

# 2. Скачиваем все зависимости (кешируем этот слой)
RUN mvn dependency:go-offline -B

# 3. Копируем исходный код
COPY src ./src

# 4. Собираем проект (пропускаем тесты для скорости)
RUN mvn clean package -DskipTests

# ==================== ВТОРАЯ ЧАСТЬ: ЗАПУСК ====================
# Используем легкий образ только с JRE
FROM eclipse-temurin:17-jre-alpine

# Рабочая директория
WORKDIR /app

# Создаем пользователя (безопасность)
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Копируем собранный JAR из первого образа
COPY --from=builder /app/target/link-checker.jar app.jar

# Порт который слушает приложение
EXPOSE 8080

# Команда запуска
ENTRYPOINT ["java", "-jar", "app.jar"]