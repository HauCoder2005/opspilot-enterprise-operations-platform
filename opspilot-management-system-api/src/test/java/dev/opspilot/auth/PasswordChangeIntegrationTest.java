package dev.opspilot.auth;

import dev.opspilot.support.ApiClient;
import dev.opspilot.support.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

import static dev.opspilot.auth.AuthTestData.countAuditAction;
import static dev.opspilot.auth.AuthTestData.countRefreshTokens;
import static dev.opspilot.auth.AuthTestData.mustChangePassword;
import static dev.opspilot.auth.AuthTestData.readUser;
import static dev.opspilot.auth.AuthTestData.tokenVersion;
import static dev.opspilot.auth.AuthTestData.user;
import static org.assertj.core.api.Assertions.assertThat;

/** KAN-78 — người dùng tự đổi mật khẩu; admin reset luôn đi qua policy service production. */
class PasswordChangeIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String CHANGE = "/api/v1/auth/change-password";
    private static final String NEW_PASSWORD = "Brand-New-Pass-42";

    @Test
    void changePasswordRotatesCredentialsAndRevokesAllSessions() throws SQLException {
        long userId = user(orgId, "pw.ok", PASSWORD).mustChangePassword(true).create();
        Response login = api.login(orgCode, "pw.ok", PASSWORD, "MOBILE");
        Response otherDevice = api.login(orgCode, "pw.ok", PASSWORD, "WEB");
        int versionBefore = tokenVersion(userId);

        Response change = change(login, PASSWORD, NEW_PASSWORD);

        assertThat(change.status()).isEqualTo(204);
        assertThat(mustChangePassword(userId)).isFalse();
        assertThat(tokenVersion(userId)).isEqualTo(versionBefore + 1);
        assertThat(countRefreshTokens(userId, "revoked_at IS NULL")).isZero();
        assertThat(countRefreshTokens(userId, "revoke_reason = 'PASSWORD_CHANGED'")).isEqualTo(2);
        assertThat(api.get("/api/v1/auth/me", "Authorization", bearer(otherDevice)).status()).isEqualTo(401);
        assertThat(AuthTestData.queryInt("SELECT count(*) FROM app_user WHERE id = ? AND password_hash LIKE '{bcrypt}%'"
                + " AND password_changed_at IS NOT NULL", userId)).isEqualTo(1);
        assertThat(api.login(orgCode, "pw.ok", PASSWORD, "WEB").status()).isEqualTo(401);
        Response relogin = api.login(orgCode, "pw.ok", NEW_PASSWORD, "WEB");
        assertThat(relogin.status()).isEqualTo(200);
        assertThat(relogin.body().get("mustChangePassword").asBoolean()).isFalse();
        assertThat(countAuditAction(orgId, "auth.password.change", "SUCCESS", null)).isEqualTo(1);
        assertThat(change.rawBody()).doesNotContain(NEW_PASSWORD);
    }

    @Test
    void wrongCurrentPasswordIsRejectedAndCountedTowardsLockout() throws SQLException {
        long userId = user(orgId, "pw.wrong", PASSWORD).create();
        Response login = api.login(orgCode, "pw.wrong", PASSWORD, "MOBILE");

        Response response = change(login, WRONG, NEW_PASSWORD);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("CURRENT_PASSWORD_INCORRECT");
        assertThat(readUser(userId).failedLoginCount()).isEqualTo(1);
        assertThat(countAuditAction(orgId, "auth.password.change", "FAILURE", "CURRENT_PASSWORD_INCORRECT")).isEqualTo(1);
        assertThat(api.login(orgCode, "pw.wrong", PASSWORD, "WEB").status()).as("mật khẩu cũ vẫn dùng được").isEqualTo(200);
    }

    @Test
    void reusingCurrentPasswordOrViolatingPolicyIsRejected() throws SQLException {
        user(orgId, "pw.policy", PASSWORD).create();
        Response login = api.login(orgCode, "pw.policy", PASSWORD, "MOBILE");

        Response same = change(login, PASSWORD, PASSWORD);
        Response tooShort = change(login, PASSWORD, "short");
        Response tooManyBytes = change(login, PASSWORD, "Ừ".repeat(30));

        assertThat(same.code()).isEqualTo("PASSWORD_REUSE_NOT_ALLOWED");
        assertThat(tooShort.code()).isEqualTo("PASSWORD_POLICY_VIOLATION");
        assertThat(tooManyBytes.code()).isEqualTo("PASSWORD_POLICY_VIOLATION");
        assertThat(api.get("/api/v1/auth/me", "Authorization", bearer(login)).status()).isEqualTo(200);
    }

    @Test
    void changePasswordRequiresAuthentication() {
        Response response = api.post(CHANGE, ApiClient.json(Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD)));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void adminResetFailsClosedWhenActorHasNoScopedPermission() throws SQLException {
        user(orgId, "pw.admin", PASSWORD).create();
        long target = user(orgId, "pw.target", PASSWORD).create();
        Response admin = api.login(orgCode, "pw.admin", PASSWORD, "MOBILE");

        Response response = api.post("/api/v1/iam/users/" + target + "/reset-password", null, "Authorization", bearer(admin));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("ACCESS_DENIED");
        assertThat(countAuditAction(orgId, "iam.user.password.reset", "DENIED", "POLICY_DENIED")).isEqualTo(1);
        assertThat(api.login(orgCode, "pw.target", PASSWORD, "WEB").status()).as("mật khẩu đích không đổi").isEqualTo(200);
    }

    private Response change(Response login, String current, String next) {
        return api.post(CHANGE, ApiClient.json(Map.of("currentPassword", current, "newPassword", next)),
                "Authorization", bearer(login));
    }
}
