package club.ttg.online;

import club.ttg.online.service.OnlineUserService;
import club.ttg.online.service.PreferenceStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnlineControllerTest
{
    private final OnlineUserService service = mock(OnlineUserService.class);
    private final PreferenceStatsService preferenceStatsService = mock(PreferenceStatsService.class);
    private final OnlineProperties properties = new OnlineProperties();
    private final OnlineController controller = new OnlineController(service, preferenceStatsService, properties);

    OnlineControllerTest()
    {
        properties.setAllowedSites(List.of("new"));
    }

    @Test
    void heartbeatReturnsTotalForAllSitesDefaultWindow()
    {
        OnlineController.HeartbeatRequest request = new OnlineController.HeartbeatRequest(
                "new",
                "visitor-123",
                null,
                OnlineType.GUEST,
                null,
                null
        );
        when(service.getTotalCount(eq(Duration.ofMinutes(30)), any(Instant.class)))
                .thenReturn(new OnlineUserService.OnlineCount(2, 3, 0));

        ResponseEntity<OnlineController.HeartbeatResponse> response = controller.heartbeat(request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(5, response.getBody().total());
        verify(service).heartbeat(
                eq(OnlineType.GUEST),
                eq("new"),
                eq("visitor-123"),
                eq(null),
                eq(false),
                any(Instant.class)
        );
        verify(service).getTotalCount(eq(Duration.ofMinutes(30)), any(Instant.class));
    }

    @Test
    void heartbeatPassesWorldFlagToService()
    {
        OnlineController.HeartbeatRequest request = new OnlineController.HeartbeatRequest(
                "new",
                "user-42",
                null,
                OnlineType.REGISTERED,
                true,
                null
        );
        when(service.getTotalCount(eq(Duration.ofMinutes(30)), any(Instant.class)))
                .thenReturn(new OnlineUserService.OnlineCount(0, 1, 1));

        controller.heartbeat(request);

        verify(service).heartbeat(
                eq(OnlineType.REGISTERED),
                eq("new"),
                eq("user-42"),
                eq(null),
                eq(true),
                any(Instant.class)
        );
    }

    @Test
    void statsReportPlayersWithoutAddingThemToTotal()
    {
        when(service.getCount(eq("new"), eq(Duration.ofMinutes(30)), any(Instant.class)))
                .thenReturn(new OnlineUserService.OnlineCount(2, 3, 4));

        OnlineController.OnlineStatsResponse response = controller.stats(null);

        assertEquals(1, response.sites().size());
        assertEquals(4, response.sites().getFirst().players());
        assertEquals(5, response.sites().getFirst().total());
        assertEquals(4, response.total().players());
        assertEquals(5, response.total().total());
    }

    @Test
    void heartbeatPassesPreferencesToPreferenceStats()
    {
        Map<String, String> preferences = Map.of("theme", "light", "width", "default");
        OnlineController.HeartbeatRequest request = new OnlineController.HeartbeatRequest(
                "new",
                "visitor-123",
                null,
                OnlineType.GUEST,
                null,
                preferences
        );
        when(service.getTotalCount(eq(Duration.ofMinutes(30)), any(Instant.class)))
                .thenReturn(new OnlineUserService.OnlineCount(1, 0, 0));

        controller.heartbeat(request);

        verify(preferenceStatsService).record(eq("new"), eq("visitor-123"), eq(preferences), any(Instant.class));
    }

    @Test
    void siteStatsIncludePreferences()
    {
        List<PreferenceStatsService.PreferencePeriodStats> preferences = List.of(
                new PreferenceStatsService.PreferencePeriodStats(1, Map.of("theme", Map.of("dark", 7L)))
        );
        when(service.getCount(eq("new"), eq(Duration.ofMinutes(30)), any(Instant.class)))
                .thenReturn(new OnlineUserService.OnlineCount(2, 3, 0));
        when(preferenceStatsService.getStats(eq("new"), any(Instant.class))).thenReturn(preferences);

        OnlineController.OnlineSiteStatsResponse response = controller.siteStats("new", null);

        assertEquals(preferences, response.preferences());
    }

    @Test
    void unknownSiteStatsHaveNoPreferences()
    {
        OnlineController.OnlineSiteStatsResponse response = controller.siteStats("unknown", null);

        assertEquals(List.of(), response.preferences());
    }
}
