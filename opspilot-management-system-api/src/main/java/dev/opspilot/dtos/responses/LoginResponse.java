package dev.opspilot.dtos.responses;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Kết quả đăng nhập thành công")
public record LoginResponse(
        @Schema(example = "Bearer") String tokenType,
        String accessToken,
        Instant accessTokenExpiresAt,
        @Schema(description = "Có thể vắng mặt nếu refresh token được giao qua cookie (OPEN DECISION KAN-77)")
        String refreshToken,
        Instant refreshTokenExpiresAt,
        @Schema(description = "TRUE: client phải chuyển người dùng sang màn hình đổi mật khẩu (KAN-78)")
        boolean mustChangePassword,
        UserSummary user
) {
    public record UserSummary(long id, String displayName) {
    }

    @Override
    public String toString() {
        return "LoginResponse[tokenType=" + tokenType + ", accessToken=***, refreshToken=***, mustChangePassword="
                + mustChangePassword + ", user=" + user + "]";
    }
}
