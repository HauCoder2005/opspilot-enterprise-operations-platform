package dev.opspilot.services.auth;

import dev.opspilot.enums.ClientType;

import java.time.Instant;

/**
 * Cổng phát hành token sau khi xác thực thành công (JWT access token — KAN-76, refresh token xoay vòng — KAN-77).
 * Implementation chính thức: {@link AuthTokenIssuer}.
 *
 * <p>Hợp đồng: {@link #issue} được gọi bên trong giao dịch ghi nhận đăng nhập thành công, sau khi tenant đã
 * được gắn ({@code opspilot.org_id}). Implementation có thể INSERT {@code refresh_token} trong cùng giao dịch;
 * nếu ném lỗi, toàn bộ ghi nhận thành công (reset bộ đếm, audit SUCCESS) bị rollback.
 * Implementation không được log raw token.
 */
public interface IAuthTokenIssuer {

    IssuedTokens issue(TokenSubject subject, ClientType clientType, ClientInfo clientInfo);

    /** Danh tính đã xác thực, dùng để tạo claim; không chứa danh sách permission. */
    record TokenSubject(long userId, long organizationId, int tokenVersion, boolean mustChangePassword) {
    }

    record IssuedTokens(String accessToken, Instant accessTokenExpiresAt,
                        String refreshToken, Instant refreshTokenExpiresAt) {
        @Override
        public String toString() {
            return "IssuedTokens[accessToken=***, accessTokenExpiresAt=" + accessTokenExpiresAt
                    + ", refreshToken=***, refreshTokenExpiresAt=" + refreshTokenExpiresAt + "]";
        }
    }
}
