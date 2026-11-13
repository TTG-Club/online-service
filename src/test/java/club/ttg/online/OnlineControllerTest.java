package club.ttg.online;

import club.ttg.online.service.OnlineUserService;
import club.ttg.online.service.PreferenceStatsService;
import club.ttg.online.service.UsageStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OnlineControllerTest
{
    private final OnlineUserService service = mock(OnlineUserService.class);
    private final PreferenceStatsService preferenceStatsService = mock(PreferenceStatsService.class);
    private final OnlineProperties properties = new OnlineProperties();
    private final UsageStatsService usageStatsService = mock(UsageStatsService.class);
    private final OnlineController controller = new OnlineController(
            service,
            preferenceStatsService,
            usageStatsService,
            properties
    );
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

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
        OnlineController.OnlineStatsResponse allSites = controller.stats(null);

        assertEquals(preferences, response.preferences());
        assertEquals(preferences, allSites.sites().getFirst().preferences());
    }

    @Test
    void unknownSiteStatsHaveNoPreferences()
    {
        OnlineController.OnlineSiteStatsResponse response = controller.siteStats("unknown", null);

        assertEquals(List.of(), response.preferences());
    }

    @Test
    void usageRecordsMarkAndRepliesNoContent() throws Exception
    {
        postUsage(usageBody("new", "dm-screen", "60")).andExpect(status().isNoContent());

        verify(usageStatsService).record(eq("new"), eq("dm-screen"), eq("visitor-123"), eq(60), any(Instant.class));
    }

    @Test
    void usageAcceptsPageOpenWithoutSeconds() throws Exception
    {
        postUsage(usageBody("new", "dm-screen", "0")).andExpect(status().isNoContent());

        verify(usageStatsService).record(eq("new"), eq("dm-screen"), eq("visitor-123"), eq(0), any(Instant.class));
    }

    @Test
    void usageRejectsActiveSecondsOutOfRange() throws Exception
    {
        postUsage(usageBody("new", "dm-screen", "301")).andExpect(status().isBadRequest());
        postUsage(usageBody("new", "dm-screen", "-1")).andExpect(status().isBadRequest());
        postUsage(usageBody("new", "dm-screen", "null")).andExpect(status().isBadRequest());

        verifyUsageNotRecorded();
    }

    @Test
    void usageRejectsInvalidFeatureAndMissingFields() throws Exception
    {
        postUsage(usageBody("new", "DM Screen", "60")).andExpect(status().isBadRequest());
        postUsage("""
                {"siteId":"new","feature":"dm-screen","type":"GUEST","activeSeconds":60}
                """).andExpect(status().isBadRequest());
        postUsage("""
                {"siteId":"new","feature":"dm-screen","key":"visitor-123","activeSeconds":60}
                """).andExpect(status().isBadRequest());

        verifyUsageNotRecorded();
    }

    @Test
    void usageOfUnknownSiteIsNotFound() throws Exception
    {
        postUsage(usageBody("unknown", "dm-screen", "60")).andExpect(status().isNotFound());

        verifyUsageNotRecorded();
    }

    @Test
    void siteStatsIncludeUsage()
    {
        List<UsageStatsService.FeatureUsageStats> usage = List.of(
                new UsageStatsService.FeatureUsageStats(
                        "dm-screen",
                        List.of(new UsageStatsService.UsagePeriodStats(1, 40, 18, 0, 18, 1340))
                )
        );
        when(service.getCount(eq("new"), eq(Duration.ofMinutes(30)), any(Instant.class)))
                .thenReturn(new OnlineUserService.OnlineCount(2, 3, 0));
        when(usageStatsService.getStats(eq("new"), any(Instant.class))).thenReturn(usage);

        OnlineController.OnlineSiteStatsResponse response = controller.siteStats("new", null);
        OnlineController.OnlineStatsResponse allSites = controller.stats(null);

        assertEquals(usage, response.usage());
        assertEquals(usage, allSites.sites().getFirst().usage());
    }

    @Test
    void unknownSiteStatsHaveNoUsage()
    {
        OnlineController.OnlineSiteStatsResponse response = controller.siteStats("unknown", null);

        assertEquals(List.of(), response.usage());
    }

    private ResultActions postUsage(String body) throws Exception
    {
        return mockMvc.perform(post("/api/v1/online/usage").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void verifyUsageNotRecorded()
    {
        verify(usageStatsService, never()).record(anyString(), anyString(), anyString(), anyInt(), any(Instant.class));
    }

    private static String usageBody(String siteId, String feature, String activeSeconds)
    {
        return """
                {"siteId":"%s","feature":"%s","key":"visitor-123","type":"REGISTERED","activeSeconds":%s}
                """.formatted(siteId, feature, activeSeconds);
    }
}
