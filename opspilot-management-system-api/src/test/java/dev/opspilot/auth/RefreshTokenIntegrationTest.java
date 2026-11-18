package dev.opspilot.auth;

import dev.opspilot.support.ApiClient;
import dev.opspilot.support.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static dev.opspilot.auth.AuthTestData.countAuditAction;
import static dev.opspilot.auth.AuthTestData.countRefreshTokens;
import static dev.opspilot.auth.AuthTestData.createOrganization;
import static dev.opspilot.auth.AuthTestData.execute;
import static dev.opspilot.auth.AuthTestData.tokenVersion;
import static dev.opspilot.auth.AuthTestData.uniqueOrgCode;
import static dev.opspilot.auth.AuthTestData.user;
import static dev.opspilot.auth.LoginIntegrationTest.concurrently;
import static org.assertj.core.api.Assertions.assertThat;

/** KAN-77 — refresh token xoay vòng, phát hiện dùng lại, logout, logout mọi thiết bị, CSRF cho cookie WEB. */
class RefreshTokenIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";

    @Test
    void mobileRefreshRotatesTokenWithinSameFamily() throws SQLException {
        long userId = user(orgId, "rt.rotate", PASSWORD).create();
        Response login = api.login(orgCode, "rt.rotate", PASSWORD, "MOBILE");
        String first = login.text("refreshToken");

        Response refreshed = refreshMobile(first);

        assertThat(refreshed.status()).isEqualTo(200);
        String second = refreshed.text("refreshToken");
        assertThat(second).isNotEqualTo(first).startsWith(orgId + ".");
        assertThat(decode(refreshed).getSubject()).isEqualTo(Long.toString(userId));
        assertThat(api.get("/api/v1/auth/me", "Authorization", bearer(refreshed)).status()).isEqualTo(200);
        assertThat(countRefreshTokens(userId, "revoke_reason = 'ROTATED' AND replaced_by_id IS NOT NULL")).isEqualTo(1);
        assertThat(countRefreshTokens(userId, "revoked_at IS NULL")).isEqualTo(1);
        assertThat(AuthTestData.queryInt("SELECT count(DISTINCT family_id) FROM refresh_token WHERE user_id = ?", userId))
                .isEqualTo(1);
        assertThat(countAuditAction(orgId, "auth.token.refresh", "SUCCESS", null)).isEqualTo(1);
    }

    @Test
    void reusingRotatedTokenRevokesWholeFamilyAndCommitsDespite401() throws SQLException {
        long userId = user(orgId, "rt.reuse", PASSWORD).create();
        String first = api.login(orgCode, "rt.reuse", PASSWORD, "MOBILE").text("refreshToken");
        String second = refreshMobile(first).text("refreshToken");

        Response reuse = refreshMobile(first);

        assertThat(reuse.status()).isEqualTo(401);
        assertThat(reuse.code()).isEqualTo("INVALID_REFRESH_TOKEN");
        // Đọc bằng kết nối khác sau khi API trả 401: việc thu hồi cả family đã được COMMIT.
        assertThat(countRefreshTokens(userId, "revoked_at IS NULL")).isZero();
        assertThat(countRefreshTokens(userId, "revoke_reason = 'REUSE_DETECTED'")).isEqualTo(1);
        assertThat(countAuditAction(orgId, "auth.token.refresh", "FAILURE", "REFRESH_TOKEN_REUSE")).isEqualTo(1);
        assertThat(refreshMobile(second).status()).as("token mới nhất của family cũng bị vô hiệu").isEqualTo(401);
    }

    @Test
    void concurrentRefreshWithSameTokenIsSerializedAndTreatedAsReuse() throws Exception {
        long userId = user(orgId, "rt.race", PASSWORD).create();
        String token = api.login(orgCode, "rt.race", PASSWORD, "MOBILE").text("refreshToken");

        List<Response> responses = concurrently(2, () -> refreshMobile(token));

        assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(200, 401);
        assertThat(countRefreshTokens(userId, "revoked_at IS NULL")).as("family bị thu hồi toàn bộ").isZero();
    }

    @Test
    void tamperedTenantPrefixUnknownAndExpiredTokensAreRejected() throws SQLException {
        long userId = user(orgId, "rt.bad", PASSWORD).create();
        String token = api.login(orgCode, "rt.bad", PASSWORD, "MOBILE").text("refreshToken");
        long otherOrg = createOrganization(uniqueOrgCode(), "ACTIVE");
        String foreign = otherOrg + token.substring(token.indexOf('.'));

        assertThat(refreshMobile(foreign).status()).isEqualTo(401);
        assertThat(refreshMobile("garbage").status()).isEqualTo(401);
        assertThat(api.post(REFRESH, "{}").status()).isEqualTo(401);

        execute("UPDATE refresh_token SET issued_at = now() - interval '10 days', expires_at = now() - interval '1 day'"
                + " WHERE user_id = ?", userId);
        Response expired = refreshMobile(token);
        assertThat(expired.status()).isEqualTo(401);
        assertThat(expired.code()).isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void refreshForDisabledUserIsRejectedAndFamilyRevoked() throws SQLException {
        long userId = user(orgId, "rt.disabled", PASSWORD).create();
        String token = api.login(orgCode, "rt.disabled", PASSWORD, "MOBILE").text("refreshToken");
        execute("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", userId);

        assertThat(refreshMobile(token).status()).isEqualTo(401);
        assertThat(countRefreshTokens(userId, "revoked_at IS NULL")).isZero();
    }

    @Test
    void webRefreshUsesCookieAndRequiresCsrfToken() throws SQLException {
        user(orgId, "rt.web", PASSWORD).create();
        Response login = api.login(orgCode, "rt.web", PASSWORD, "WEB");
        String refreshCookie = login.cookie(COOKIE);
        assertThat(refreshCookie).isNotBlank();

        Response withoutCsrf = api.post(REFRESH, null, "Cookie", COOKIE + "=" + refreshCookie);
        assertThat(withoutCsrf.status()).isEqualTo(403);
        assertThat(withoutCsrf.code()).isEqualTo("CSRF_TOKEN_INVALID");

        Response csrf = api.get("/api/v1/auth/csrf");
        String xsrfCookie = csrf.cookie("XSRF-TOKEN");
        assertThat(csrf.text("token")).isEqualTo(xsrfCookie);
        assertThat(csrf.text("headerName")).isEqualTo("X-XSRF-TOKEN");

        Response wrongCsrf = api.post(REFRESH, null,
                "Cookie", COOKIE + "=" + refreshCookie + "; XSRF-TOKEN=" + xsrfCookie, "X-XSRF-TOKEN", "forged");
        assertThat(wrongCsrf.status()).isEqualTo(403);

        Response ok = api.post(REFRESH, null,
                "Cookie", COOKIE + "=" + refreshCookie + "; XSRF-TOKEN=" + xsrfCookie, "X-XSRF-TOKEN", xsrfCookie);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.text("refreshToken")).isNull();
        assertThat(ok.cookie(COOKIE)).isNotBlank().isNotEqualTo(refreshCookie);
    }

    @Test
    void mobileLogoutRevokesFamilyAndIsIdempotent() throws SQLException {
        long userId = user(orgId, "rt.logout", PASSWORD).create();
        String token = api.login(orgCode, "rt.logout", PASSWORD, "MOBILE").text("refreshToken");

        Response logout = api.post(LOGOUT, ApiClient.json(Map.of("refreshToken", token)));

        assertThat(logout.status()).isEqualTo(204);
        assertThat(countRefreshTokens(userId, "revoke_reason = 'LOGOUT'")).isEqualTo(1);
        assertThat(refreshMobile(token).status()).isEqualTo(401);
        assertThat(api.post(LOGOUT, ApiClient.json(Map.of("refreshToken", token))).status()).isEqualTo(204);
        assertThat(api.post(LOGOUT, "{}").status()).isEqualTo(204);
        assertThat(countAuditAction(orgId, "auth.logout", "SUCCESS", null)).isEqualTo(1);
    }

    @Test
    void webLogoutRequiresCsrfAndClearsCookie() throws SQLException {
        long userId = user(orgId, "rt.weblogout", PASSWORD).create();
        String refreshCookie = api.login(orgCode, "rt.weblogout", PASSWORD, "WEB").cookie(COOKIE);
        String xsrf = api.get("/api/v1/auth/csrf").cookie("XSRF-TOKEN");

        assertThat(api.post(LOGOUT, null, "Cookie", COOKIE + "=" + refreshCookie).status()).isEqualTo(403);
        Response logout = api.post(LOGOUT, null,
                "Cookie", COOKIE + "=" + refreshCookie + "; XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf);

        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.setCookieHeader(COOKIE)).hasValueSatisfying(c -> assertThat(c).contains("Max-Age=0"));
        assertThat(countRefreshTokens(userId, "revoked_at IS NULL")).isZero();
    }

    @Test
    void logoutAllRevokesEveryDeviceAndInvalidatesAccessTokens() throws SQLException {
        long userId = user(orgId, "rt.all", PASSWORD).create();
        Response phone = api.login(orgCode, "rt.all", PASSWORD, "MOBILE");
        Response laptop = api.login(orgCode, "rt.all", PASSWORD, "WEB");
        int versionBefore = tokenVersion(userId);

        Response response = api.post("/api/v1/auth/logout-all", null, "Authorization", bearer(phone));

        assertThat(response.status()).isEqualTo(204);
        assertThat(countRefreshTokens(userId, "revoked_at IS NULL")).isZero();
        assertThat(tokenVersion(userId)).isEqualTo(versionBefore + 1);
        assertThat(api.get("/api/v1/auth/me", "Authorization", bearer(phone)).status()).isEqualTo(401);
        assertThat(api.get("/api/v1/auth/me", "Authorization", bearer(laptop)).status()).isEqualTo(401);
        assertThat(refreshMobile(phone.text("refreshToken")).status()).isEqualTo(401);
        assertThat(countAuditAction(orgId, "auth.logout_all", "SUCCESS", null)).isEqualTo(1);
        assertThat(api.post("/api/v1/auth/logout-all", null).status()).as("cần access token").isEqualTo(401);
    }

    @Test
    void refreshIssuesAccessTokenWithCurrentTokenVersion() throws SQLException {
        long userId = user(orgId, "rt.tv", PASSWORD).create();
        String token = api.login(orgCode, "rt.tv", PASSWORD, "MOBILE").text("refreshToken");
        execute("UPDATE app_user SET token_version = 7 WHERE id = ?", userId);

        Response refreshed = refreshMobile(token);

        assertThat(claimLong(decode(refreshed), "tv")).isEqualTo(7);
        assertThat(api.get("/api/v1/auth/me", "Authorization", bearer(refreshed)).status()).isEqualTo(200);
    }

    private Response refreshMobile(String token) {
        return api.post(REFRESH, ApiClient.json(Map.of("refreshToken", token)));
    }
}
