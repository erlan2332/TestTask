# TestTask

REST-сервис для управления задачами и получения списка объектов из внешнего API. Задачи хранятся в H2; список задач кэшируется в Redis; внешние объекты в базу не записываются.

## Архитектура

Запрос проходит через `TaskController` → интерфейс `TaskService` → `TaskServiceImpl` → `TaskRepository` → H2. Контроллер работает с DTO и не обращается к JPA напрямую. Сервис управляет транзакциями и преобразует `Task` в `TaskResponse`. Для отсутствующей задачи возвращается HTTP 404.

Для внешних объектов используется отдельный путь: `ExternalApiController` → `ExternalApiService` → `ExternalApiServiceImpl` → внешний REST API. Запрос выполняется только при обращении к эндпоинту.

## Технологии

- Java 21, Spring Boot 3.5.16, Gradle 8.14.3 (Groovy DSL и Wrapper)
- Spring Web MVC, Spring Data JPA, Spring Validation
- Spring Security (HTTP Basic, BCrypt)
- Spring Cache, Spring Data Redis
- H2 in-memory, Hibernate
- JUnit 5, Mockito, Spring Boot Test и MockMvc
- Lombok

## Требования и запуск

Нужны JDK 21, Redis, свободный порт 8080 и доступ к интернету при первой загрузке Gradle и зависимостей. Системная установка Gradle не требуется. `JAVA_HOME` должен указывать на JDK 21. Для Homebrew `openjdk@21` на macOS:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
docker run -d --name testtask-redis -p 127.0.0.1:6379:6379 redis:7-alpine
cp .env.example .env
# Замените значения APP_SECURITY_USERNAME и APP_SECURITY_PASSWORD в .env.
set -a
. ./.env
set +a
./gradlew bootRun
```

Обычный `./gradlew bootRun` не загружает `.env` автоматически: переменные нужно передать процессу приложения, например как показано выше. `.env` не хранится в Git. Без непустых `APP_SECURITY_USERNAME` и `APP_SECURITY_PASSWORD` приложение не запускается. Сервис будет доступен на `http://localhost:8080`.

Если контейнер уже создан, запустите его через `docker start testtask-redis`. Параметры `REDIS_HOST` и `REDIS_PORT` задают адрес Redis; по умолчанию используются `localhost:6379`. Таймаут подключения и команды — по 2 секунды. При недоступности Redis приложение не переключается на локальный кэш.

## Basic Authentication

Авторизация защищает задачи и проксирование внешнего API от анонимных запросов. Для всех маршрутов `/api/tasks`, `/api/tasks/{id}` и `/api/external/objects` требуется HTTP Basic с одним пользователем. Имя и пароль задаются переменными окружения `APP_SECURITY_USERNAME` и `APP_SECURITY_PASSWORD`. В памяти приложения хранится BCrypt-хеш пароля; HTTP-сессия и форма входа не используются.

Пример запроса после запуска (curl интерактивно спросит пароль):

```bash
curl -i -u "$APP_SECURITY_USERNAME" http://localhost:8080/api/tasks
```

Без заголовка Authorization либо с неверным именем или паролем API возвращает `401 Unauthorized` с `WWW-Authenticate: Basic` и безопасным JSON-ответом. С правильными данными сохраняются прежние статусы: `200` для чтения и обновления, `201` для создания, `204` для удаления; ошибки входных данных и бизнес-операций по-прежнему дают `400`, `404`, `405`, `415`, `500` или `502` в соответствующих случаях. Для локальной демонстрации используйте только собственные тестовые credentials; значения в `.env.example` — шаблон.

## Redis Cache

Redis ускоряет только `GET /api/tasks`. При **Cache MISS** приложение читает список из H2 и сохраняет его в Redis. При **Cache HIT** список возвращается из Redis без повторного обращения к `TaskRepository`. Пустой список тоже кэшируется. Другие маршруты, включая получение задачи по ID и внешний API, не кэшируются.

Кэш `tasks` живёт 5 минут с момента записи. Успешные POST, PUT и DELETE задач очищают весь кэш `tasks` после фиксации транзакции; следующий GET вновь читает H2. Ошибка или откат транзакции не должны очищать актуальный кэш. При запуске приложения очищаются только ключи кэша `tasks`: H2 in-memory после перезапуска пуста, поэтому старый список из Redis был бы неверным. Другие ключи Redis не удаляются. Список DTO хранится стандартной JDK-сериализацией; `TaskResponse` реализует `Serializable`.

Для проверки после запуска приложения выполните два одинаковых авторизованных GET и посмотрите ключ и TTL:

```bash
curl -i -u "$APP_SECURITY_USERNAME" http://localhost:8080/api/tasks
curl -i -u "$APP_SECURITY_USERNAME" http://localhost:8080/api/tasks
docker exec testtask-redis redis-cli --scan --pattern 'tasks::*'
docker exec testtask-redis redis-cli TTL 'tasks::SimpleKey []'
```

TTL после первого GET близок к 300 секундам. Для проверки чтения из БД можно запустить приложение с `./gradlew bootRun --args='--logging.level.org.hibernate.SQL=DEBUG'`: первый GET выведет запрос списка, повторный — нет. Затем создайте, обновите или удалите задачу через API: ключ `tasks::*` исчезнет, а следующий GET создаст его заново с актуальными данными. Авторизация проверяется до доступа к кэшу; анонимный запрос получает 401.

## API

| Метод | Путь | Успешный статус | Результат |
|---|---|---:|---|
| POST | `/api/tasks` | 201 Created | Создать задачу; ответ содержит `Location` |
| GET | `/api/tasks` | 200 OK | Список задач; `[]`, если задач нет |
| GET | `/api/tasks/{id}` | 200 OK | Получить задачу |
| PUT | `/api/tasks/{id}` | 200 OK | Полностью обновить редактируемые поля |
| DELETE | `/api/tasks/{id}` | 204 No Content | Удалить задачу; тело ответа пустое |
| GET | `/api/external/objects` | 200 OK | Получить JSON из внешнего API |

Статусы задачи: `TODO`, `IN_PROGRESS`, `DONE`. При создании `status` можно опустить: будет использован `TODO`. Для PUT обязательны `title` и `status`; `description: null` очищает описание. `title` не может быть пустым и ограничен 255 символами, `description` — 2000 символами. Некорректные данные или значение статуса дают HTTP 400. Обращение к несуществующему ID даёт HTTP 404.

### Создание

```bash
curl -i -u "$APP_SECURITY_USERNAME" -X POST http://localhost:8080/api/tasks \
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
curl -i -u "$APP_SECURITY_USERNAME" -X PUT http://localhost:8080/api/tasks/1 \
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

## Внешний REST API

`GET /api/external/objects` обращается к `https://api.restful-api.dev/objects` через Spring `RestClient` и возвращает полученный JSON-массив клиенту без сохранения в H2. URL задан свойством `external.api.url` в `application.yml`. Таймаут подключения — 3 секунды, чтения — 5 секунд.

```bash
curl -i -u "$APP_SECURITY_USERNAME" http://localhost:8080/api/external/objects
```

Пример элемента ответа (состав внешних данных может измениться):

```json
[
  {
    "id": "1",
    "name": "Google Pixel 6 Pro",
    "data": {"color": "Cloudy White", "capacity": "128 GB"}
  }
]
```

Приложение записывает в лог полученный JSON, метод и статус внешнего запроса. Логируемое содержимое ограничено 4096 символами; при превышении добавляется `[truncated]`. Заголовки и cookie не записываются. Для недоступного сервиса, внешнего HTTP 4xx/5xx, пустого или некорректного JSON возвращается `502 Bad Gateway` в едином формате ошибок. Вызов зависит от доступности внешнего сервиса и не кэшируется.

## Обработка ошибок

REST API возвращает ошибки в едином JSON-формате: `timestamp` (UTC, `Instant`), числовой `status`, текстовый `error`, безопасное `message` и путь `path` без параметров запроса. Например, если задачи нет:

```json
{
  "timestamp": "2026-11-17T12:00:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Task with id 999 not found",
  "path": "/api/tasks/999"
}
```

Для ошибок валидации, некорректного JSON, статуса задачи и типа ID возвращается `400 Bad Request` с сообщением `Invalid request`. Неподдерживаемый метод даёт `405 Method Not Allowed`, неподдерживаемый тип содержимого — `415 Unsupported Media Type`. Неожиданные ошибки дают `500 Internal Server Error` с сообщением `Internal server error`; детали исключения клиенту не передаются. Время в примере условное.

Ошибка аутентификации обрабатывается фильтром Spring Security и возвращает тот же формат с `status: 401`, `error: "Unauthorized"`, `message: "Authentication required"` и заголовком `WWW-Authenticate`. Ответ не уточняет, какое из учётных данных неверно.

Сбой внешнего API даёт `502 Bad Gateway`, например с `message: "External service unavailable"`. В ответе нет внутреннего адреса сервиса или тела его ошибки.

## Логирование

Для запросов и ответов пишутся метод, путь, HTTP-статус ответа и длительность обработки в миллисекундах. Для JSON также пишется ограниченное представление тела. Это касается и ответов с ошибками. Тело запроса читается после обработки, а тело ответа копируется обратно клиенту. Пустое тело помечается `[empty]`.

Заголовки, включая Authorization, cookie и параметры URL не записываются. Значения произвольного текста в JSON задач, включая `title`, `description`, сообщения об ошибках и неизвестные поля, скрываются. Из известных значений видны только числовой `id`, числовой HTTP-статус и допустимые значения статуса задачи. Вложенные объекты обрабатываются рекурсивно. Тело не пишется для не-JSON, multipart, потоковых запросов и запросов вне `/api/tasks`; для них остаются записи с методом, путём, статусом и длительностью. Путь `/api/external/objects` распознаётся фильтром, но его тело в HTTP-логе опускается: внешний JSON записывается отдельно сервисом один раз. Неизвестные или содержащие произвольный сегмент пути в логах скрываются. Длина представления тела задач ограничена 4096 символами; тело от 8192 байт помечается как усечённое без записи содержимого.

Пример записей для создания задачи (время и ID приведены только как пример):

```text
HTTP REQUEST | POST /api/tasks | body={"title":"[REDACTED]","status":"TODO"}
HTTP RESPONSE | POST /api/tasks | status=201 | durationMs=35 | body={"id":1,"title":"[REDACTED]","description":null,"status":"TODO","createdAt":"[REDACTED]","updatedAt":"[REDACTED]"}
```

## H2

Используется база `jdbc:h2:mem:testtask`; внешний сервер БД не нужен. Hibernate создаёт таблицу `tasks` по JPA-сущности. Данные существуют только во время работы процесса приложения и удаляются после его завершения. H2 Console по умолчанию не включена.

## Сборка и тесты

```bash
./gradlew clean build
./gradlew test
RUN_REDIS_TESTS=true ./gradlew test --rerun-tasks
```

`build` уже запускает тесты. Обычная локальная сборка использует `spring.cache.type=none`, поэтому не требует Redis. Тесты кэша через Spring proxy используют локальный CacheManager; отдельный набор Redis-тестов включается переменной `RUN_REDIS_TESTS=true` и требует Redis на `localhost:6379`. Он проверяет запись, чтение, TTL, сериализацию, инвалидацию, откат и очистку при запуске. GitHub Actions запускает Redis service и `clean build` с Redis-тестами при Pull Request в `task-dev`.

Внешний клиент тестируется через `MockRestServiceServer`, а контроллер — с подменённым сервисом; эти тесты не обращаются к интернету. В тестовых ресурсах заданы только фиктивные credentials `test-user` / `test-password`. Для ручной проверки после запуска выполните `curl -i -u "$APP_SECURITY_USERNAME" http://localhost:8080/api/external/objects` и проверьте статус, JSON и запись `EXTERNAL API` в логах.

Основная ветка проекта — `task-dev`. Изменения вносятся через отдельные ветки и Pull Requests в `task-dev`.
