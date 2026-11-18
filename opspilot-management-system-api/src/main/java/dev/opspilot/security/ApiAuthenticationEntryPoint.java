package dev.opspilot.security;

import dev.opspilot.enums.ErrorCode;
import dev.opspilot.exceptions.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 401 cho request thiếu/sai access token, body problem+json {@code UNAUTHENTICATED}.
 *
 * <p>Header {@code WWW-Authenticate} theo RFC 6750 ở dạng tối giản: {@code Bearer} hoặc
 * {@code Bearer error="invalid_token"}. Không dùng header mặc định của Spring Security 7.1 vì nó kèm
 * {@code error_description} (lộ lý do kỹ thuật như "Invalid signature") và {@code resource_metadata} trỏ tới
 * endpoint RFC 9728 mà ứng dụng không cung cấp.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemResponses problems;

    public ApiAuthenticationEntryPoint(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String challenge = exception instanceof OAuth2AuthenticationException ? "Bearer error=\"invalid_token\"" : "Bearer";
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        problems.write(request, response, ErrorCode.UNAUTHENTICATED);
    }
}
