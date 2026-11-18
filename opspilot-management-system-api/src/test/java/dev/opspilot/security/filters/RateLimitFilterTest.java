package dev.opspilot.security.filters;

import dev.opspilot.configs.AppProperties;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    @Test
    void rejectsRequestAfterBurstCapacityAndReturnsCalculatedRetryAfter() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(properties(true, 60, 2));

        assertThat(invoke(filter, "/api/v1/system/ping").getStatus()).isEqualTo(200);
        assertThat(invoke(filter, "/api/v1/system/ping").getStatus()).isEqualTo(200);

        MockHttpServletResponse rejected = invoke(filter, "/api/v1/system/ping");

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentType()).startsWith("application/problem+json");
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("1");
        assertThat(rejected.getHeader(RateLimitFilter.LIMIT_HEADER)).isEqualTo("60");
        assertThat(rejected.getContentAsString()).contains("Too Many Requests");
    }

    @Test
    void doesNotLimitSwaggerOrActuatorPaths() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(properties(true, 1, 1));

        assertThat(invoke(filter, "/swagger-ui/index.html").getStatus()).isEqualTo(200);
        assertThat(invoke(filter, "/swagger-ui/index.html").getStatus()).isEqualTo(200);
        assertThat(invoke(filter, "/actuator/health").getStatus()).isEqualTo(200);
    }

    @Test
    void bypassesLimiterWhenDisabled() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(properties(false, 1, 1));

        assertThat(invoke(filter, "/api/v1/system/ping").getStatus()).isEqualTo(200);
        assertThat(invoke(filter, "/api/v1/system/ping").getStatus()).isEqualTo(200);
    }

    private static MockHttpServletResponse invoke(RateLimitFilter filter, String path)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static AppProperties properties(boolean enabled, int requestsPerMinute, int burstCapacity) {
        return new AppProperties(
                "opspilot-api",
                "/api/v1",
                new AppProperties.RateLimit(enabled, requestsPerMinute, burstCapacity, 100, 15),
                new AppProperties.Cors(List.of("http://localhost:3000"))
        );
    }
}
