package dev.opspilot.security;

import dev.opspilot.security.authentication.OpsPilotPrincipal;

import dev.opspilot.dtos.ApiErrorResponse;
import dev.opspilot.enums.ApiHttpStatus;
import dev.opspilot.enums.ErrorCode;
import dev.opspilot.security.filters.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Trả lỗi 401/403 của Spring Security theo {@link ApiErrorResponse}, không lộ chi tiết exception.
 *
 * <ul>
 *   <li>401: thêm {@code WWW-Authenticate} tối giản theo RFC 6750 ({@code Bearer} hoặc
 *       {@code Bearer error="invalid_token"}), không kèm error_description/resource_metadata.</li>
 *   <li>403: phân biệt thiếu/sai CSRF token, bắt buộc đổi mật khẩu (KAN-78) và từ chối quyền thông thường.</li>
 * </ul>
 */
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
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                exception instanceof OAuth2AuthenticationException ? "Bearer error=\"invalid_token\"" : "Bearer");
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
        if (exception instanceof CsrfException) {
            log.warn("CSRF token missing or invalid");
            writeError(response, ApiHttpStatus.FORBIDDEN, ErrorCode.CSRF_TOKEN_INVALID.name(),
                    ErrorCode.CSRF_TOKEN_INVALID.defaultMessage());
            return;
        }
        if (mustChangePassword()) {
            log.warn("Access denied until password is changed");
            writeError(response, ApiHttpStatus.FORBIDDEN, ErrorCode.PASSWORD_CHANGE_REQUIRED.name(),
                    ErrorCode.PASSWORD_CHANGE_REQUIRED.defaultMessage());
            return;
        }
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

    private static boolean mustChangePassword() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof OpsPilotPrincipal principal
                && principal.mustChangePassword();
    }

    private static String currentTraceId() {
        String traceId = MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY);
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }
}
