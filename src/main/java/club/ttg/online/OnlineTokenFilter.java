package club.ttg.online;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Межсервисный токен: записывать данные (heartbeat, usage) могут только бэки сайтов, которые
 * знают общий секрет. Чтение статистики токеном не закрыто.
 * <p>
 * Пока токен в настройках не задан, проверка выключена: так сервис можно обновить раньше,
 * чем секрет пропишут в окружении, и приём сигналов при этом не сломается.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnlineTokenFilter extends OncePerRequestFilter
{
    public static final String TOKEN_HEADER = "X-Online-Token";

    private static final String API_PREFIX = "/api/v1/online/";

    private final OnlineProperties properties;

    @Override
    protected void initFilterBean()
    {
        if (!isEnabled())
        {
            log.warn("online.api-token не задан: приём heartbeat и usage открыт без проверки токена");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request)
    {
        return !isEnabled()
                || !HttpMethod.POST.matches(request.getMethod())
                || !request.getRequestURI().startsWith(request.getContextPath() + API_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException
    {
        String token = request.getHeader(TOKEN_HEADER);

        if (token == null || !matches(token))
        {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isEnabled()
    {
        String expected = properties.getApiToken();

        return expected != null && !expected.isBlank();
    }

    private boolean matches(String token)
    {
        // Сравнение за постоянное время, чтобы токен нельзя было подобрать по времени ответа.
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                properties.getApiToken().getBytes(StandardCharsets.UTF_8)
        );
    }
}
