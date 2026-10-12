package club.ttg.online.service;

import club.ttg.online.OnlineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class RedisPreferenceStatsServiceTest
{
    // 2026-05-08 23:30 UTC — в Москве уже 9 мая, сутки режутся по московскому времени.
    private static final Instant NOW = Instant.parse("2026-05-08T23:30:00Z");

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final SetOperations<String, String> setOperations = mock(SetOperations.class);
    private final HyperLogLogOperations<String, String> hyperLogLogOperations = mock(HyperLogLogOperations.class);
    private final OnlineProperties properties = new OnlineProperties();
    private final RedisPreferenceStatsService service = new RedisPreferenceStatsService(redisTemplate, properties);

    RedisPreferenceStatsServiceTest()
    {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForHyperLogLog()).thenReturn(hyperLogLogOperations);
    }

    @Test
    void recordAddsVisitorToDailyCounterOfEachTrackedPreference()
    {
        when(setOperations.isMember("online:new:pref:theme:values", "light")).thenReturn(true);
        when(setOperations.isMember("online:new:pref:width:values", "wide")).thenReturn(true);

        service.record("New", "visitor-1", Map.of("theme", "light", "width", "wide"), NOW);

        verify(hyperLogLogOperations).add("online:new:pref:theme:2026-05-09:light", "visitor-1");
        verify(hyperLogLogOperations).add("online:new:pref:width:2026-05-09:wide", "visitor-1");
        verify(redisTemplate).expire("online:new:pref:theme:2026-05-09:light", Duration.ofDays(31));
    }

    @Test
    void recordIgnoresUntrackedPreferencesAndInvalidValues()
    {
        service.record("new", "visitor-1", Map.of("font", "big", "theme", "Light!"), NOW);

        verify(hyperLogLogOperations, never()).add(anyString(), any(String[].class));
        verify(setOperations, never()).add(anyString(), any(String[].class));
    }

    @Test
    void recordRemembersNewValueWhileUnderLimit()
    {
        when(setOperations.isMember("online:new:pref:theme:values", "svifty7")).thenReturn(false);
        when(setOperations.size("online:new:pref:theme:values")).thenReturn(2L);

        service.record("new", "visitor-1", Map.of("theme", "svifty7"), NOW);

        verify(setOperations).add("online:new:pref:theme:values", "svifty7");
        verify(hyperLogLogOperations).add("online:new:pref:theme:2026-05-09:svifty7", "visitor-1");
    }

    @Test
    void recordDropsNewValueOverLimit()
    {
        when(setOperations.isMember("online:new:pref:theme:values", "junk")).thenReturn(false);
        when(setOperations.size("online:new:pref:theme:values")).thenReturn(16L);

        service.record("new", "visitor-1", Map.of("theme", "junk"), NOW);

        verify(setOperations, never()).add("online:new:pref:theme:values", "junk");
        verify(hyperLogLogOperations, never()).add(anyString(), any(String[].class));
    }

    @Test
    void getStatsCountsUniqueVisitorsAcrossPeriodDays()
    {
        properties.getPreferences().setTracked(List.of("theme"));
        properties.getPreferences().setPeriodsDays(List.of(1, 2));
        when(setOperations.members("online:new:pref:theme:values")).thenReturn(Set.of("dark", "light"));
        when(hyperLogLogOperations.size("online:new:pref:theme:2026-05-09:dark")).thenReturn(5L);
        when(hyperLogLogOperations.size("online:new:pref:theme:2026-05-09:light")).thenReturn(0L);
        when(hyperLogLogOperations.size(
                "online:new:pref:theme:2026-05-09:dark",
                "online:new:pref:theme:2026-05-08:dark"
        )).thenReturn(8L);
        when(hyperLogLogOperations.size(
                "online:new:pref:theme:2026-05-09:light",
                "online:new:pref:theme:2026-05-08:light"
        )).thenReturn(3L);

        List<PreferenceStatsService.PreferencePeriodStats> stats = service.getStats("new", NOW);

        assertEquals(
                List.of(
                        new PreferenceStatsService.PreferencePeriodStats(1, Map.of("theme", Map.of("dark", 5L))),
                        new PreferenceStatsService.PreferencePeriodStats(2, Map.of("theme", Map.of("dark", 8L, "light", 3L)))
                ),
                stats
        );
    }
}
