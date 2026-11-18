package dev.opspilot.repositories.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Bảng {@code refresh_token}: chỉ lưu SHA-256 (hex) của token. Yêu cầu tenant đã gắn vào giao dịch (RLS),
 * mọi truy vấn vẫn lọc {@code organization_id} tường minh.
 */
@Repository
public class RefreshTokenRepository {

    private static final int USER_AGENT_MAX_LENGTH = 500;

    private final JdbcClient jdbcClient;

    public RefreshTokenRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** @param familyId null cho token đầu tiên của một lần đăng nhập (trigger gán family_id = id) */
    public StoredToken insert(long organizationId, long userId, String tokenHash, Long familyId, Duration ttl,
                              String clientType, String userAgent, String ipAddress) {
        return jdbcClient.sql("""
                        INSERT INTO refresh_token (organization_id, user_id, token_hash, family_id, expires_at,
                                                   client_type, user_agent, ip_address)
                        VALUES (:orgId, :userId, :hash, :familyId, now() + (:ttlSeconds * INTERVAL '1 second'),
                                :clientType, :userAgent, CAST(:ip AS inet))
                        RETURNING id, family_id, expires_at
                        """)
                .param("orgId", organizationId)
                .param("userId", userId)
                .param("hash", tokenHash)
                .param("familyId", familyId)
                .param("ttlSeconds", ttl.toSeconds())
                .param("clientType", clientType)
                .param("userAgent", truncate(userAgent))
                .param("ip", ipAddress)
                .query((rs, rowNum) -> new StoredToken(
                        rs.getLong("id"),
                        rs.getLong("family_id"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    /** Khoá dòng token (FOR UPDATE) để các lần refresh đồng thời với cùng token được tuần tự hoá. */
    public Optional<TokenRow> findForUpdate(long organizationId, String tokenHash) {
        return jdbcClient.sql("""
                        SELECT id, user_id, family_id, client_type, revoked_at IS NOT NULL AS revoked, revoke_reason,
                               expires_at <= now() AS expired
                          FROM refresh_token
                         WHERE organization_id = :orgId AND token_hash = :hash
                           FOR UPDATE
                        """)
                .param("orgId", organizationId)
                .param("hash", tokenHash)
                .query((rs, rowNum) -> new TokenRow(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getLong("family_id"),
                        rs.getString("client_type"),
                        rs.getBoolean("revoked"),
                        rs.getString("revoke_reason"),
                        rs.getBoolean("expired")))
                .optional();
    }

    public void markRotated(long organizationId, long tokenId, long replacedById) {
        jdbcClient.sql("""
                        UPDATE refresh_token
                           SET revoked_at = now(), revoke_reason = 'ROTATED', replaced_by_id = :newId
                         WHERE organization_id = :orgId AND id = :id AND revoked_at IS NULL
                        """)
                .param("newId", replacedById)
                .param("orgId", organizationId)
                .param("id", tokenId)
                .update();
    }

    public int revokeFamily(long organizationId, long familyId, RevokeReason reason) {
        return jdbcClient.sql("""
                        UPDATE refresh_token SET revoked_at = now(), revoke_reason = :reason
                         WHERE organization_id = :orgId AND family_id = :familyId AND revoked_at IS NULL
                        """)
                .param("reason", reason.name())
                .param("orgId", organizationId)
                .param("familyId", familyId)
                .update();
    }

    public int revokeAllForUser(long organizationId, long userId, RevokeReason reason) {
        return jdbcClient.sql("""
                        UPDATE refresh_token SET revoked_at = now(), revoke_reason = :reason
                         WHERE organization_id = :orgId AND user_id = :userId AND revoked_at IS NULL
                        """)
                .param("reason", reason.name())
                .param("orgId", organizationId)
                .param("userId", userId)
                .update();
    }

    private static String truncate(String value) {
        return value == null || value.length() <= USER_AGENT_MAX_LENGTH ? value : value.substring(0, USER_AGENT_MAX_LENGTH);
    }

    /** Khớp ràng buộc {@code ck_refresh_token_reason}. */
    public enum RevokeReason { LOGOUT, ROTATED, REUSE_DETECTED, ADMIN, PASSWORD_CHANGED }

    public record StoredToken(long id, long familyId, Instant expiresAt) {
    }

    public record TokenRow(long id, long userId, long familyId, String clientType, boolean revoked,
                           String revokeReason, boolean expired) {
    }
}
