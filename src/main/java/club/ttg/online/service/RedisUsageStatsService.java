package club.ttg.online.service;

import club.ttg.online.OnlineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RedisUsageStatsService implements UsageStatsService
{
    private final StringRedisTemplate redisTemplate;
    private final OnlineProperties properties;

    @Override
    public void record(String siteId, String feature, String visitorKey, int activeSeconds, Instant now)
    {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(visitorKey, "visitorKey");
        Objects.requireNonNull(now, "now");

        OnlineProperties.Usage config = properties.getUsage();

        if (!config.getTracked().contains(feature))
        {
            return;
        }

        String dayKey = dayKey(siteId, feature, LocalDate.ofInstant(now, config.getZone()));

        // Хэш «посетитель - секунды за день». Прибавляем и ноль: так поле появляется,
        // и открывший страницу попадает в посетители, даже если ничего не делал.
        redisTemplate.opsForHash().increment(dayKey, visitorKey, Math.max(activeSeconds, 0));
        // День живёт на сутки дольше хранения, чтобы самый старый день периода не пропадал раньше времени.
        redisTemplate.expire(dayKey, Duration.ofDays(config.getRetentionDays() + 1L));
    }

    @Override
    public List<FeatureUsageStats> getStats(String siteId, Instant now)
    {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(now, "now");

        OnlineProperties.Usage config = properties.getUsage();
        LocalDate today = LocalDate.ofInstant(now, config.getZone());
        List<Integer> periods = config.getPeriodsDays().stream()
                .map(days -> Math.clamp(days, 1, config.getRetentionDays()))
                .distinct()
                .toList();
        int maxDays = periods.stream().mapToInt(Integer::intValue).max().orElse(0);

        return config.getTracked().stream()
                .distinct()
                .map(feature ->
                {
                    // Дни читаем один раз на самый длинный период: короткие периоды - его начало.
                    List<Map<String, Long>> days = loadDays(siteId, feature, today, maxDays);

                    return new FeatureUsageStats(
                            feature,
                            periods.stream()
                                    .map(period -> countPeriod(days.subList(0, period), period, config.getMinActiveSeconds()))
                                    .toList()
                    );
                })
                .toList();
    }

    /**
     * @return секунды посетителей по дням: первым идёт сегодня, дальше вглубь
     */
    private List<Map<String, Long>> loadDays(String siteId, String feature, LocalDate today, int days)
    {
        List<Map<String, Long>> result = new ArrayList<>(days);

        for (int offset = 0; offset < days; offset++)
        {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(dayKey(siteId, feature, today.minusDays(offset)));
            Map<String, Long> seconds = new HashMap<>();

            if (entries != null)
            {
                entries.forEach((visitorKey, value) -> seconds.put(String.valueOf(visitorKey), parseSeconds(value)));
            }

            result.add(seconds);
        }

        return result;
    }

    private static UsagePeriodStats countPeriod(List<Map<String, Long>> days, int period, int minActiveSeconds)
    {
        // Посетитель - число его засчитанных дней за период (ноль: только открывал).
        Map<String, Integer> countedDays = new HashMap<>();
        long activeDays = 0;
        long activeSeconds = 0;

        for (Map<String, Long> day : days)
        {
            for (Map.Entry<String, Long> entry : day.entrySet())
            {
                boolean counted = entry.getValue() >= minActiveSeconds;

                countedDays.merge(entry.getKey(), counted ? 1 : 0, Integer::sum);

                if (counted)
                {
                    activeDays++;
                    activeSeconds += entry.getValue();
                }
            }
        }

        long users = countedDays.values().stream().filter(count -> count >= 1).count();
        long returning = countedDays.values().stream().filter(count -> count >= 2).count();

        return new UsagePeriodStats(period, countedDays.size(), users, returning, activeDays, activeSeconds / 60);
    }

    private static long parseSeconds(Object value)
    {
        try
        {
            return Long.parseLong(String.valueOf(value));
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    private String dayKey(String siteId, String feature, LocalDate day)
    {
        return properties.getRedis().getKeyPrefix() + siteId.trim().toLowerCase() + ":usage:" + feature + ":" + day;
    }
}
