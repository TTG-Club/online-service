package club.ttg.online.service;

import java.time.Instant;
import java.util.List;

/**
 * Учёт работы с инструментами сайта (например, «Ширма Мастера»). Открытие страницы
 * пользованием не считается: человек «пользовался» в тот день, когда набрал за инструментом
 * не меньше заданного в настройках времени.
 */
public interface UsageStatsService
{
    /**
     * @param feature       инструмент. Отметки по неотслеживаемым инструментам молча отбрасываются.
     * @param activeSeconds сколько секунд работы прибавить посетителю за сегодня. Ноль значит
     *                      «открыл страницу»: посетитель запоминается без времени работы.
     */
    void record(String siteId, String feature, String visitorKey, int activeSeconds, Instant now);

    /**
     * @return по одной записи на каждый отслеживаемый инструмент, даже если данных по нему нет
     */
    List<FeatureUsageStats> getStats(String siteId, Instant now);

    /**
     * @param periods по одному срезу на каждый период из настроек сервиса
     */
    record FeatureUsageStats(String feature, List<UsagePeriodStats> periods)
    {
    }

    /**
     * «Засчитанный день» посетителя — день, в который он набрал не меньше порога работы.
     *
     * @param days          за сколько последних дней (включая сегодня) посчитан срез
     * @param visitors      сколько разных посетителей открывали инструмент
     * @param users         сколько разных посетителей имеют хотя бы один засчитанный день
     * @param returning     сколько разных посетителей имеют два и более засчитанных дня
     * @param activeDays    сколько всего засчитанных дней, сумма по посетителям
     * @param activeMinutes сколько минут набрано за засчитанные дни, с округлением вниз
     */
    record UsagePeriodStats(int days, long visitors, long users, long returning, long activeDays, long activeMinutes)
    {
    }
}
