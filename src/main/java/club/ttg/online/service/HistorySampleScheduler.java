package club.ttg.online.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Раз в {@code online.history.sample-interval} снимает текущий онлайн для истории по суткам.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HistorySampleScheduler
{
    private final HistoryService historyService;

    @Scheduled(fixedRateString = "#{@onlineProperties.history.sampleInterval.toMillis()}")
    public void sample()
    {
        try
        {
            historyService.sample(Instant.now());
        }
        catch (RuntimeException e)
        {
            log.warn("Не удалось снять замер онлайна для истории: {}", e.getMessage());
        }
    }
}
