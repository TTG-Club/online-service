package club.ttg.online.service;

import club.ttg.online.OnlineProperties;
import club.ttg.online.OnlineType;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class RedisHistoryServiceTest
{
    // 2026-11-15 21:35:07 UTC — в Москве уже 16 ноября, 00:35.
    private static final Instant NOW = Instant.parse("2026-11-15T21:35:07Z");

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final HyperLogLogOperations<String, String> hll = mock(HyperLogLogOperations.class);
    private final HashOperations<String, Object, Object> hash = mock(HashOperations.class);
    private final ValueOperations<String, String> value = mock(ValueOperations.class);
    private final OnlineUserService onlineUserService = mock(OnlineUserService.class);
    private final OnlineProperties properties = new OnlineProperties();
    private final RedisHistoryService service = new RedisHistoryService(redisTemplate, onlineUserService, properties);

    RedisHistoryServiceTest()
    {
        properties.setAllowedSites(List.of("5e24", "5e14"));
        when(redisTemplate.opsForHyperLogLog()).thenReturn(hll);
        when(redisTemplate.<Object, Object>opsForHash()).thenReturn(hash);
        when(redisTemplate.opsForValue()).thenReturn(value);
    }

    @Test
    void recordVisitAddsVisitorToDailyListOfItsType()
    {
        service.recordVisit("5E14", OnlineType.GUEST, "guest-1", null, NOW);
        service.recordVisit("5e14", OnlineType.REGISTERED, "user-1", "guest-1", NOW);

        List<String> keys = List.of(
                "online:5e14:history:2026-11-16:people:guest",
                "online:5e14:history:2026-11-16:people:registered",
                "online:5e14:history:2026-11-16:people:linked"
        );
        String ttl = String.valueOf(Duration.ofDays(3).toSeconds());
        verify(redisTemplate).execute(RedisHistoryService.VISIT_SCRIPT, keys, "GUEST", "guest-1", "", ttl);
        verify(redisTemplate).execute(RedisHistoryService.VISIT_SCRIPT, keys, "REGISTERED", "user-1", "guest-1", ttl);
        verifyNoInteractions(hll);
    }

    @Test
    void visitScriptStrikesGuestOutAfterSignIn()
    {
        String script = RedisHistoryService.VISIT_SCRIPT.getScriptAsString();

        // Вошедший перестаёт быть гостем, а вышедший из аккаунта гостем за эти сутки не становится.
        assertTrue(script.contains("redis.call('SREM', KEYS[1], ARGV[3])"));
        assertTrue(script.contains("redis.call('SADD', KEYS[3], ARGV[3])"));
        assertTrue(script.contains("redis.call('SISMEMBER', KEYS[3], ARGV[2]) == 0"));
    }

    @Test
    void sampleWritesEachSiteAndSumThroughOneScriptCall()
    {
        when(value.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(onlineUserService.getCount(eq("5e24"), eq(Duration.ofMinutes(30)), eq(NOW)))
                .thenReturn(new OnlineUserService.OnlineCount(10, 4, 2));
        when(onlineUserService.getCount(eq("5e14"), eq(Duration.ofMinutes(30)), eq(NOW)))
                .thenReturn(new OnlineUserService.OnlineCount(3, 1, 0));

        assertTrue(service.sample(NOW));

        String peakAt = "2026-11-16T00:35:07+03:00";
        String ttl = String.valueOf(Duration.ofDays(400).toSeconds());
        verify(redisTemplate).execute(RedisHistoryService.SAMPLE_SCRIPT,
                List.of("online:5e24:history:2026-11-16"), "14", "2", peakAt, ttl);
        verify(redisTemplate).execute(RedisHistoryService.SAMPLE_SCRIPT,
                List.of("online:5e14:history:2026-11-16"), "4", "0", peakAt, ttl);
        verify(redisTemplate).execute(RedisHistoryService.SAMPLE_SCRIPT,
                List.of("online:all:history:2026-11-16"), "18", "2", peakAt, ttl);
        // Пик меняется только внутри скрипта: отдельное чтение-запись могли бы перетереть друг друга копии.
        verifyNoInteractions(hash);
    }

    @Test
    void sampleMovesPastDaysFromListsIntoDayHashes()
    {
        when(value.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(onlineUserService.getCount(anyString(), any(Duration.class), any(Instant.class)))
                .thenReturn(new OnlineUserService.OnlineCount(0, 0, 0));

        service.sample(NOW);

        String ttl = String.valueOf(Duration.ofDays(400).toSeconds());
        for (String day : List.of("2026-11-15", "2026-11-14"))
        {
            verify(redisTemplate).execute(RedisHistoryService.FINALIZE_SCRIPT, finalizeKeys(day), "2", ttl);
        }
        verify(redisTemplate, never()).execute(
                eq(RedisHistoryService.FINALIZE_SCRIPT), eq(finalizeKeys("2026-11-16")), any(Object[].class)
        );
    }

    @Test
    void finalizeScriptKeepsFirstResultAndDropsLists()
    {
        String script = RedisHistoryService.FINALIZE_SCRIPT.getScriptAsString();

        // Повторный перенос не перетирает итог нулями из уже удалённых списков.
        assertTrue(script.contains("redis.call('HEXISTS', KEYS[1], 'visitors') == 0"));
        assertTrue(script.contains("if not hasPeople then"));
        assertTrue(script.contains("redis.call('DEL', KEYS[i])"));
    }

    @Test
    void sampleScriptUpdatesPeakOnlyWhenHigher()
    {
        String script = RedisHistoryService.SAMPLE_SCRIPT.getScriptAsString();

        assertTrue(script.contains("online > peak"));
        assertTrue(script.contains("players > peakPlayers"));
        assertTrue(script.contains("HINCRBY', KEYS[1], 'samples', 1"));
    }

    @Test
    void sampleIsSkippedWhenAnotherCopyTookTheInterval()
    {
        when(value.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertFalse(service.sample(NOW));

        verifyNoInteractions(onlineUserService);
        verify(redisTemplate, never()).execute(eq(RedisHistoryService.SAMPLE_SCRIPT), anyList(), any(Object[].class));
    }

    @Test
    void sampleLockIsBoundToInterval()
    {
        when(value.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        long slot = NOW.toEpochMilli() / Duration.ofMinutes(5).toMillis();

        service.sample(NOW);

        verify(value).setIfAbsent("online:history:sample-lock:" + slot, "1", Duration.ofMinutes(10));
    }

    @Test
    void historyListsEveryDayOldestFirstEndingToday()
    {
        properties.getHistory().setZone(java.time.ZoneId.of("Europe/Moscow"));

        HistoryService.History history = service.getHistory(3, NOW);

        assertEquals("Europe/Moscow", history.zone());
        assertEquals("2026-11-14", history.from());
        assertEquals("2026-11-16", history.to());
        assertEquals(
                List.of("2026-11-14", "2026-11-15", "2026-11-16"),
                history.total().days().stream().map(HistoryService.HistoryDay::date).toList()
        );
        assertEquals(List.of("5e14", "5e24"), history.sites().stream().map(HistoryService.SiteSeries::siteId).toList());
        assertEquals(3, history.sites().getFirst().days().size());
    }

    @Test
    void dayWithoutDataHasNullNumbers()
    {
        HistoryService.History history = service.getHistory(1, NOW);

        assertEquals(HistoryService.HistoryDay.empty("2026-11-16"), history.total().days().getFirst());
        assertEquals(HistoryService.HistoryDay.empty("2026-11-16"), history.sites().getFirst().days().getFirst());
    }

    @Test
    void dayWithSamplesButNoVisitorsHasZeros()
    {
        when(hash.entries("online:5e14:history:2026-11-16")).thenReturn(Map.of(
                "peak", "0", "peakAt", "2026-11-16T00:05:00+03:00", "sum", "0", "samples", "3", "peakPlayers", "0"
        ));

        HistoryService.HistoryDay day = service.getHistory(1, NOW).sites().getFirst().days().getFirst();

        assertEquals(
                new HistoryService.HistoryDay("2026-11-16", 0L, 0L, 0L, 0L, "2026-11-16T00:05:00+03:00", 0.0, 0L),
                day
        );
    }

    @Test
    void todayCountsVisitorsFromExactLists()
    {
        mockCount(List.of(
                "online:5e14:history:2026-11-16:people:guest",
                "online:5e14:history:2026-11-16:people:registered"
        ), "1", List.of(15L, 12L, 3L));

        HistoryService.HistoryDay day = service.getHistory(1, NOW).sites().getFirst().days().getFirst();

        assertEquals(new HistoryService.HistoryDay("2026-11-16", 15L, 12L, 3L, null, null, null, null), day);
    }

    @Test
    void totalCountsUnionOfAllSitesLists()
    {
        mockCount(List.of(
                "online:5e14:history:2026-11-16:people:guest",
                "online:5e24:history:2026-11-16:people:guest",
                "online:5e14:history:2026-11-16:people:registered",
                "online:5e24:history:2026-11-16:people:registered"
        ), "2", List.of(40L, 30L, 10L));

        HistoryService.HistoryDay day = service.getHistory(1, NOW).total().days().getFirst();

        assertEquals(new HistoryService.HistoryDay("2026-11-16", 40L, 30L, 10L, null, null, null, null), day);
    }

    @Test
    void pastDayReadsCountsMovedIntoDayHash()
    {
        when(hash.entries("online:5e14:history:2026-11-15")).thenReturn(Map.of(
                "visitors", "20", "guests", "18", "registered", "2"
        ));

        HistoryService.HistoryDay day = service.getHistory(2, NOW).sites().getFirst().days().getFirst();

        assertEquals(new HistoryService.HistoryDay("2026-11-15", 20L, 18L, 2L, null, null, null, null), day);
        verify(redisTemplate, never()).execute(
                eq(RedisHistoryService.COUNT_SCRIPT),
                eq(List.of(
                        "online:5e14:history:2026-11-15:people:guest",
                        "online:5e14:history:2026-11-15:people:registered"
                )),
                any(Object[].class)
        );
    }

    @Test
    void daysOlderThanListsDoNotRunCountScript()
    {
        service.getHistory(30, NOW);

        // Списки живут трое суток: считаем сегодня и три дня назад - по каждому сайту и по всем вместе.
        verify(redisTemplate, times(4 * 3)).execute(
                eq(RedisHistoryService.COUNT_SCRIPT), anyList(), any(Object[].class)
        );
    }

    @Test
    void switchDayTakesLargerOfLegacyAndExactCounts()
    {
        String guests = "online:5e14:history:2026-11-16:visitors:guest";
        String registered = "online:5e14:history:2026-11-16:visitors:registered";
        when(hll.size(guests, registered)).thenReturn(30L);
        when(hll.size(guests)).thenReturn(25L);
        when(hll.size(registered)).thenReturn(6L);
        mockCount(List.of(
                "online:5e14:history:2026-11-16:people:guest",
                "online:5e14:history:2026-11-16:people:registered"
        ), "1", List.of(12L, 10L, 2L));

        HistoryService.HistoryDay day = service.getHistory(1, NOW).sites().getFirst().days().getFirst();

        assertEquals(new HistoryService.HistoryDay("2026-11-16", 30L, 25L, 6L, null, null, null, null), day);
    }

    @Test
    void legacyDayCountsVisitorsAndAveragesSamples()
    {
        String guests = "online:5e14:history:2026-11-16:visitors:guest";
        String registered = "online:5e14:history:2026-11-16:visitors:registered";
        when(hll.size(guests, registered)).thenReturn(812L);
        when(hll.size(guests)).thenReturn(700L);
        when(hll.size(registered)).thenReturn(113L);
        when(hash.entries("online:5e14:history:2026-11-16")).thenReturn(Map.of(
                "peak", "41", "peakAt", "2026-11-16T21:35:00+03:00", "sum", "52", "samples", "3", "peakPlayers", "3"
        ));

        HistoryService.HistoryDay day = service.getHistory(1, NOW).sites().getFirst().days().getFirst();

        assertEquals(
                new HistoryService.HistoryDay("2026-11-16", 812L, 700L, 113L, 41L, "2026-11-16T21:35:00+03:00", 17.3, 3L),
                day
        );
    }

    @Test
    void legacyTotalCountsUnionOfAllSitesAndReadsCombinedSamples()
    {
        String g24 = "online:5e24:history:2026-11-16:visitors:guest";
        String g14 = "online:5e14:history:2026-11-16:visitors:guest";
        String r24 = "online:5e24:history:2026-11-16:visitors:registered";
        String r14 = "online:5e14:history:2026-11-16:visitors:registered";
        // Сайты в ответе отсортированы, поэтому и ключи идут в порядке 5e14, 5e24.
        when(hll.size(g14, g24, r14, r24)).thenReturn(50L);
        when(hll.size(g14, g24)).thenReturn(40L);
        when(hll.size(r14, r24)).thenReturn(12L);

        HistoryService.HistoryDay day = service.getHistory(1, NOW).total().days().getFirst();

        assertEquals(new HistoryService.HistoryDay("2026-11-16", 50L, 40L, 12L, null, null, null, null), day);
        verify(hash).entries("online:all:history:2026-11-16");
    }

    @Test
    void daysAreClampedToAllowedRange()
    {
        assertEquals(1, service.getHistory(0, NOW).total().days().size());
        assertEquals(365, service.getHistory(1000, NOW).total().days().size());
        assertEquals("2025-11-17", service.getHistory(1000, NOW).from());
    }

    private static List<String> finalizeKeys(String day)
    {
        String p14 = "online:5e14:history:" + day;
        String p24 = "online:5e24:history:" + day;

        return List.of(
                "online:all:history:" + day, p14, p24,
                p14 + ":people:guest", p24 + ":people:guest",
                p14 + ":people:registered", p24 + ":people:registered",
                p14 + ":people:linked", p24 + ":people:linked"
        );
    }

    @SuppressWarnings("rawtypes")
    private void mockCount(List<String> keys, String sites, List<Long> result)
    {
        when(redisTemplate.execute(eq(RedisHistoryService.COUNT_SCRIPT), eq(keys), eq(sites)))
                .thenReturn((List) result);
    }
}
