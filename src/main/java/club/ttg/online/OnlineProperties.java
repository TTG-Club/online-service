package club.ttg.online;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "online")
public class OnlineProperties
{
    private List<String> allowedSites = new ArrayList<>();

    private long defaultWindowMinutes = 30;
    private long minWindowMinutes = 1;
    private long maxWindowMinutes = 1440;

    private Redis redis = new Redis();
    private Cors cors = new Cors();
    private Preferences preferences = new Preferences();

    @Getter
    @Setter
    public static class Redis
    {
        private String keyPrefix = "online:";
        private Cleanup cleanup = new Cleanup();
    }

    @Getter
    @Setter
    public static class Cleanup
    {
        private boolean enabled = true;
        private Duration fixedDelay = Duration.ofSeconds(30);
    }

    @Getter
    @Setter
    public static class Preferences
    {
        /**
         * Какие настройки отображения учитывать. Остальные из сигнала отбрасываются.
         */
        private List<String> tracked = new ArrayList<>(List.of("theme", "width"));

        /**
         * Сколько разных значений одной настройки запоминать, чтобы мусор не плодил ключи.
         */
        private int maxValues = 16;

        /**
         * Сколько дней хранить дневные счётчики.
         */
        private int retentionDays = 30;

        /**
         * Периоды в днях, за которые отдавать статистику.
         */
        private List<Integer> periodsDays = new ArrayList<>(List.of(1, 7, 30));

        /**
         * Часовой пояс, по которому режутся сутки.
         */
        private ZoneId zone = ZoneId.of("Europe/Moscow");
    }

    @Getter
    @Setter
    public static class Cors
    {
        private boolean enabled = false;
        private List<String> allowedOrigins = new ArrayList<>();
        private List<String> allowedMethods = new ArrayList<>();
        private List<String> allowedHeaders = new ArrayList<>();
        private boolean allowCredentials = false;
    }
}
