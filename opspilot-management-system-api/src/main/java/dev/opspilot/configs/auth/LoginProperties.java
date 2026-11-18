package dev.opspilot.configs.auth;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Chính sách chống dò mật khẩu khi đăng nhập (KAN-75).
 *
 * @param maxFailedAttempts số lần sai liên tiếp trước khi khoá tạm
 * @param lockDuration      thời gian khoá tạm sau khi chạm ngưỡng
 */
@Validated
@ConfigurationProperties(prefix = "app.auth.login")
public record LoginProperties(
        @DefaultValue("5") @Min(1) @Max(100) int maxFailedAttempts,
        @DefaultValue("15m") @NotNull Duration lockDuration
) {
    public LoginProperties {
        if (lockDuration != null && (lockDuration.isNegative() || lockDuration.isZero())) {
            throw new IllegalArgumentException("app.auth.login.lock-duration phải lớn hơn 0");
        }
    }
}
