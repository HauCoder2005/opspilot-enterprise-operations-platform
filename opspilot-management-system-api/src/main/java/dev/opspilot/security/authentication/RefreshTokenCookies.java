package dev.opspilot.security.authentication;

import dev.opspilot.configs.AppProperties;
import dev.opspilot.configs.auth.AuthTokenProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * Cookie refresh token cho client WEB (quyết định đã duyệt): HttpOnly + Secure + SameSite=Strict,
 * chỉ gửi tới {@code <api-base-path>/auth}. Client MOBILE nhận token trong body.
 */
@Component
public class RefreshTokenCookies {

    private final String name;
    private final boolean secure;
    private final String path;

    public RefreshTokenCookies(AuthTokenProperties tokenProperties, AppProperties appProperties) {
        this.name = tokenProperties.refreshCookie().name();
        this.secure = tokenProperties.refreshCookie().secure();
        this.path = appProperties.apiBasePath() + "/auth";
    }

    public String name() {
        return name;
    }

    public boolean isPresent(HttpServletRequest request) {
        return read(request).isPresent();
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank())
                .map(Cookie::getValue)
                .findFirst();
    }

    public void write(HttpServletResponse response, String rawToken, Instant expiresAt) {
        Duration maxAge = Duration.between(Instant.now(), expiresAt);
        response.addHeader(HttpHeaders.SET_COOKIE, base(rawToken).maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build().toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, base("").maxAge(Duration.ZERO).build().toString());
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secure).sameSite("Strict").path(path);
    }
}
