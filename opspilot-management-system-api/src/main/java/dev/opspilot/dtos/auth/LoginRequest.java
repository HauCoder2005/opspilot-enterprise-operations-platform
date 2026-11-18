package dev.opspilot.dtos.auth;

import dev.opspilot.enums.ClientType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Thông tin đăng nhập: mã doanh nghiệp + username/email/số điện thoại + mật khẩu")
public record LoginRequest(
        @Schema(description = "Mã doanh nghiệp", example = "ACME")
        @NotBlank @Size(max = 30) String orgCode,
        @Schema(description = "Username, email hoặc số điện thoại", example = "owner")
        @NotBlank @Size(max = 255) String identifier,
        @Schema(description = "Mật khẩu", format = "password")
        @NotBlank @Size(max = 128) String password,
        @Schema(description = "Loại client", example = "WEB")
        @NotNull ClientType clientType
) {
    /** Không bao giờ in mật khẩu ra log, kể cả khi record bị log nhầm. */
    @Override
    public String toString() {
        return "LoginRequest[orgCode=" + orgCode + ", identifier=***, password=***, clientType=" + clientType + "]";
    }
}
