package dev.opspilot.dtos.requests;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Body cho refresh/logout của client MOBILE. Client WEB gửi refresh token qua cookie HttpOnly. */
@Schema(description = "Refresh token (chỉ MOBILE; WEB dùng cookie)")
public record RefreshTokenRequest(@Size(max = 128) String refreshToken) {
    @Override
    public String toString() {
        return "RefreshTokenRequest[refreshToken=***]";
    }
}
