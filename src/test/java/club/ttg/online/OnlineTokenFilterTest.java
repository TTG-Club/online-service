package club.ttg.online;

import club.ttg.online.service.OnlineUserService;
import club.ttg.online.service.PreferenceStatsService;
import club.ttg.online.service.UsageStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OnlineTokenFilterTest
{
    private static final String USAGE_BODY = """
            {"siteId":"new","feature":"dm-screen","key":"visitor-123","type":"GUEST","activeSeconds":60}
            """;
    private static final String HEARTBEAT_BODY = """
            {"siteId":"new","key":"visitor-123","type":"GUEST"}
            """;

    private final OnlineUserService service = mock(OnlineUserService.class);
    private final UsageStatsService usageStatsService = mock(UsageStatsService.class);
    private final OnlineProperties properties = new OnlineProperties();
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new OnlineController(
                    service,
                    mock(PreferenceStatsService.class),
                    usageStatsService,
                    properties
            ))
            .addFilters(new OnlineTokenFilter(properties))
            .build();

    OnlineTokenFilterTest()
    {
        properties.setAllowedSites(List.of("new"));
        properties.setApiToken("secret");
        when(service.getTotalCount(any(Duration.class), any(Instant.class)))
                .thenReturn(new OnlineUserService.OnlineCount(1, 0, 0));
        when(service.getCount(anyString(), any(Duration.class), any(Instant.class)))
                .thenReturn(new OnlineUserService.OnlineCount(1, 0, 0));
    }

    @Test
    void writeWithValidTokenIsAccepted() throws Exception
    {
        mockMvc.perform(json(post("/api/v1/online/usage"), USAGE_BODY).header("X-Online-Token", "secret"))
                .andExpect(status().isNoContent());
        mockMvc.perform(json(post("/api/v1/online/heartbeat"), HEARTBEAT_BODY).header("X-Online-Token", "secret"))
                .andExpect(status().isOk());

        verify(usageStatsService).record(eq("new"), eq("dm-screen"), eq("visitor-123"), eq(60), any(Instant.class));
    }

    @Test
    void writeWithoutTokenIsUnauthorized() throws Exception
    {
        mockMvc.perform(json(post("/api/v1/online/usage"), USAGE_BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(post("/api/v1/online/heartbeat"), HEARTBEAT_BODY)).andExpect(status().isUnauthorized());

        verifyNothingRecorded();
    }

    @Test
    void writeWithWrongTokenIsUnauthorized() throws Exception
    {
        mockMvc.perform(json(post("/api/v1/online/usage"), USAGE_BODY).header("X-Online-Token", "guess"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(json(post("/api/v1/online/heartbeat"), HEARTBEAT_BODY).header("X-Online-Token", ""))
                .andExpect(status().isUnauthorized());

        verifyNothingRecorded();
    }

    @Test
    void statsAreReadableWithoutToken() throws Exception
    {
        mockMvc.perform(get("/api/v1/online/stats")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/online/stats/new")).andExpect(status().isOk());
    }

    @Test
    void checkIsOffWhileTokenIsNotConfigured() throws Exception
    {
        properties.setApiToken("");

        mockMvc.perform(json(post("/api/v1/online/usage"), USAGE_BODY)).andExpect(status().isNoContent());
        mockMvc.perform(json(post("/api/v1/online/heartbeat"), HEARTBEAT_BODY)).andExpect(status().isOk());
    }

    private void verifyNothingRecorded()
    {
        verify(usageStatsService, never()).record(anyString(), anyString(), anyString(), anyInt(), any(Instant.class));
        verify(service, never()).heartbeat(any(), anyString(), anyString(), any(), anyBoolean(), any(Instant.class));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body)
    {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
