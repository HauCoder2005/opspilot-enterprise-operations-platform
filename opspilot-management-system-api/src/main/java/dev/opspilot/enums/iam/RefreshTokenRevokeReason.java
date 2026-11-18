package dev.opspilot.enums.iam;

/** Khớp {@code ck_refresh_token_reason}. */
public enum RefreshTokenRevokeReason {
    LOGOUT,
    ROTATED,
    REUSE_DETECTED,
    ADMIN,
    PASSWORD_CHANGED
}
