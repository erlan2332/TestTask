# TestTask

## О проекте

REST API для управления задачами. Задачи хранятся в H2 во время работы приложения; список кэшируется в Redis. После создания задачи приложение может отправить уведомление на email через SMTP. Отдельный маршрут возвращает данные внешнего REST API.

## Технологический стек

Java 21, Spring Boot 3.5.16, Gradle 8.14.3, Spring Web MVC, Spring Data JPA, H2, Spring Security с HTTP Basic, Spring Cache, Redis, Spring Mail, Lombok, JUnit 5, Mockito, Docker и Docker Compose.

## Реализованные требования

| Тип | Требование | Статус по коду |
|---|---|---|
| Обязательное | CRUD API — пять операций | Выполнено |
| Обязательное | H2 Database | Выполнено |
| Обязательное | Логирование запросов и ответов | Выполнено |
| Дополнительное +1 | GET на внешний API с логированием ответа | Выполнено |
| Дополнительное +1 | Unit-тесты CRUD в сервисе | Выполнено |
| Дополнительное +1 | Email созданных задач | Выполнено |
| Дополнительное +1 | Basic Authentication | Выполнено |
| Дополнительное +1 | Redis-кэширование списка | Выполнено |
| Дополнительное +1 | Dockerfile | Выполнено |

Единый формат ошибок и Docker Compose также реализованы как улучшения проекта сверх перечисленных пунктов.

## Архитектура

`TaskController` принимает и валидирует запросы, `TaskServiceImpl` выполняет операции в транзакциях, `TaskMapper` преобразует Entity и DTO, `TaskRepository` работает с H2. После сохранения сервис публикует снимок задачи как `TaskCreatedEvent`; слушатель отправляет письмо через `EmailService` только после коммита. `CacheConfig` управляет кэшем списка и очищает его при запуске, чтобы Redis не вернул данные прежнего экземпляра H2. `SecurityConfig` защищает API, `HttpLoggingFilter` пишет безопасные HTTP-логи. Внешний API вызывается через отдельные контроллер и сервис.

## Запуск через Docker

Нужны Docker с Compose, доступ к Docker Hub и свободные порты 8080 и 8026. Порт Mailpit UI можно изменить. Redis использует только внутреннюю сеть Compose.

```bash
git clone https://github.com/erlan2332/TestTask.git
cd TestTask
cp .env.example .env
# Замените APP_SECURITY_USERNAME и APP_SECURITY_PASSWORD в .env.
docker compose config > /dev/null
docker compose up --build -d
docker compose ps
```

`.env` не хранится в Git. Docker Compose читает его для подстановки и передаёт переменные приложению через `env_file`. Укажите непустые собственные тестовые `APP_SECURITY_USERNAME` и `APP_SECURITY_PASSWORD` до запуска. В Compose приложение всегда подключается к `redis:6379` и `mailpit:1025`; эти адреса задаются в `compose.yml` и заменяют локальные `localhost` из `.env.example`. Для локального Mailpit аутентификация SMTP и STARTTLS выключены. `APP_MAIL_ENABLED=true` включает уведомления, `false` отключает. `APP_MAIL_FROM` и `APP_MAIL_TO` задают отправителя и получателя; `MAIL_USERNAME` и `MAIL_PASSWORD` нужны только для другого SMTP при запуске вне этого Compose. Реальные пароли не коммитьте.

Приложение доступно на `http://localhost:8080`, веб-интерфейс Mailpit — на [http://localhost:8026](http://localhost:8026). Если 8026 занят, измените `MAILPIT_UI_PORT` в `.env`; внутри контейнера UI остаётся на 8025. Логи и остановка:

```bash
docker compose logs app
docker compose down
```

Compose ждёт успешного Redis healthcheck перед запуском приложения. H2 не требует отдельного контейнера. `docker compose down` удаляет контейнеры проекта; данные H2 исчезают уже при перезапуске контейнера приложения.

## Проверка API

Compose не экспортирует переменные из `.env` в оболочку. Для следующих команд загрузите их отдельно; `curl -u` интерактивно запросит пароль:

```bash
set -a
. ./.env
set +a
curl -i http://localhost:8080/api/tasks
curl -i -u "$APP_SECURITY_USERNAME" http://localhost:8080/api/tasks
curl -i -u "$APP_SECURITY_USERNAME" -H 'Content-Type: application/json' \
  -d '{"title":"Подготовить отчёт","description":"До пятницы"}' \
  http://localhost:8080/api/tasks
```

Первый запрос вернёт `401`, второй — `200`, создание — `201` с `Location: /api/tasks/<id>`. Подставьте полученный ID для остальных операций:

```bash
curl -i -u "$APP_SECURITY_USERNAME" http://localhost:8080/api/tasks/1
curl -i -u "$APP_SECURITY_USERNAME" -X PUT -H 'Content-Type: application/json' \
  -d '{"title":"Отчёт готов","status":"DONE"}' http://localhost:8080/api/tasks/1
curl -i -u "$APP_SECURITY_USERNAME" -X DELETE http://localhost:8080/api/tasks/1
```

GET по ID и PUT возвращают `200`, DELETE — `204`. Несуществующий ID возвращает `404`, некорректные данные или статус — `400`. Статусы задачи: `TODO`, `IN_PROGRESS`, `DONE`. При создании статус по умолчанию `TODO`; название обязательно и ограничено 255 символами, описание — 2000 символами. Для PUT обязательны название и статус. API возвращает безопасный JSON для ошибок; подробности исключений клиенту не передаются.

## Redis

Кэшируется только `GET /api/tasks`, включая пустой список. Первый запрос читает H2 и создаёт запись `tasks::*` в Redis; повторный получает данные из кэша. Время жизни — 5 минут. Успешные POST, PUT и DELETE очищают кэш после фиксации транзакции; при откате он остаётся. Проверка ключа и TTL:

```bash
docker compose exec redis redis-cli --scan --pattern 'tasks::*'
docker compose exec redis redis-cli TTL 'tasks::SimpleKey []'
```

Redis не опубликован на хосте. При запуске приложения старый кэш задач очищается, поскольку H2 in-memory после перезапуска пуста. Другие ключи Redis не затрагиваются.

## Email

При успешном POST письмо содержит ID, название, описание, статус и время создания задачи. Если описание отсутствует, указано `Not provided`. Письмо отправляется после коммита через SMTP Mailpit; при откате или ошибке валидации отправки нет. PUT и DELETE писем не создают. Сообщения можно просмотреть в [Mailpit UI](http://localhost:8026) (или на порту `MAILPIT_UI_PORT` из `.env`). Mailpit перехватывает письма и не доставляет их реальным адресатам.

При недоступном SMTP созданная задача остаётся в H2, POST возвращает `201`, ошибка логируется без содержимого письма и credentials. SMTP имеет таймауты: 3 секунды для подключения, по 5 секунд для чтения и записи. Отправка синхронная и может увеличить время запроса; автоматической повторной доставки нет.

## Внешний API

```bash
curl -i -u "$APP_SECURITY_USERNAME" http://localhost:8080/api/external/objects
```

Приложение делает GET к `https://api.restful-api.dev/objects`, возвращает JSON без сохранения в H2 и записывает ограниченный внешний ответ в лог. При недоступности сервиса или ошибочном ответе возвращается безопасный `502 Bad Gateway`. Таймаут подключения — 3 секунды, чтения — 5 секунд.

## Запуск без Docker

Нужны Java 21 в `PATH` и локальные Redis (`localhost:6379`) и Mailpit SMTP (`localhost:1025`, UI `localhost:8025`). Их можно запустить отдельными контейнерами. Если контейнеры уже созданы, используйте `docker start` с соответствующим именем вместо нового `docker run`.

```bash
java -version # должна выводиться версия 21
docker run -d --name testtask-redis -p 127.0.0.1:6379:6379 redis:7-alpine
docker run -d --name testtask-mailpit -p 127.0.0.1:1025:1025 -p 127.0.0.1:8025:8025 axllent/mailpit:v1.33.1
cp .env.example .env
# Замените APP_SECURITY_USERNAME и APP_SECURITY_PASSWORD в .env.
set -a
. ./.env
set +a
./gradlew bootRun
```

`./gradlew bootRun` сам по себе не загружает `.env`. При запуске без Docker `REDIS_HOST=localhost` и `MAIL_HOST=localhost`; переменные `MAILPIT_UI_PORT` приложением не используются. Если на macOS Java 21 установлена через Homebrew, при необходимости задайте `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.

## Тестирование

```bash
./gradlew clean build
./gradlew test
RUN_REDIS_TESTS=true ./gradlew test --rerun-tasks
docker build -t testtask:local .
docker compose config > /dev/null
```

`build` уже запускает тесты. Обычные тесты используют локальный кэш и подменённый почтовый отправитель, поэтому реальный Redis и SMTP им не нужны. Для набора `RUN_REDIS_TESTS=true` нужен Redis на `localhost:6379`; эти тесты проверяют TTL, инвалидацию, откат и взаимодействие с email. GitHub Actions запускает Redis service, Gradle build и проверку запуска Redis-тестов, затем собирает Docker image. CI не требует настоящего SMTP и не публикует образ.

HTTP-логи содержат метод, путь, статус и длительность. Для `/api/tasks` JSON-тела очищаются от произвольных значений перед записью; захват тела ограничен 8192 байтами, а запись — 4096 символами. Крупный ответ передаётся клиенту без полного буферизования фильтром. Значения названия и описания, заголовок Authorization, cookie и SMTP credentials в логи не попадают.

## Ограничения

- H2 работает в памяти контейнера. После перезапуска приложения задачи исчезают; Redis-кэш списка очищается при старте.
- Маршрут внешнего API зависит от доступности стороннего сервиса.
- Email отправляется синхронно после коммита; автоматических повторных попыток и гарантированной доставки нет.
