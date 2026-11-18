package dev.opspilot.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Đổi mật khẩu của chính người dùng")
public record ChangePasswordRequest(
        @Schema(format = "password") @NotBlank @Size(max = 128) String currentPassword,
        @Schema(format = "password", description = "8–128 ký tự, tối đa 72 byte UTF-8, khác mật khẩu hiện tại")
        @NotBlank @Size(max = 128) String newPassword
) {
    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=***, newPassword=***]";
    }
}
