package dev.opspilot.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Kết quả reset mật khẩu. Mật khẩu tạm chỉ hiển thị một lần; người dùng phải đổi ở lần đăng nhập sau.")
public record PasswordResetResponse(long userId, String temporaryPassword, boolean mustChangePassword) {
    @Override
    public String toString() {
        return "PasswordResetResponse[userId=" + userId + ", temporaryPassword=***]";
    }
}
