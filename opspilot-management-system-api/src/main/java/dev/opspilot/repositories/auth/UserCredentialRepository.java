package dev.opspilot.repositories.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Trạng thái phiên và thông tin xác thực của {@code app_user} cho JWT (KAN-76), refresh (KAN-77) và mật khẩu (KAN-78).
 * Yêu cầu tenant đã được gắn vào giao dịch; mọi câu lệnh vẫn lọc {@code organization_id} tường minh.
 * Dùng SQL có điều kiện thay vì JPA để cập nhật nguyên tử và không ghi đè bộ đếm đăng nhập.
 */
@Repository
public class UserCredentialRepository {

    private final JdbcClient jdbcClient;

    public UserCredentialRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<SessionUser> findSessionUser(long organizationId, long userId) {
        return jdbcClient.sql("""
                        SELECT id, username, display_name, status, token_version, must_change_password, password_hash,
                               (locked_until IS NOT NULL AND locked_until > now()) AS temporarily_locked
                          FROM app_user
                         WHERE organization_id = :orgId AND id = :userId AND deleted_at IS NULL
                        """)
                .param("orgId", organizationId)
                .param("userId", userId)
                .query((rs, rowNum) -> new SessionUser(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getInt("token_version"),
                        rs.getBoolean("must_change_password"),
                        rs.getString("password_hash"),
                        rs.getBoolean("temporarily_locked")))
                .optional();
    }

    /** Tăng token_version: mọi access token đang lưu hành của user bị từ chối ngay ở request kế tiếp. */
    public int incrementTokenVersion(long organizationId, long userId) {
        return jdbcClient.sql("""
                        UPDATE app_user SET token_version = token_version + 1
                         WHERE organization_id = :orgId AND id = :userId AND deleted_at IS NULL
                        """)
                .param("orgId", organizationId)
                .param("userId", userId)
                .update();
    }

    /**
     * Người dùng tự đổi mật khẩu. Có điều kiện {@code password_hash = :expectedHash} để không ghi đè một thay đổi
     * đồng thời. Xoá must_change_password, tăng token_version, xoá khoá tạm.
     */
    public boolean changeOwnPassword(long organizationId, long userId, String expectedHash, String newHash) {
        return jdbcClient.sql("""
                        UPDATE app_user
                           SET password_hash = :newHash,
                               must_change_password = FALSE,
                               password_changed_at = now(),
                               token_version = token_version + 1,
                               failed_login_count = 0,
                               locked_until = NULL,
                               updated_at = now(),
                               updated_by = :userId,
                               version = version + 1
                         WHERE organization_id = :orgId AND id = :userId AND deleted_at IS NULL
                           AND status = 'ACTIVE' AND password_hash = :expectedHash
                        """)
                .param("newHash", newHash)
                .param("orgId", organizationId)
                .param("userId", userId)
                .param("expectedHash", expectedHash)
                .update() == 1;
    }

    /** Quản trị viên reset mật khẩu: bắt đổi ở lần đăng nhập sau, tăng token_version, xoá khoá tạm. */
    public boolean adminResetPassword(long organizationId, long targetUserId, String newHash, long actorUserId) {
        return jdbcClient.sql("""
                        UPDATE app_user
                           SET password_hash = :newHash,
                               must_change_password = TRUE,
                               password_changed_at = now(),
                               token_version = token_version + 1,
                               failed_login_count = 0,
                               locked_until = NULL,
                               updated_at = now(),
                               updated_by = :actorId,
                               version = version + 1
                         WHERE organization_id = :orgId AND id = :targetId AND deleted_at IS NULL
                        """)
                .param("newHash", newHash)
                .param("actorId", actorUserId)
                .param("orgId", organizationId)
                .param("targetId", targetUserId)
                .update() == 1;
    }

    public record SessionUser(long id, String username, String displayName, String status, int tokenVersion,
                              boolean mustChangePassword, String passwordHash, boolean temporarilyLocked) {
        public boolean isActive() {
            return "ACTIVE".equals(status);
        }

        @Override
        public String toString() {
            return "SessionUser[id=" + id + ", status=" + status + ", tokenVersion=" + tokenVersion + "]";
        }
    }
}
