package dev.opspilot.dtos.auth;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "CSRF token cho refresh/logout bằng cookie (WEB). Gửi lại trong header headerName.")
public record CsrfTokenResponse(String headerName, String token) {
}
