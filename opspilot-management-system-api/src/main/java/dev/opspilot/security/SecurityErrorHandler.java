package dev.opspilot.security;

import dev.opspilot.dtos.ApiErrorResponse;
import dev.opspilot.enums.ApiHttpStatus;
import dev.opspilot.security.filters.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityErrorHandler.class);

    private final ObjectMapper objectMapper;

    public SecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        log.warn("Authentication required");
        writeError(
                response,
                ApiHttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "Authentication is required"
        );
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException {
        log.warn("Access denied");
        writeError(response, ApiHttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied");
    }

    private void writeError(
            HttpServletResponse response,
            ApiHttpStatus status,
            String code,
            String message
    ) throws IOException {
        String traceId = currentTraceId();
        ApiErrorResponse body = new ApiErrorResponse(
                status.getCode(),
                code,
                message,
                traceId,
                Map.of()
        );

        response.setStatus(status.getCode());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(RequestIdFilter.REQUEST_ID_HEADER, traceId);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static String currentTraceId() {
        String traceId = MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY);
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }
}
