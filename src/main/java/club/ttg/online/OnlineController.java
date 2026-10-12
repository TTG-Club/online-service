package club.ttg.online;

import club.ttg.online.service.OnlineUserService;
import club.ttg.online.service.PreferenceStatsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/online")
@Validated
public class OnlineController
{
    private final OnlineUserService service;
    private final PreferenceStatsService preferenceStatsService;
    private final OnlineProperties properties;

    @PostMapping("/heartbeat")
    public ResponseEntity<HeartbeatResponse> heartbeat(@Valid @RequestBody HeartbeatRequest request)
    {
        String siteId = normalizeSiteId(request.siteId());

        if (!properties.getAllowedSites().contains(siteId))
        {
            return ResponseEntity.notFound().build();
        }

        Instant now = Instant.now();
        service.heartbeat(
                request.type(),
                siteId,
                request.key(),
                request.previousGuestKey(),
                Boolean.TRUE.equals(request.inWorld()),
                now
        );
        preferenceStatsService.record(siteId, request.key(), request.preferences(), now);
        OnlineUserService.OnlineCount count = service.getTotalCount(
                Duration.ofMinutes(properties.getDefaultWindowMinutes()),
                now
        );

        return ResponseEntity.ok(new HeartbeatResponse(count.total()));
    }

    @GetMapping("/stats")
    public OnlineStatsResponse stats(@RequestParam(required = false) Long windowMinutes)
    {
        Duration window = resolveWindow(windowMinutes);
        Instant now = Instant.now();

        List<OnlineSiteStats> sites = properties.getAllowedSites().stream()
                .map(siteId -> buildSiteStats(siteId, window, now))
                .sorted(Comparator.comparing(OnlineSiteStats::siteId))
                .toList();

        OnlineTotals totals = sites.stream()
                .reduce(
                        new OnlineTotals(0, 0, 0),
                        (acc, s) -> new OnlineTotals(
                                acc.guests() + s.guests(),
                                acc.registered() + s.registered(),
                                acc.players() + s.players()
                        ),
                        (a, b) -> new OnlineTotals(
                                a.guests() + b.guests(),
                                a.registered() + b.registered(),
                                a.players() + b.players()
                        )
                );

        return new OnlineStatsResponse(
                window.toMinutes(),
                new OnlineTotalsWithTotal(totals.guests(), totals.registered(), totals.players(), totals.total()),
                sites
        );
    }

    @GetMapping("/stats/{siteId}")
    public OnlineSiteStatsResponse siteStats(
            @PathVariable String siteId,
            @RequestParam(required = false) Long windowMinutes
    )
    {
        String normalized = normalizeSiteId(siteId);

        if (!properties.getAllowedSites().contains(normalized))
        {
            return new OnlineSiteStatsResponse(
                    resolveWindow(windowMinutes).toMinutes(),
                    normalized,
                    0,
                    0,
                    0,
                    0,
                    List.of()
            );
        }

        Duration window = resolveWindow(windowMinutes);
        Instant now = Instant.now();

        OnlineSiteStats stats = buildSiteStats(normalized, window, now);

        return new OnlineSiteStatsResponse(
                window.toMinutes(),
                stats.siteId(),
                stats.guests(),
                stats.registered(),
                stats.players(),
                stats.total(),
                stats.preferences()
        );
    }

    private OnlineSiteStats buildSiteStats(String siteId, Duration window, Instant now)
    {
        OnlineUserService.OnlineCount count = service.getCount(siteId, window, now);

        return new OnlineSiteStats(
                siteId,
                count.guests(),
                count.registered(),
                count.players(),
                count.total(),
                preferenceStatsService.getStats(siteId, now)
        );
    }

    private Duration resolveWindow(Long requestedMinutes)
    {
        long minutes = (requestedMinutes == null)
                ? properties.getDefaultWindowMinutes()
                : requestedMinutes;

        long clamped = Math.clamp(minutes, properties.getMinWindowMinutes(), properties.getMaxWindowMinutes());

        return Duration.ofMinutes(clamped);
    }

    private static String normalizeSiteId(String siteId)
    {
        return (siteId == null) ? "" : siteId.trim().toLowerCase();
    }

    // ===== DTOs =====

    public record HeartbeatRequest(
            @NotBlank
            @Size(max = 32)
            @Pattern(regexp = "^[a-z0-9][a-z0-9_-]*$")
            String siteId,

            @NotBlank
            @Size(max = 128)
            String key,

            @Size(max = 128)
            String previousGuestKey,

            OnlineType type,

            /*
             * Посетитель прямо сейчас в игровом мире. Не обязателен: сайты и старые версии
             * приложения его не шлют, для них признак просто всегда снят.
             */
            Boolean inWorld,

            /*
             * Настройки отображения посетителя: тема, ширина сайта и т.п. Не обязательны:
             * сайты, которые их не шлют, просто не попадают в статистику настроек.
             */
            @Size(max = 8)
            Map<String, String> preferences
    )
    {
        public HeartbeatRequest
        {
            if (type == null)
            {
                throw new IllegalArgumentException("type is required");
            }
        }
    }

    public record HeartbeatResponse(long total)
    {
    }

    public record OnlineStatsResponse(
            long windowMinutes,
            OnlineTotalsWithTotal total,
            List<OnlineSiteStats> sites
    )
    {
    }

    public record OnlineSiteStatsResponse(
            long windowMinutes,
            String siteId,
            long guests,
            long registered,
            long players,
            long total,
            List<PreferenceStatsService.PreferencePeriodStats> preferences
    )
    {
    }

    /**
     * @param players сколько посетителей сейчас в игровых мирах. Это подмножество гостей и
     *                зарегистрированных, поэтому в {@code total} оно не складывается.
     */
    public record OnlineSiteStats(
            String siteId,
            long guests,
            long registered,
            long players,
            long total,
            List<PreferenceStatsService.PreferencePeriodStats> preferences
    )
    {
    }

    public record OnlineTotals(long guests, long registered, long players)
    {
        public long total()
        {
            return guests + registered;
        }
    }

    public record OnlineTotalsWithTotal(long guests, long registered, long players, long total)
    {
    }
}
