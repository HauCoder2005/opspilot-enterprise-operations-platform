package dev.opspilot.auth;

import dev.opspilot.support.PostgresTestDatabase;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Seed và đọc dữ liệu kiểm thử bằng kết nối superuser (bỏ qua RLS), mô phỏng công cụ onboarding KAN-95. */
final class AuthTestData {

    static final PasswordEncoder ONBOARDING_ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private AuthTestData() {
    }

    static String uniqueOrgCode() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    static long createOrganization(String code, String status) throws SQLException {
        try (Connection c = PostgresTestDatabase.superuserConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO organization (code, name, status) VALUES (?, ?, ?) RETURNING id")) {
            ps.setString(1, code);
            ps.setString(2, "Org " + code);
            ps.setString(3, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static UserBuilder user(long orgId, String username, String rawPassword) {
        return new UserBuilder(orgId, username, rawPassword);
    }

    static UserState readUser(long userId) throws SQLException {
        try (Connection c = PostgresTestDatabase.superuserConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT failed_login_count, locked_until, last_login_at,
                            (locked_until IS NOT NULL AND locked_until > now()) AS locked_now
                       FROM app_user WHERE id = ?""")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new UserState(
                        rs.getInt("failed_login_count"),
                        rs.getObject("locked_until", OffsetDateTime.class),
                        rs.getObject("last_login_at", OffsetDateTime.class),
                        rs.getBoolean("locked_now"));
            }
        }
    }

    static void execute(String sql, Object... params) throws SQLException {
        try (Connection c = PostgresTestDatabase.superuserConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    static int countAudit(long orgId, String actorType, Long userId, String result, String reason) throws SQLException {
        try (Connection c = PostgresTestDatabase.superuserConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*) FROM audit_log
                      WHERE organization_id = ? AND action = 'auth.login' AND actor_type = ?
                        AND actor_user_id IS NOT DISTINCT FROM ? AND result = ?
                        AND reason IS NOT DISTINCT FROM ?""")) {
            ps.setLong(1, orgId);
            ps.setString(2, actorType);
            ps.setObject(3, userId);
            ps.setString(4, result);
            ps.setString(5, reason);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    static int countRefreshTokens(long userId, String whereSql) throws SQLException {
        try (Connection c = PostgresTestDatabase.superuserConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM refresh_token WHERE user_id = ? AND " + whereSql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    static int tokenVersion(long userId) throws SQLException {
        return queryInt("SELECT token_version FROM app_user WHERE id = ?", userId);
    }

    static boolean mustChangePassword(long userId) throws SQLException {
        return queryInt("SELECT CASE WHEN must_change_password THEN 1 ELSE 0 END FROM app_user WHERE id = ?", userId) == 1;
    }

    static int queryInt(String sql, Object... params) throws SQLException {
        try (Connection c = PostgresTestDatabase.superuserConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    static int countAuditAction(long orgId, String action, String result, String reason) throws SQLException {
        return queryInt("SELECT count(*) FROM audit_log WHERE organization_id = ? AND action = ? AND result = ?"
                + " AND reason IS NOT DISTINCT FROM ?", orgId, action, result, reason);
    }

    record UserState(int failedLoginCount, OffsetDateTime lockedUntil, OffsetDateTime lastLoginAt, boolean lockedNow) {
    }

    static final class UserBuilder {
        private final long orgId;
        private final String username;
        private final String rawPassword;
        private String email;
        private String phone;
        private String status = "ACTIVE";
        private boolean mustChangePassword;
        private int failedLoginCount;
        private String lockedUntilSql = "NULL";
        private boolean deleted;

        private UserBuilder(long orgId, String username, String rawPassword) {
            this.orgId = orgId;
            this.username = username;
            this.rawPassword = rawPassword;
        }

        UserBuilder email(String value) { this.email = value; return this; }
        UserBuilder phone(String value) { this.phone = value; return this; }
        UserBuilder status(String value) { this.status = value; return this; }
        UserBuilder mustChangePassword(boolean value) { this.mustChangePassword = value; return this; }
        UserBuilder failedLoginCount(int value) { this.failedLoginCount = value; return this; }
        /** Biểu thức SQL cố định do test viết, ví dụ {@code now() + interval '10 minutes'}. */
        UserBuilder lockedUntilSql(String value) { this.lockedUntilSql = value; return this; }
        UserBuilder deleted() { this.deleted = true; return this; }

        long create() throws SQLException {
            String sql = "INSERT INTO app_user (organization_id, username, email, phone, password_hash, display_name,"
                    + " status, must_change_password, failed_login_count, locked_until, deleted_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, " + lockedUntilSql + ", " + (deleted ? "now()" : "NULL") + ")"
                    + " RETURNING id";
            try (Connection c = PostgresTestDatabase.superuserConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, orgId);
                ps.setString(2, username);
                ps.setString(3, email);
                ps.setString(4, phone);
                ps.setString(5, ONBOARDING_ENCODER.encode(rawPassword));
                ps.setString(6, "User " + username);
                ps.setString(7, status);
                ps.setBoolean(8, mustChangePassword);
                ps.setInt(9, failedLoginCount);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
    }
}
