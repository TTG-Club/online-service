
# TTG Online Service

Микросервис для подсчёта пользователей **online** на сайтах TTG Club.

Сервис принимает heartbeat-сигналы от сайтов и хранит состояние пользователей в Redis.
Позволяет получать статистику:

- сколько **гостей** онлайн
- сколько **зарегистрированных пользователей**
- сколько пользователей **на каждом сайте**
- сколько **всего онлайн**

Сервис поддерживает любое количество сайтов.

---

# Архитектура

Browser
│
│ heartbeat
▼
TTG Website (backend)
│
│ POST /api/v1/online/heartbeat
▼
TTG Online Service
│
│ Redis ZSET
▼
Redis

Каждый сайт отправляет heartbeat для:
- гостей
- зарегистрированных пользователей

Сервис агрегирует статистику за заданное временное окно.

---

# Поддерживаемые сайты

Конфигурируются через `application.yaml`:

online:
allowed-sites:
- new
- 5e14

При добавлении нового сайта достаточно добавить новый `siteId`.

---

# API

## Heartbeat

POST /api/v1/online/heartbeat

### Request

{
"siteId": "new",
"key": "visitor-or-user-id",
"type": "GUEST"
}

### type

GUEST
REGISTERED

### previousGuestKey

Optional. Send it with `type: "REGISTERED"` when a visitor has just logged in.
The service removes this key from `online:{siteId}:guest`, so the same person is not counted as both guest and registered during the stats window.

### preferences

Optional. Настройки отображения посетителя, например `{"theme": "dark", "width": "wide", "layout": "top-left"}`.
Учитываются только настройки из `online.preferences.tracked`, значения — строки вида `[a-z0-9_-]`, до 32 символов.

### Response

200 OK

{
"total": 12
}

---

## Работа с инструментами сайта

POST /api/v1/online/usage

Отметка о работе с инструментом сайта (например, «Ширма Мастера»). Сайт шлёт её при открытии
страницы инструмента с `activeSeconds: 0` и дальше раз в минуту — с временем работы,
накопленным с прошлой отметки.

### Request

{
"siteId": "new",
"feature": "dm-screen",
"key": "visitor-or-user-id",
"type": "REGISTERED",
"activeSeconds": 60
}

- `feature` — строка вида `[a-z0-9_-]`, до 32 символов. Учитываются только инструменты из `online.usage.tracked`, отметки по остальным молча отбрасываются.
- `type` — `GUEST` или `REGISTERED`, на подсчёт не влияет.
- `activeSeconds` — целое от 0 до 300. Ноль значит «открыл страницу».

### Response

204 No Content

400 — некорректное тело, 404 — сайт не из `allowed-sites`.

---

# Получение статистики

## Общая статистика

GET /api/v1/online/stats
GET /api/v1/online/stats?windowMinutes=30

### Response

{
"windowMinutes": 30,
"total": {
"guests": 12,
"registered": 8,
"total": 20
},
"sites": [
{
"siteId": "5e14",
"guests": 5,
"registered": 3,
"total": 8
},
{
"siteId": "new",
"guests": 7,
"registered": 5,
"total": 12
}
]
}

---

## Статистика по одному сайту

GET /api/v1/online/stats/{siteId}

пример:

GET /api/v1/online/stats/new

Response:

{
"windowMinutes": 30,
"siteId": "new",
"guests": 7,
"registered": 5,
"total": 12,
"preferences": [
{ "days": 1, "preferences": { "theme": { "dark": 40, "light": 12 }, "width": { "wide": 45, "default": 7 } } },
{ "days": 7, "preferences": { ... } },
{ "days": 30, "preferences": { ... } }
],
"usage": [
{ "feature": "dm-screen", "periods": [
{ "days": 1, "visitors": 40, "users": 18, "returning": 0, "activeDays": 18, "activeMinutes": 1340 },
{ "days": 7, "visitors": 120, "users": 51, "returning": 22, "activeDays": 96, "activeMinutes": 8120 },
{ "days": 30, "visitors": 300, "users": 110, "returning": 47, "activeDays": 260, "activeMinutes": 21500 }
] }
]
}

Те же `preferences` и `usage` есть у каждого сайта в общей статистике `GET /api/v1/online/stats`.

`preferences` — уникальные посетители с каждым значением настройки за последние `days` суток (включая сегодня).
Кто менял значение в течение периода, учтён в каждом из выбранных.

`usage` — работа с инструментами сайта за последние `days` суток (включая сегодня). Каждый инструмент
из `online.usage.tracked` есть в ответе всегда, даже с нулями. «Засчитанный день» посетителя — день,
в который он набрал не меньше `online.usage.min-active-seconds` секунд работы.

- `visitors` — сколько разных посетителей открывали инструмент;
- `users` — сколько из них имеют хотя бы один засчитанный день;
- `returning` — сколько имеют два и более засчитанных дня;
- `activeDays` — сколько всего засчитанных дней, сумма по посетителям;
- `activeMinutes` — сколько минут набрано за засчитанные дни, с округлением вниз.

---

# Redis структура

Используются Sorted Sets:

online:{siteId}:guest
online:{siteId}:registered

member:
visitorId или userId

score:
timestamp lastSeen

Пример ключей:

online:new:guest
online:new:registered
online:5e14:guest
online:5e14:registered

Статистика настроек отображения:

online:{siteId}:pref:{name}:values — Set известных значений настройки (не больше `max-values`)
online:{siteId}:pref:{name}:{yyyy-MM-dd}:{value} — HyperLogLog уникальных посетителей за сутки, живёт `retention-days + 1` дней

Работа с инструментами сайта:

online:{siteId}:usage:{feature}:{yyyy-MM-dd} — Hash «посетитель → секунды работы за сутки», живёт `retention-days + 1` дней

---

# Конфигурация

server:
port: 8092

spring:
data:
redis:
host: localhost
port: 6379

online:
allowed-sites:
- new
- 5e14

default-window-minutes: 30
min-window-minutes: 1
max-window-minutes: 1440

redis:
key-prefix: online:

cors:
enabled: false

---

# Интеграция с сайтами

Каждый сайт должен:

1. генерировать `visitorId` cookie для гостей
2. отправлять heartbeat

Пример:

POST /api/v1/online/heartbeat

{
"siteId": "new",
"key": "visitorId",
"type": "GUEST"
}

или

{
"siteId": "new",
"key": "userId",
"previousGuestKey": "visitorId",
"type": "REGISTERED"
}

Heartbeat рекомендуется отправлять каждые **30 секунд**.

---

# Мониторинг

Spring Boot Actuator:

/actuator/health
/actuator/metrics

---

# Требования

- Java 21
- Redis 6+
- Spring Boot 4.x

---

# Запуск

mvn spring-boot:run

или

mvn clean package
java -jar target/online.jar
