package dev.opspilot.security;

import dev.opspilot.security.SecurityErrorHandler;
import dev.opspilot.security.filters.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityErrorHandlerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final SecurityErrorHandler handler = new SecurityErrorHandler(objectMapper);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void authenticationFailureReturnsSanitized401WithTraceId() throws Exception {
        MDC.put(RequestIdFilter.TRACE_ID_MDC_KEY, "trace-401");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.commence(
                new MockHttpServletRequest(),
                response,
                new BadCredentialsException("raw JWT secret-token")
        );

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("trace-401");
        assertThat(body.path("code").stringValue()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(body.path("traceId").stringValue()).isEqualTo("trace-401");
        assertThat(response.getContentAsString()).doesNotContain("secret-token").doesNotContain("JWT");
    }

    @Test
    void authorizationFailureReturnsSanitized403() throws Exception {
        MDC.put(RequestIdFilter.TRACE_ID_MDC_KEY, "trace-403");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(
                new MockHttpServletRequest(),
                response,
                new AccessDeniedException("sensitive authorization detail")
        );

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body.path("code").stringValue()).isEqualTo("ACCESS_DENIED");
        assertThat(body.path("traceId").stringValue()).isEqualTo("trace-403");
        assertThat(response.getContentAsString()).doesNotContain("sensitive authorization detail");
    }
}
