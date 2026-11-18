package dev.opspilot.repositories.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * Truy cập dữ liệu phục vụ đăng nhập trên {@code app_user} bằng SQL tường minh.
 *
 * <p>Các phương thức đọc/ghi {@code app_user} yêu cầu tenant đã được gắn vào giao dịch
 * ({@link dev.opspilot.security.tenant.TenantDatabaseContext}); ngoài RLS, mọi truy vấn vẫn lọc
 * {@code organization_id} tường minh (phòng thủ nhiều lớp).
 *
 * <p>Bộ đếm sai mật khẩu được cập nhật bằng một câu UPDATE nguyên tử: biểu thức trong SET đọc phiên bản
 * dòng mới nhất sau khi PostgreSQL khoá dòng, nên các lần sai đồng thời không làm mất lượt đếm.
 * Không tăng {@code version}/{@code updated_at}: đây là trạng thái bảo mật, không phải chỉnh sửa hồ sơ.
 */
@Repository
public class LoginAccountRepository {

    private static final int SMALLINT_MAX = Short.MAX_VALUE;

    private final JdbcClient jdbcClient;

    public LoginAccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Tra cứu doanh nghiệp ACTIVE theo mã qua hàm SECURITY DEFINER đã duyệt; không cần tenant context. */
    public Optional<Long> findActiveOrganizationId(String orgCode) {
        return jdbcClient.sql("SELECT find_organization_id(:code)")
                .param("code", orgCode)
                .query(Long.class)
                .optional();
    }

    /**
     * Tìm tài khoản chưa xoá theo username/email (không phân biệt hoa thường) hoặc phone (khớp chính xác).
     * Nếu một định danh khớp nhiều tài khoản ở các cột khác nhau, ưu tiên username > email > phone.
     */
    public Optional<LoginAccount> findByIdentifier(long organizationId, String identifier) {
        return jdbcClient.sql("""
                        SELECT id, password_hash, status, must_change_password,
                               (locked_until IS NOT NULL AND locked_until > now()) AS temporarily_locked
                          FROM app_user
                         WHERE organization_id = :orgId
                           AND deleted_at IS NULL
                           AND (lower(username) = lower(:identifier)
                                OR lower(email) = lower(:identifier)
                                OR phone = :identifier)
                         ORDER BY CASE WHEN lower(username) = lower(:identifier) THEN 0
                                       WHEN lower(email) = lower(:identifier) THEN 1
                                       ELSE 2 END
                         LIMIT 1
                        """)
                .param("orgId", organizationId)
                .param("identifier", identifier)
                .query((rs, rowNum) -> new LoginAccount(
                        rs.getLong("id"),
                        rs.getString("password_hash"),
                        rs.getString("status"),
                        rs.getBoolean("must_change_password"),
                        rs.getBoolean("temporarily_locked")))
                .optional();
    }

    /**
     * Ghi nhận một lần sai mật khẩu. Nếu lần khoá tạm trước đã hết hạn, bộ đếm bắt đầu lại từ 1.
     * Chạm ngưỡng thì đặt {@code locked_until = now() + lockDuration}.
     */
    public Optional<FailedAttemptState> recordFailedAttempt(long organizationId, long userId,
                                                            int maxFailedAttempts, Duration lockDuration) {
        return jdbcClient.sql("""
                        UPDATE app_user
                           SET failed_login_count =
                                   CASE WHEN locked_until IS NOT NULL AND locked_until <= now() THEN 1
                                        ELSE LEAST(failed_login_count + 1, :smallintMax) END,
                               locked_until =
                                   CASE WHEN (CASE WHEN locked_until IS NOT NULL AND locked_until <= now() THEN 1
                                                   ELSE failed_login_count + 1 END) >= :maxAttempts
                                            THEN now() + (:lockSeconds * INTERVAL '1 second')
                                        WHEN locked_until <= now() THEN NULL
                                        ELSE locked_until END
                         WHERE organization_id = :orgId AND id = :userId AND deleted_at IS NULL
                        RETURNING failed_login_count,
                                  (locked_until IS NOT NULL AND locked_until > now()) AS locked
                        """)
                .param("smallintMax", SMALLINT_MAX)
                .param("maxAttempts", maxFailedAttempts)
                .param("lockSeconds", lockDuration.toSeconds())
                .param("orgId", organizationId)
                .param("userId", userId)
                .query((rs, rowNum) -> new FailedAttemptState(rs.getInt("failed_login_count"), rs.getBoolean("locked")))
                .optional();
    }

    /**
     * Ghi nhận đăng nhập thành công: reset bộ đếm, xoá khoá tạm, cập nhật {@code last_login_at}.
     * Có điều kiện: chỉ áp dụng khi tài khoản vẫn ACTIVE, không bị khoá tạm và mật khẩu chưa đổi kể từ lúc
     * kiểm tra — tránh race với một lần khoá/đổi mật khẩu xảy ra đồng thời.
     */
    public Optional<SuccessfulLogin> recordSuccessfulLogin(long organizationId, long userId, String verifiedPasswordHash) {
        return jdbcClient.sql("""
                        UPDATE app_user
                           SET failed_login_count = 0,
                               locked_until = NULL,
                               last_login_at = now()
                         WHERE organization_id = :orgId AND id = :userId
                           AND deleted_at IS NULL
                           AND status = 'ACTIVE'
                           AND (locked_until IS NULL OR locked_until <= now())
                           AND password_hash = :passwordHash
                        RETURNING id, display_name, token_version, must_change_password
                        """)
                .param("orgId", organizationId)
                .param("userId", userId)
                .param("passwordHash", verifiedPasswordHash)
                .query((rs, rowNum) -> new SuccessfulLogin(
                        rs.getLong("id"),
                        rs.getString("display_name"),
                        rs.getInt("token_version"),
                        rs.getBoolean("must_change_password")))
                .optional();
    }

    public record LoginAccount(long id, String passwordHash, String status, boolean mustChangePassword,
                               boolean temporarilyLocked) {
        @Override
        public String toString() {
            return "LoginAccount[id=" + id + ", status=" + status + ", temporarilyLocked=" + temporarilyLocked + "]";
        }
    }

    public record FailedAttemptState(int failedLoginCount, boolean locked) {
    }

    public record SuccessfulLogin(long userId, String displayName, int tokenVersion, boolean mustChangePassword) {
    }
}
