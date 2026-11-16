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

    private final StringRedisTemplate redisTemplate;
    private final OnlineUserService onlineUserService;
    private final OnlineProperties properties;

    @Override
    public void recordVisit(String siteId, OnlineType type, String visitorKey, Instant now)
    {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(visitorKey, "visitorKey");
        Objects.requireNonNull(now, "now");

        String key = visitorsKey(siteId, today(now), type);

        // HyperLogLog: уникальные посетители за день в фиксированных ~12 КБ на ключ.
        redisTemplate.opsForHyperLogLog().add(key, visitorKey);
        redisTemplate.expire(key, retention());
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
        List<String> sites = properties.getAllowedSites().stream().sorted().toList();

        List<HistoryDay> totalDays = period.stream()
                .map(day -> buildDay(ALL_SITES, sites, day))
                .toList();
        List<SiteSeries> siteSeries = sites.stream()
                .map(siteId -> new SiteSeries(
                        siteId,
                        period.stream().map(day -> buildDay(siteId, List.of(siteId), day)).toList()
                ))
                .toList();

        return new History(zone().getId(), from.toString(), to.toString(), new Series(totalDays), siteSeries);
    }

    /**
     * @param hashSiteId    чей хеш замеров читать
     * @param visitorSites  по каким сайтам объединять уникальных посетителей
     */
    private HistoryDay buildDay(String hashSiteId, List<String> visitorSites, LocalDate day)
    {
        String[] guestKeys = visitorSites.stream()
                .map(siteId -> visitorsKey(siteId, day, OnlineType.GUEST))
                .toArray(String[]::new);
        String[] registeredKeys = visitorSites.stream()
                .map(siteId -> visitorsKey(siteId, day, OnlineType.REGISTERED))
                .toArray(String[]::new);
        String[] allKeys = Stream.concat(Stream.of(guestKeys), Stream.of(registeredKeys))
                .toArray(String[]::new);

        // PFCOUNT по нескольким ключам считает объединение: вошедший в течение дня гость
        // и человек, заходивший на два сайта, остаются одним посетителем.
        long visitors = pfCount(allKeys);
        Map<Object, Object> stats = redisTemplate.opsForHash().entries(dayKey(hashSiteId, day));
        long samples = parseLong(stats.get(FIELD_SAMPLES));

        if (visitors == 0 && samples == 0)
        {
            return HistoryDay.empty(day.toString());
        }

        long guests = pfCount(guestKeys);
        long registered = pfCount(registeredKeys);

        if (samples == 0)
        {
            return new HistoryDay(day.toString(), visitors, guests, registered, null, null, null, null);
        }

        double average = Math.round(parseLong(stats.get(FIELD_SUM)) * 10.0 / samples) / 10.0;

        return new HistoryDay(
                day.toString(),
                visitors,
                guests,
                registered,
                parseLong(stats.get(FIELD_PEAK)),
                (String) stats.get(FIELD_PEAK_AT),
                average,
                parseLong(stats.get(FIELD_PEAK_PLAYERS))
        );
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

    private String visitorsKey(String siteId, LocalDate day, OnlineType type)
    {
        return dayKey(siteId, day) + ":visitors:" + type.name().toLowerCase();
    }

    private String sitePrefix(String siteId)
    {
        return properties.getRedis().getKeyPrefix() + siteId.trim().toLowerCase() + ":";
    }
}
