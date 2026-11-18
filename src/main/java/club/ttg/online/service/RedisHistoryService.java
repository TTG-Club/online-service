package club.ttg.online.service;

import club.ttg.online.OnlineProperties;
import club.ttg.online.OnlineType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class RedisHistoryService implements HistoryService
{
    static final String FIELD_PEAK = "peak";
    static final String FIELD_PEAK_AT = "peakAt";
    static final String FIELD_PEAK_PLAYERS = "peakPlayers";
    static final String FIELD_SUM = "sum";
    static final String FIELD_SAMPLES = "samples";
    static final String FIELD_VISITORS = "visitors";
    static final String FIELD_GUESTS = "guests";
    static final String FIELD_REGISTERED = "registered";

    private static final String PEOPLE_GUESTS = "guest";
    private static final String PEOPLE_REGISTERED = "registered";
    private static final String PEOPLE_LINKED = "linked";

    /**
     * Сколько живут точные списки посетителей суток. Их итог переносится в хеш дня на
     * следующие сутки, списки нужны только до этого переноса - запас на простой сервиса.
     */
    static final Duration PEOPLE_TTL = Duration.ofDays(3);

    /**
     * Один замер дня одной командой: несколько копий сервиса не перетрут пик друг друга.
     * KEYS[1] - хеш дня; ARGV: онлайн, в мирах, время замера, TTL в секундах.
     */
    static final RedisScript<Long> SAMPLE_SCRIPT = new DefaultRedisScript<>(
            """
            local online = tonumber(ARGV[1])
            local players = tonumber(ARGV[2])
            local peak = tonumber(redis.call('HGET', KEYS[1], 'peak'))
            if (not peak) or online > peak then
              redis.call('HSET', KEYS[1], 'peak', online, 'peakAt', ARGV[3])
            end
            local peakPlayers = tonumber(redis.call('HGET', KEYS[1], 'peakPlayers'))
            if (not peakPlayers) or players > peakPlayers then
              redis.call('HSET', KEYS[1], 'peakPlayers', players)
            end
            redis.call('HINCRBY', KEYS[1], 'sum', online)
            redis.call('HINCRBY', KEYS[1], 'samples', 1)
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return 1
            """,
            Long.class
    );

    /**
     * Посетитель суток. Гость, вошедший в аккаунт, вычёркивается из гостей и запоминается
     * среди вошедших: если он потом выйдет, гостем за эти сутки уже не станет.
     * KEYS: гости, вошедшие, гостевые ключи вошедших; ARGV: тип, ключ, прежний гостевой
     * ключ (пустая строка, если его нет), TTL в секундах.
     */
    static final RedisScript<Long> VISIT_SCRIPT = new DefaultRedisScript<>(
            """
            if ARGV[1] == 'REGISTERED' then
              redis.call('SADD', KEYS[2], ARGV[2])
              if ARGV[3] ~= '' then
                redis.call('SREM', KEYS[1], ARGV[3])
                redis.call('SADD', KEYS[3], ARGV[3])
              end
            elseif redis.call('SISMEMBER', KEYS[3], ARGV[2]) == 0 then
              redis.call('SADD', KEYS[1], ARGV[2])
            end
            for i = 1, 3 do
              redis.call('EXPIRE', KEYS[i], ARGV[4])
            end
            return 1
            """,
            Long.class
    );

    /**
     * Объединение списков нескольких сайтов: один человек на двух сайтах - один посетитель.
     * Ключи гостей - случайные идентификаторы, вошедших - логины, поэтому эти множества
     * не пересекаются и посетители - это просто их сумма.
     */
    private static final String UNION_FUNCTION = """
            local function countUnion(first, last)
              local seen, total = {}, 0
              for i = first, last do
                for _, member in ipairs(redis.call('SMEMBERS', KEYS[i])) do
                  if not seen[member] then
                    seen[member] = true
                    total = total + 1
                  end
                end
              end
              return total
            end
            """;

    /**
     * Посетители суток по точным спискам. KEYS: гости сайтов, затем вошедшие сайтов;
     * ARGV[1] - число сайтов. Ответ: посетители, гости, вошедшие.
     */
    @SuppressWarnings("rawtypes")
    static final RedisScript<List> COUNT_SCRIPT = new DefaultRedisScript<>(
            UNION_FUNCTION + """
            local n = tonumber(ARGV[1])
            local guests = countUnion(1, n)
            local registered = countUnion(n + 1, 2 * n)
            return {guests + registered, guests, registered}
            """,
            List.class
    );

    /**
     * Переносит итог прошедших суток из списков в хеши дня и удаляет списки: держать их
     * все дни хранения истории слишком дорого. Повторный вызов только дочищает списки.
     * KEYS: хеш дня всех сайтов, хеши дня сайтов, гости сайтов, вошедшие сайтов, гостевые
     * ключи вошедших по сайтам; ARGV: число сайтов, TTL хешей в секундах.
     */
    static final RedisScript<Long> FINALIZE_SCRIPT = new DefaultRedisScript<>(
            UNION_FUNCTION + """
            local n = tonumber(ARGV[1])
            local hasPeople = false
            for i = n + 2, #KEYS do
              if redis.call('EXISTS', KEYS[i]) == 1 then
                hasPeople = true
              end
            end
            if not hasPeople then
              return 0
            end
            if redis.call('HEXISTS', KEYS[1], 'visitors') == 0 then
              for i = 1, n do
                local guests = redis.call('SCARD', KEYS[1 + n + i])
                local registered = redis.call('SCARD', KEYS[1 + 2 * n + i])
                redis.call('HSET', KEYS[1 + i],
                  'visitors', guests + registered, 'guests', guests, 'registered', registered)
                redis.call('EXPIRE', KEYS[1 + i], ARGV[2])
              end
              local guests = countUnion(n + 2, 2 * n + 1)
              local registered = countUnion(2 * n + 2, 3 * n + 1)
              redis.call('HSET', KEYS[1],
                'visitors', guests + registered, 'guests', guests, 'registered', registered)
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            for i = n + 2, #KEYS do
              redis.call('DEL', KEYS[i])
            end
            return 1
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final OnlineUserService onlineUserService;
    private final OnlineProperties properties;

    @Override
    public void recordVisit(String siteId, OnlineType type, String visitorKey, String previousGuestKey, Instant now)
    {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(visitorKey, "visitorKey");
        Objects.requireNonNull(now, "now");

        LocalDate day = today(now);
        String previous = (previousGuestKey == null || previousGuestKey.isBlank()) ? "" : previousGuestKey;

        // Точные списки, а не HyperLogLog: из HyperLogLog нельзя вычеркнуть гостя, который вошёл.
        redisTemplate.execute(
                VISIT_SCRIPT,
                List.of(
                        peopleKey(siteId, day, PEOPLE_GUESTS),
                        peopleKey(siteId, day, PEOPLE_REGISTERED),
                        peopleKey(siteId, day, PEOPLE_LINKED)
                ),
                type.name(),
                visitorKey,
                previous,
                String.valueOf(PEOPLE_TTL.toSeconds())
        );
    }

    @Override
    public boolean sample(Instant now)
    {
        Objects.requireNonNull(now, "now");

        if (!acquireSampleSlot(now))
        {
            return false;
        }

        Duration window = Duration.ofMinutes(properties.getDefaultWindowMinutes());
        LocalDate day = today(now);
        String sampledAt = now.atZone(zone()).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String ttlSeconds = String.valueOf(retention().toSeconds());

        long totalOnline = 0;
        long totalPlayers = 0;

        for (String siteId : properties.getAllowedSites())
        {
            OnlineUserService.OnlineCount count = onlineUserService.getCount(siteId, window, now);
            totalOnline += count.total();
            totalPlayers += count.players();
            writeSample(siteId, day, count.total(), count.players(), sampledAt, ttlSeconds);
        }

        writeSample(ALL_SITES, day, totalOnline, totalPlayers, sampledAt, ttlSeconds);

        // Списки живут несколько суток - дочищаем всё, что сервис мог пропустить, пока стоял.
        for (long back = 1; back < PEOPLE_TTL.toDays(); back++)
        {
            finalizeDay(day.minusDays(back), ttlSeconds);
        }

        return true;
    }

    @Override
    public History getHistory(int days, Instant now)
    {
        Objects.requireNonNull(now, "now");

        int clamped = Math.clamp(days, 1, properties.getHistory().getMaxDays());
        LocalDate to = today(now);
        LocalDate from = to.minusDays(clamped - 1L);
        List<LocalDate> period = from.datesUntil(to.plusDays(1)).toList();
        List<String> sites = sortedSites();
        LocalDate oldestPeople = to.minusDays(PEOPLE_TTL.toDays());

        List<HistoryDay> totalDays = period.stream()
                .map(day -> buildDay(ALL_SITES, sites, day, oldestPeople))
                .toList();
        List<SiteSeries> siteSeries = sites.stream()
                .map(siteId -> new SiteSeries(
                        siteId,
                        period.stream().map(day -> buildDay(siteId, List.of(siteId), day, oldestPeople)).toList()
                ))
                .toList();

        return new History(zone().getId(), from.toString(), to.toString(), new Series(totalDays), siteSeries);
    }

    /**
     * @param hashSiteId    чей хеш дня читать
     * @param visitorSites  по каким сайтам объединять уникальных посетителей
     * @param oldestPeople  раньше этого дня точных списков уже нет
     */
    private HistoryDay buildDay(String hashSiteId, List<String> visitorSites, LocalDate day, LocalDate oldestPeople)
    {
        Map<Object, Object> stats = redisTemplate.opsForHash().entries(dayKey(hashSiteId, day));
        VisitorCounts people = stats.containsKey(FIELD_VISITORS)
                ? new VisitorCounts(
                        parseLong(stats.get(FIELD_VISITORS)),
                        parseLong(stats.get(FIELD_GUESTS)),
                        parseLong(stats.get(FIELD_REGISTERED))
                )
                : day.isBefore(oldestPeople) ? VisitorCounts.NONE : countPeople(visitorSites, day);
        // До перехода на точные списки сутки считались в HyperLogLog. В день перехода
        // заполнены оба счёта, каждый за свою часть суток: берём больший.
        VisitorCounts counts = people.max(countLegacy(visitorSites, day));
        long samples = parseLong(stats.get(FIELD_SAMPLES));

        if (counts.visitors() == 0 && samples == 0)
        {
            return HistoryDay.empty(day.toString());
        }

        if (samples == 0)
        {
            return new HistoryDay(
                    day.toString(), counts.visitors(), counts.guests(), counts.registered(), null, null, null, null
            );
        }

        double average = Math.round(parseLong(stats.get(FIELD_SUM)) * 10.0 / samples) / 10.0;

        return new HistoryDay(
                day.toString(),
                counts.visitors(),
                counts.guests(),
                counts.registered(),
                parseLong(stats.get(FIELD_PEAK)),
                (String) stats.get(FIELD_PEAK_AT),
                average,
                parseLong(stats.get(FIELD_PEAK_PLAYERS))
        );
    }

    private VisitorCounts countPeople(List<String> sites, LocalDate day)
    {
        List<String> keys = Stream.concat(
                sites.stream().map(siteId -> peopleKey(siteId, day, PEOPLE_GUESTS)),
                sites.stream().map(siteId -> peopleKey(siteId, day, PEOPLE_REGISTERED))
        ).toList();
        List<?> result = redisTemplate.execute(COUNT_SCRIPT, keys, String.valueOf(sites.size()));

        if (result == null || result.size() < 3)
        {
            return VisitorCounts.NONE;
        }

        return new VisitorCounts(parseLong(result.get(0)), parseLong(result.get(1)), parseLong(result.get(2)));
    }

    private VisitorCounts countLegacy(List<String> sites, LocalDate day)
    {
        String[] guestKeys = sites.stream()
                .map(siteId -> legacyVisitorsKey(siteId, day, OnlineType.GUEST))
                .toArray(String[]::new);
        String[] registeredKeys = sites.stream()
                .map(siteId -> legacyVisitorsKey(siteId, day, OnlineType.REGISTERED))
                .toArray(String[]::new);
        String[] allKeys = Stream.concat(Stream.of(guestKeys), Stream.of(registeredKeys))
                .toArray(String[]::new);
        long visitors = pfCount(allKeys);

        if (visitors == 0)
        {
            return VisitorCounts.NONE;
        }

        return new VisitorCounts(visitors, pfCount(guestKeys), pfCount(registeredKeys));
    }

    private void finalizeDay(LocalDate day, String ttlSeconds)
    {
        List<String> sites = sortedSites();
        List<String> keys = new ArrayList<>();
        keys.add(dayKey(ALL_SITES, day));
        sites.forEach(siteId -> keys.add(dayKey(siteId, day)));
        sites.forEach(siteId -> keys.add(peopleKey(siteId, day, PEOPLE_GUESTS)));
        sites.forEach(siteId -> keys.add(peopleKey(siteId, day, PEOPLE_REGISTERED)));
        sites.forEach(siteId -> keys.add(peopleKey(siteId, day, PEOPLE_LINKED)));

        redisTemplate.execute(FINALIZE_SCRIPT, keys, String.valueOf(sites.size()), ttlSeconds);
    }

    private void writeSample(String siteId, LocalDate day, long online, long players, String sampledAt, String ttl)
    {
        redisTemplate.execute(
                SAMPLE_SCRIPT,
                List.of(dayKey(siteId, day)),
                String.valueOf(online),
                String.valueOf(players),
                sampledAt,
                ttl
        );
    }

    /**
     * Замок на интервал замера: кто первым занял интервал, тот и снимает. Ключ привязан к
     * номеру интервала, поэтому копии с разным временем старта не мешают друг другу.
     */
    private boolean acquireSampleSlot(Instant now)
    {
        long intervalMillis = Math.max(properties.getHistory().getSampleInterval().toMillis(), 1000L);
        long slot = now.toEpochMilli() / intervalMillis;
        String lockKey = properties.getRedis().getKeyPrefix() + "history:sample-lock:" + slot;

        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofMillis(intervalMillis * 2)));
    }

    private long pfCount(String[] keys)
    {
        Long count = redisTemplate.opsForHyperLogLog().size(keys);

        return (count == null) ? 0L : count;
    }

    private static long parseLong(Object value)
    {
        if (value == null)
        {
            return 0L;
        }

        try
        {
            return Long.parseLong(value.toString());
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }

    private List<String> sortedSites()
    {
        return properties.getAllowedSites().stream().sorted().toList();
    }

    private LocalDate today(Instant now)
    {
        return LocalDate.ofInstant(now, zone());
    }

    private ZoneId zone()
    {
        return properties.getHistory().getZone();
    }

    private Duration retention()
    {
        return Duration.ofDays(properties.getHistory().getRetentionDays());
    }

    private String dayKey(String siteId, LocalDate day)
    {
        return sitePrefix(siteId) + "history:" + day;
    }

    /**
     * Точный список посетителей суток: гости, вошедшие или гостевые ключи вошедших.
     */
    private String peopleKey(String siteId, LocalDate day, String list)
    {
        return dayKey(siteId, day) + ":people:" + list;
    }

    /**
     * HyperLogLog посетителей суток, который вёлся до перехода на точные списки.
     */
    private String legacyVisitorsKey(String siteId, LocalDate day, OnlineType type)
    {
        return dayKey(siteId, day) + ":visitors:" + type.name().toLowerCase();
    }

    private String sitePrefix(String siteId)
    {
        return properties.getRedis().getKeyPrefix() + siteId.trim().toLowerCase() + ":";
    }

    private record VisitorCounts(long visitors, long guests, long registered)
    {
        static final VisitorCounts NONE = new VisitorCounts(0, 0, 0);

        VisitorCounts max(VisitorCounts other)
        {
            return (other.visitors() > visitors) ? other : this;
        }
    }
}
