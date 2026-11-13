package club.ttg.online.service;

import club.ttg.online.OnlineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class RedisUsageStatsServiceTest
{
    // 2026-05-08 23:30 UTC — в Москве уже 9 мая, сутки режутся по московскому времени.
    private static final Instant NOW = Instant.parse("2026-05-08T23:30:00Z");
    private static final String FEATURE = "dm-screen";

    // Хэши Redis в памяти: ключ - поле - значение. Так тесты проверяют итог, а не порядок вызовов.
    private final Map<String, Map<Object, Object>> hashes = new HashMap<>();
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
    private final OnlineProperties properties = new OnlineProperties();
    private final RedisUsageStatsService service = new RedisUsageStatsService(redisTemplate, properties);

    RedisUsageStatsServiceTest()
    {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.increment(anyString(), any(), anyLong())).thenAnswer(invocation ->
        {
            Map<Object, Object> hash = hashes.computeIfAbsent(invocation.getArgument(0), key -> new HashMap<>());
            long delta = invocation.getArgument(2);

            return Long.parseLong((String) hash.merge(
                    invocation.getArgument(1),
                    String.valueOf(delta),
                    (current, added) -> String.valueOf(Long.parseLong((String) current) + delta)
            ));
        });
        when(hashOperations.entries(anyString()))
                .thenAnswer(invocation -> hashes.getOrDefault(invocation.<String>getArgument(0), Map.of()));
    }

    @Test
    void recordAddsSecondsToVisitorOfTheDayAndSetsTtl()
    {
        service.record("New", FEATURE, "visitor-1", 60, NOW);
        service.record("new", FEATURE, "visitor-1", 45, NOW);

        assertEquals(Map.of("online:new:usage:dm-screen:2026-05-09", Map.of("visitor-1", "105")), hashes);
        verify(redisTemplate, times(2))
                .expire("online:new:usage:dm-screen:2026-05-09", Duration.ofDays(31));
    }

    @Test
    void pageOpenWithoutSecondsCountsAsVisitorButNotUser()
    {
        service.record("new", FEATURE, "visitor-1", 0, NOW);

        assertEquals(new UsageStatsService.UsagePeriodStats(1, 1, 0, 0, 0, 0), period("new", 1));
    }

    @Test
    void dayIsCountedOnceMinActiveSecondsAreReached()
    {
        for (int i = 0; i < 4; i++)
        {
            service.record("new", FEATURE, "visitor-1", 60, NOW);
        }

        assertEquals(new UsageStatsService.UsagePeriodStats(1, 1, 0, 0, 0, 0), period("new", 1));

        service.record("new", FEATURE, "visitor-1", 60, NOW);

        assertEquals(new UsageStatsService.UsagePeriodStats(1, 1, 1, 0, 1, 5), period("new", 1));
    }

    @Test
    void twoCountedDaysOfOneVisitorMakeHimReturning()
    {
        recordCountedDay("new", "visitor-1", NOW);
        recordCountedDay("new", "visitor-1", NOW.minus(1, ChronoUnit.DAYS));
        // Второй человек пользовался один день, третий только открывал страницу.
        recordCountedDay("new", "visitor-2", NOW);
        service.record("new", FEATURE, "visitor-3", 0, NOW);

        assertEquals(new UsageStatsService.UsagePeriodStats(7, 3, 2, 1, 3, 15), period("new", 7));
        assertEquals(new UsageStatsService.UsagePeriodStats(1, 3, 2, 0, 2, 10), period("new", 1));
    }

    @Test
    void activeMinutesSumOnlyCountedDaysAndRoundDown()
    {
        service.record("new", FEATURE, "visitor-1", 300, NOW);
        service.record("new", FEATURE, "visitor-1", 59, NOW);
        // Незасчитанный день в минуты не идёт.
        service.record("new", FEATURE, "visitor-2", 299, NOW);

        assertEquals(new UsageStatsService.UsagePeriodStats(1, 2, 1, 0, 1, 5), period("new", 1));
    }

    @Test
    void dayOutsidePeriodIsNotCounted()
    {
        recordCountedDay("new", "visitor-1", NOW.minus(7, ChronoUnit.DAYS));

        assertEquals(new UsageStatsService.UsagePeriodStats(7, 0, 0, 0, 0, 0), period("new", 7));
        assertEquals(new UsageStatsService.UsagePeriodStats(30, 1, 1, 0, 1, 5), period("new", 30));
    }

    @Test
    void sitesAreNotMixed()
    {
        recordCountedDay("new", "visitor-1", NOW);

        assertEquals(new UsageStatsService.UsagePeriodStats(1, 1, 1, 0, 1, 5), period("new", 1));
        assertEquals(new UsageStatsService.UsagePeriodStats(1, 0, 0, 0, 0, 0), period("old", 1));
    }

    @Test
    void untrackedFeatureIsNotRecorded()
    {
        service.record("new", "spell-book", "visitor-1", 60, NOW);

        assertTrue(hashes.isEmpty());
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void trackedFeatureIsAlwaysPresentWithZeros()
    {
        assertEquals(
                List.of(new UsageStatsService.FeatureUsageStats(
                        FEATURE,
                        List.of(
                                new UsageStatsService.UsagePeriodStats(1, 0, 0, 0, 0, 0),
                                new UsageStatsService.UsagePeriodStats(7, 0, 0, 0, 0, 0),
                                new UsageStatsService.UsagePeriodStats(30, 0, 0, 0, 0, 0)
                        )
                )),
                service.getStats("vttg", NOW)
        );
    }

    @Test
    void thresholdIsAppliedWhenReadingSoItCanBeChangedLater()
    {
        service.record("new", FEATURE, "visitor-1", 120, NOW);
        properties.getUsage().setMinActiveSeconds(120);

        assertEquals(new UsageStatsService.UsagePeriodStats(1, 1, 1, 0, 1, 2), period("new", 1));
    }

    private void recordCountedDay(String siteId, String visitorKey, Instant at)
    {
        service.record(siteId, FEATURE, visitorKey, 300, at);
    }

    private UsageStatsService.UsagePeriodStats period(String siteId, int days)
    {
        return service.getStats(siteId, NOW).getFirst().periods().stream()
                .filter(stats -> stats.days() == days)
                .findFirst()
                .orElseThrow();
    }
}
