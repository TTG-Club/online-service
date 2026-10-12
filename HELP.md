
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

Optional. Настройки отображения посетителя, например `{"theme": "dark", "width": "wide"}`.
Учитываются только настройки из `online.preferences.tracked`, значения — строки вида `[a-z0-9_-]`, до 32 символов.

### Response

200 OK

{
"total": 12
}

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
]
}

`preferences` — уникальные посетители с каждым значением настройки за последние `days` суток (включая сегодня).
Кто менял значение в течение периода, учтён в каждом из выбранных.

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
