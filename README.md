# TestTask

REST API для управления задачами на Java 21 и Spring Boot 3.5.16. Исходный код открыт в этом репозитории; рабочая демоверсия развёрнута из ветки `task-dev`.

## Где посмотреть

| Что | Адрес |
|---|---|
| Сервис | [testtask-41yk.onrender.com](https://testtask-41yk.onrender.com) |
| Swagger UI | [Открыть и выполнить запросы](https://testtask-41yk.onrender.com/swagger-ui/index.html) |
| OpenAPI | [Схема API в JSON](https://testtask-41yk.onrender.com/v3/api-docs) |
| HTTP-логи | [Better Stack: TestTask Render](https://telemetry.betterstack.com/team/t637360/tail?s=l2841670) |
| CI/CD | [GitHub Actions](https://github.com/erlan2332/TestTask/actions/workflows/ci.yml) |

Бизнес-эндпоинты защищены HTTP Basic. Для проверки в Swagger нажмите **Authorize** и введите учётные данные, выданные владельцем проекта. Без них API возвращает `401`. Swagger и OpenAPI доступны без авторизации. Ссылка на логи требует входа в Better Stack и доступа к команде проекта; доступ проверяющему выдаёт владелец. Бесплатный Render может «засыпать», поэтому первый запрос иногда выполняется дольше.

## Выполнение тестового задания

| Пункт | Что сделано |
|---|---|
| Обязательный 1 — REST API | Все пять операций с задачами: создание, список, получение по ID, обновление и удаление. |
| Обязательный 2 — H2 | Задачи хранятся в реляционной H2 через Spring Data JPA. |
| Обязательный 3 — HTTP-логи | Zalando Logbook записывает запросы и ответы, включая JSON задач и HTTP-статусы. |
| Дополнительный 1 — внешний GET | `GET /api/external/objects` вызывает [restful-api.dev](https://api.restful-api.dev/objects); ответ записывается в лог с ограничением 65 тыс. символов. |
| Дополнительный 2 — unit-тесты | Проверены все CRUD-методы `TaskServiceImpl`; есть HTTP- и интеграционные тесты. |
| Дополнительный 3 — email | После успешного создания задачи отправляется письмо через SMTP. Локально письма принимает Mailpit; в демоверсии Render отправка отключена. |
| Дополнительный 4 — Basic Authentication | Все бизнес-эндпоинты требуют логин и пароль. |
| Дополнительный 5 — Redis | Список задач кэшируется на 5 минут; создание, изменение и удаление очищают кэш. |
| Дополнительный 6 — Dockerfile | Многоэтапная сборка Java 21; `compose.yml` запускает приложение, Redis и Mailpit. |

## Эндпоинты

| Метод | Путь | Результат |
|---|---|---|
| `POST` | `/api/tasks` | Создать задачу — `201` |
| `GET` | `/api/tasks` | Получить список — `200` |
| `GET` | `/api/tasks/{id}` | Получить задачу — `200` |
| `PUT` | `/api/tasks/{id}` | Обновить задачу — `200` |
| `DELETE` | `/api/tasks/{id}` | Удалить задачу — `204` |
| `GET` | `/api/external/objects` | Получить данные внешнего API — `200` |

Пример тела для `POST /api/tasks`:

```json
{"title":"Проверка","description":"Тестовая задача","status":"TODO"}
```

Для `PUT` обязательны `title` и `status`. Допустимые статусы: `TODO`, `IN_PROGRESS`, `DONE`. Некорректный запрос возвращает `400`, отсутствующая задача — `404`, ошибка внешнего API — `502`.

## Логи и развёртывание

Render запускает Docker-образ приложения и отдельный Redis-совместимый Key Value. Логи приложения передаются в Better Stack. В Live tail можно искать по `message_json.method=POST`, `message_json.status=201` или `message_json.correlation=<значение>`; полный JSON виден при открытии записи. Logbook удаляет заголовки и query-параметры, включая Authorization и Cookie, и маскирует стандартные поля с паролями и токенами. Поля задач `title` и `description` логируются полностью — используйте для демонстрации только тестовые данные.

GitHub Actions на push и PR запускает Gradle build, тесты с настоящим Redis и Docker build. При успешном push в `task-dev` отдельный job запускает Render для проверенного коммита. Секрет деплоя хранится в GitHub Actions, не в репозитории.

H2 работает **в памяти**: после перезапуска или деплоя задачи исчезают. На бесплатном Render SMTP отключён, поэтому email проверяется локально. Это ограничения демоверсии, а не отсутствующие функции проекта.

## Локальный запуск и тесты

```bash
git clone -b task-dev https://github.com/erlan2332/TestTask.git
cd TestTask
cp .env.example .env
# Укажите свои APP_SECURITY_USERNAME и APP_SECURITY_PASSWORD в .env
docker compose up --build -d
```

Локальный [Swagger UI](http://localhost:8080/swagger-ui/index.html), [Mailpit](http://localhost:8026) и логи: `docker compose logs -f app`. Mailpit перехватывает письма и не отправляет их на реальные адреса. Остановка: `docker compose down`.

```bash
./gradlew clean build
# Для Redis-интеграционных тестов нужен Redis на localhost:6379:
RUN_REDIS_TESTS=true ./gradlew clean build
```

Проверено на Java 21: 66 тестов, в том числе 6 Redis-интеграционных, без ошибок и пропусков; Docker build и публичные HTTP-запросы также прошли успешно.
