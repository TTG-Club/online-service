package club.ttg.online;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Межсервисный токен: записывать данные сайта (heartbeat, usage) могут только бэки сайтов,
 * которые знают общий секрет. Чтение статистики токеном не закрыто.
 * <p>
 * Пока токен в настройках не задан, проверка выключена: так сервис можно обновить раньше,
 * чем секрет пропишут в окружении, и приём сигналов при этом не сломается.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnlineTokenGuard implements InitializingBean
{
    public static final String TOKEN_HEADER = "X-Online-Token";

    private final OnlineProperties properties;

    @Override
    public void afterPropertiesSet()
    {
        if (!isEnabled())
        {
            log.warn("online.api-token не задан: приём heartbeat и usage открыт без проверки токена");
        }
    }

    /**
     * @param siteId уже нормализованный сайт из запроса
     * @param token  значение заголовка X-Online-Token, может отсутствовать
     * @return можно ли принять запись для этого сайта
     */
    public boolean allows(String siteId, String token)
    {
        if (!isEnabled() || properties.getTokenExemptSites().contains(siteId))
        {
            return true;
        }

        // Сравнение за постоянное время, чтобы токен нельзя было подобрать по времени ответа.
        return token != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                properties.getApiToken().getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean isEnabled()
    {
        String expected = properties.getApiToken();

        return expected != null && !expected.isBlank();
    }
}
