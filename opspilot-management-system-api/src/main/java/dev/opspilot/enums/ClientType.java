package dev.opspilot.enums;

/**
 * Loại client gửi yêu cầu đăng nhập. Khớp ràng buộc {@code ck_refresh_token_client}
 * của bảng {@code refresh_token} (WEB, MOBILE) và quyết định thời hạn refresh token (KAN-77).
 */
public enum ClientType {
    WEB,
    MOBILE
}
