package dev.opspilot.configs.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Base64;

/**
 * Cấu hình token (KAN-76/77). {@code jwtSecret} chỉ lấy từ môi trường ({@code JWT_SECRET}); không có giá trị mặc định.
 *
 * @param issuer              claim {@code iss} của access token
 * @param jwtSecret           khoá HMAC-SHA256 mã hoá Base64, decode tối thiểu 32 byte
 * @param accessTokenTtl      thời hạn access token (Jira: 15 phút)
 * @param refreshTokenTtlWeb  thời hạn refresh token WEB (schema: 7 ngày)
 * @param refreshTokenTtlMobile thời hạn refresh token MOBILE (schema: 30 ngày)
 * @param refreshCookie       cookie refresh token cho WEB
 */
@Validated
@ConfigurationProperties(prefix = "app.auth.token")
public record AuthTokenProperties(
        @DefaultValue("opspilot-api") @NotBlank String issuer,
        String jwtSecret,
        @DefaultValue("15m") @NotNull Duration accessTokenTtl,
        @DefaultValue("7d") @NotNull Duration refreshTokenTtlWeb,
        @DefaultValue("30d") @NotNull Duration refreshTokenTtlMobile,
        @DefaultValue @Valid RefreshCookie refreshCookie
) {
    static final int MIN_SECRET_BYTES = 32;

    public AuthTokenProperties {
        byte[] keyBytes = decodeJwtSecret(jwtSecret);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "JWT_SECRET (app.auth.token.jwt-secret) phải decode thành ít nhất 32 byte; không có giá trị mặc định");
        }
        requirePositive(accessTokenTtl, "access-token-ttl");
        requirePositive(refreshTokenTtlWeb, "refresh-token-ttl-web");
        requirePositive(refreshTokenTtlMobile, "refresh-token-ttl-mobile");
    }

    byte[] jwtKeyBytes() {
        return decodeJwtSecret(jwtSecret);
    }

    private static byte[] decodeJwtSecret(String encodedSecret) {
        if (encodedSecret == null || encodedSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "JWT_SECRET (app.auth.token.jwt-secret) bắt buộc và phải là Base64 hợp lệ");
        }
        try {
            return Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException(
                    "JWT_SECRET (app.auth.token.jwt-secret) bắt buộc và phải là Base64 hợp lệ", invalidBase64);
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException("app.auth.token." + name + " phải lớn hơn 0");
        }
    }

    /** Không in secret ra log. */
    @Override
    public String toString() {
        return "AuthTokenProperties[issuer=" + issuer + ", jwtSecret=***, accessTokenTtl=" + accessTokenTtl
                + ", refreshTokenTtlWeb=" + refreshTokenTtlWeb + ", refreshTokenTtlMobile=" + refreshTokenTtlMobile
                + ", refreshCookie=" + refreshCookie + "]";
    }

    /**
     * @param name   tên cookie
     * @param secure TRUE ở mọi môi trường thật; chỉ tắt khi dev qua HTTP không phải localhost
     */
    public record RefreshCookie(
            @DefaultValue("opspilot_rt") @NotBlank String name,
            @DefaultValue("true") boolean secure
    ) {
    }
}
