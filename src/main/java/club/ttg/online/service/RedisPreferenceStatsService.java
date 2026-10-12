package club.ttg.online.service;

import club.ttg.online.OnlineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class RedisPreferenceStatsService implements PreferenceStatsService
{
    private static final Pattern VALUE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");

    private final StringRedisTemplate redisTemplate;
    private final OnlineProperties properties;

    @Override
    public void record(String siteId, String visitorKey, Map<String, String> preferences, Instant now)
    {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(visitorKey, "visitorKey");
        Objects.requireNonNull(now, "now");

        if (preferences == null || preferences.isEmpty())
        {
            return;
        }

        OnlineProperties.Preferences config = properties.getPreferences();
        LocalDate day = LocalDate.ofInstant(now, config.getZone());
        // День живёт на сутки дольше хранения, чтобы самый старый день периода не пропадал раньше времени.
        Duration ttl = Duration.ofDays(config.getRetentionDays() + 1L);

        for (String name : config.getTracked())
        {
            String value = preferences.get(name);

            if (value == null || !VALUE_PATTERN.matcher(value).matches() || !acceptValue(siteId, name, value))
            {
                continue;
            }

            String dayKey = dayKey(siteId, name, day, value);

            // HyperLogLog: уникальные посетители за день в фиксированных ~12 КБ на ключ.
            redisTemplate.opsForHyperLogLog().add(dayKey, visitorKey);
            redisTemplate.expire(dayKey, ttl);
        }
    }

    @Override
    public List<PreferencePeriodStats> getStats(String siteId, Instant now)
    {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(now, "now");

        OnlineProperties.Preferences config = properties.getPreferences();
        LocalDate today = LocalDate.ofInstant(now, config.getZone());

        return config.getPeriodsDays().stream()
                .map(days -> Math.clamp(days, 1, config.getRetentionDays()))
                .distinct()
                .map(days -> new PreferencePeriodStats(days, countPeriod(siteId, today, days)))
                .toList();
    }

    private Map<String, Map<String, Long>> countPeriod(String siteId, LocalDate today, int days)
    {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();

        for (String name : properties.getPreferences().getTracked())
        {
            Set<String> values = redisTemplate.opsForSet().members(valuesKey(siteId, name));
            Map<String, Long> counts = new TreeMap<>();

            if (values != null)
            {
                for (String value : values)
                {
                    String[] dayKeys = IntStream.range(0, days)
                            .mapToObj(offset -> dayKey(siteId, name, today.minusDays(offset), value))
                            .toArray(String[]::new);
                    // PFCOUNT по нескольким ключам считает объединение: человек, заходивший
                    // несколько дней подряд, остаётся одним посетителем.
                    Long count = redisTemplate.opsForHyperLogLog().size(dayKeys);

                    if (count != null && count > 0)
                    {
                        counts.put(value, count);
                    }
                }
            }

            result.put(name, counts);
        }

        return result;
    }

    /**
     * Запоминает значение в списке известных. Список ограничен, чтобы мусорные значения
     * не плодили ключи без конца.
     */
    private boolean acceptValue(String siteId, String name, String value)
    {
        String valuesKey = valuesKey(siteId, name);

        if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(valuesKey, value)))
        {
            return true;
        }

        Long size = redisTemplate.opsForSet().size(valuesKey);

        if (size != null && size >= properties.getPreferences().getMaxValues())
        {
            return false;
        }

        redisTemplate.opsForSet().add(valuesKey, value);

        return true;
    }

    private String valuesKey(String siteId, String name)
    {
        return sitePrefix(siteId) + "pref:" + name + ":values";
    }

    private String dayKey(String siteId, String name, LocalDate day, String value)
    {
        return sitePrefix(siteId) + "pref:" + name + ":" + day + ":" + value;
    }

    private String sitePrefix(String siteId)
    {
        return properties.getRedis().getKeyPrefix() + siteId.trim().toLowerCase() + ":";
    }
}
