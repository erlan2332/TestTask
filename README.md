# TestTask

REST-сервис для управления задачами. На текущем этапе доступны создание, получение, полное обновление и удаление задач. Данные хранятся в H2.

## Архитектура

Запрос проходит через `TaskController` → интерфейс `TaskService` → `TaskServiceImpl` → `TaskRepository` → H2. Контроллер работает с DTO и не обращается к JPA напрямую. Сервис управляет транзакциями и преобразует `Task` в `TaskResponse`. Для отсутствующей задачи возвращается HTTP 404.

## Технологии

- Java 21, Spring Boot 3.5.16, Gradle 8.14.3 (Groovy DSL и Wrapper)
- Spring Web MVC, Spring Data JPA, Spring Validation
- H2 in-memory, Hibernate
- JUnit 5, Mockito, Spring Boot Test и MockMvc

## Требования и запуск

Нужны JDK 21, свободный порт 8080 и доступ к интернету при первой загрузке Gradle и зависимостей. Системная установка Gradle не требуется. `JAVA_HOME` должен указывать на JDK 21. Для Homebrew `openjdk@21` на macOS:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew bootRun
```

Сервис будет доступен на `http://localhost:8080`.

## API

| Метод | Путь | Успешный статус | Результат |
|---|---|---:|---|
| POST | `/api/tasks` | 201 Created | Создать задачу; ответ содержит `Location` |
| GET | `/api/tasks` | 200 OK | Список задач; `[]`, если задач нет |
| GET | `/api/tasks/{id}` | 200 OK | Получить задачу |
| PUT | `/api/tasks/{id}` | 200 OK | Полностью обновить редактируемые поля |
| DELETE | `/api/tasks/{id}` | 204 No Content | Удалить задачу; тело ответа пустое |

Статусы задачи: `TODO`, `IN_PROGRESS`, `DONE`. При создании `status` можно опустить: будет использован `TODO`. Для PUT обязательны `title` и `status`; `description: null` очищает описание. `title` не может быть пустым и ограничен 255 символами, `description` — 2000 символами. Некорректные данные или значение статуса дают HTTP 400. Обращение к несуществующему ID даёт HTTP 404.

### Создание

```bash
curl -i -X POST http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"title":"Подготовить отчёт","description":"До пятницы"}'
```

Пример ответа `201 Created` (ID и время будут другими):

```json
{
  "id": 1,
  "title": "Подготовить отчёт",
  "description": "До пятницы",
  "status": "TODO",
  "createdAt": "2026-11-17T08:00:00Z",
  "updatedAt": "2026-11-17T08:00:00Z"
}
```

Заголовок `Location` содержит `/api/tasks/1` для этого примера.

### Полное обновление

```bash
curl -i -X PUT http://localhost:8080/api/tasks/1 \
  -H 'Content-Type: application/json' \
  -d '{"title":"Отчёт готов","description":null,"status":"DONE"}'
```

Пример ответа `200 OK`:

```json
{
  "id": 1,
  "title": "Отчёт готов",
  "description": null,
  "status": "DONE",
  "createdAt": "2026-11-17T08:00:00Z",
  "updatedAt": "2026-11-17T08:05:00Z"
}
```

Поля `id`, `createdAt` и `updatedAt` задаются приложением и не входят в запросы.

## H2

Используется база `jdbc:h2:mem:testtask`; внешний сервер БД не нужен. Hibernate создаёт таблицу `tasks` по JPA-сущности. Данные существуют только во время работы процесса приложения и удаляются после его завершения. H2 Console по умолчанию не включена.

## Сборка и тесты

```bash
./gradlew clean build
./gradlew test
```

`build` уже запускает тесты. Unit-тесты проверяют сервис с Mockito; интеграционные тесты проверяют HTTP API и JPA на H2. GitHub Actions запускает `clean build` при push в `task-dev` и при Pull Request в `task-dev`.

Основная ветка проекта — `task-dev`. Изменения вносятся через отдельные ветки и Pull Requests в `task-dev`.
