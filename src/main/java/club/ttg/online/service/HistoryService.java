package club.ttg.online.service;

import club.ttg.online.OnlineType;

import java.time.Instant;
import java.util.List;

/**
 * История посещаемости по суткам: уникальные посетители, пик и средний онлайн.
 */
public interface HistoryService
{
    /**
     * siteId, под которым копятся замеры онлайна по всем сайтам вместе.
     */
    String ALL_SITES = "all";

    /**
     * Запоминает посетителя в дневном счётчике уникальных.
     */
    void recordVisit(String siteId, OnlineType type, String visitorKey, Instant now);

    /**
     * Снимает текущий онлайн по каждому сайту и по всем вместе. Если сервис запущен в
     * нескольких копиях, замер за интервал делает только одна.
     *
     * @return {@code true}, если замер сделан этой копией
     */
    boolean sample(Instant now);

    /**
     * История за последние {@code days} суток, включая сегодняшние.
     */
    History getHistory(int days, Instant now);

    record History(String zone, String from, String to, Series total, List<SiteSeries> sites)
    {
    }

    record Series(List<HistoryDay> days)
    {
    }

    record SiteSeries(String siteId, List<HistoryDay> days)
    {
    }

    /**
     * День, по которому ничего не записано, отдаётся с датой и {@code null} во всех остальных полях.
     */
    record HistoryDay(
            String date,
            Long visitors,
            Long guests,
            Long registered,
            Long peakOnline,
            String peakAt,
            Double averageOnline,
            Long peakPlayers
    )
    {
        public static HistoryDay empty(String date)
        {
            return new HistoryDay(date, null, null, null, null, null, null, null);
        }
    }
}
