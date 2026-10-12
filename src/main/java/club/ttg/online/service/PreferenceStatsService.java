package club.ttg.online.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Учёт настроек отображения, которыми пользуются посетители: тема, ширина сайта и т.п.
 * Считаются уникальные посетители за день, поэтому видно, чем пользуются чаще,
 * а не как часто шлют сигналы.
 */
public interface PreferenceStatsService
{
    /**
     * @param preferences настройки посетителя: имя настройки - значение. Неотслеживаемые
     *                    настройки и некорректные значения молча отбрасываются.
     */
    void record(String siteId, String visitorKey, Map<String, String> preferences, Instant now);

    /**
     * @return по одному срезу на каждый период из настроек сервиса
     */
    List<PreferencePeriodStats> getStats(String siteId, Instant now);

    /**
     * @param days        за сколько последних дней (включая сегодня) посчитаны посетители
     * @param preferences имя настройки - значение - число уникальных посетителей. Кто менял
     *                    значение в течение периода, учтён в каждом из выбранных значений.
     */
    record PreferencePeriodStats(int days, Map<String, Map<String, Long>> preferences)
    {
    }
}
