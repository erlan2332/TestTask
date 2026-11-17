# TestTask

Основа тестового задания для Java Backend Developer. На первом этапе подготовлены Spring Boot, Gradle, база H2, проверка запуска контекста и CI. Функциональность задач будет добавляться в следующих этапах.

## Технологии

- Java 21
- Spring Boot 3.5.16: Web, Data JPA, Validation, Test
- Gradle 8.14.3 (Groovy DSL и Gradle Wrapper)
- H2 in-memory
- JUnit 5

## Требования

- JDK 21
- Свободный порт 8080
- Интернет при первом запуске Gradle Wrapper для загрузки Gradle и зависимостей

Системная установка Gradle не требуется.
Перед запуском убедитесь, что `JAVA_HOME` указывает на JDK 21. На macOS с Homebrew `openjdk@21`:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

## Локальный запуск

```bash
./gradlew bootRun
```

Приложение запускается на `http://localhost:8080`. База H2 работает в памяти и не требует отдельного сервера.

## Сборка и тесты

```bash
./gradlew clean build
./gradlew test
```

Основная ветка проекта — `task-dev`. Следующие изменения будут вноситься через отдельные ветки и Pull Requests в `task-dev`.
