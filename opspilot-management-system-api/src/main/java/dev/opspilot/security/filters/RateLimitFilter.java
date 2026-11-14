package dev.opspilot.security.filters;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.opspilot.configs.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    static final String LIMIT_HEADER = "X-RateLimit-Limit";
    static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private static final byte[] TOO_MANY_REQUESTS_BODY = ("{"
            + "\"type\":\"about:blank\","
            + "\"title\":\"Too Many Requests\","
            + "\"status\":429,"
            + "\"detail\":\"Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau.\""
            + "}").getBytes(StandardCharsets.UTF_8);

    private final AppProperties.RateLimit settings;
    private final String apiBasePath;
    private final Cache<String, Bucket> buckets;

    public RateLimitFilter(AppProperties properties) {
        this.settings = properties.rateLimit();
        this.apiBasePath = normalizeBasePath(properties.apiBasePath());
        this.buckets = Caffeine.newBuilder()
                .maximumSize(settings.maxClients())
                .expireAfterAccess(settings.clientIdleDuration())
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!settings.enabled()) {
            return true;
        }
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return !(requestPath.equals(apiBasePath) || requestPath.startsWith(apiBasePath + "/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Bucket bucket = buckets.get(request.getRemoteAddr(), ignored -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        response.setHeader(LIMIT_HEADER, Integer.toString(settings.requestsPerMinute()));
        response.setHeader(REMAINING_HEADER, Long.toString(probe.getRemainingTokens()));

        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = retryAfterSeconds(probe.getNanosToWaitForRefill());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentLength(TOO_MANY_REQUESTS_BODY.length);
        response.getOutputStream().write(TOO_MANY_REQUESTS_BODY);
    }

    private Bucket newBucket() {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(settings.burstCapacity())
                .refillGreedy(settings.requestsPerMinute(), Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private static long retryAfterSeconds(long nanosToWait) {
        long wholeSeconds = TimeUnit.NANOSECONDS.toSeconds(nanosToWait);
        return Math.max(1, wholeSeconds + (nanosToWait % 1_000_000_000L == 0 ? 0 : 1));
    }

    private static String normalizeBasePath(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
