package dev.opspilot.auth;

import dev.opspilot.support.ApiClient.Response;
import dev.opspilot.support.PostgresTestDatabase;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.opspilot.auth.AuthTestData.countAudit;
import static dev.opspilot.auth.AuthTestData.countRefreshTokens;
import static dev.opspilot.auth.AuthTestData.createOrganization;
import static dev.opspilot.auth.AuthTestData.readUser;
import static dev.opspilot.auth.AuthTestData.uniqueOrgCode;
import static dev.opspilot.auth.AuthTestData.user;
import static org.assertj.core.api.Assertions.assertThat;

/** KAN-75 — Login API trên PostgreSQL 18 thật, ứng dụng kết nối bằng opspilot_app (RLS có hiệu lực). */
class LoginIntegrationTest extends AbstractAuthIntegrationTest {

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter springSecurityFilterChain;

    @Test
    void runtimeConnectionIsRlsConstrainedAppRole() throws SQLException {
        try (Connection c = PostgresTestDatabase.appConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT bool_and(ok) FROM rls_session_check()")) {
                rs.next();
                assertThat(rs.getBoolean(1)).as("rls_session_check() phải ok cho opspilot_app").isTrue();
            }
            user(orgId, "rls.probe", PASSWORD).create();
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM app_user")) {
                rs.next();
                assertThat(rs.getLong(1)).as("không đặt tenant thì không thấy app_user").isZero();
            }
        }
    }

    @Test
    void webLoginIssuesJwtAndRefreshCookieAndResetsCounter() throws SQLException {
        long userId = user(orgId, "alice", PASSWORD).failedLoginCount(3).create();

        Response response = api.login(orgCode.toLowerCase(), "ALICE", PASSWORD, "WEB");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.text("tokenType")).isEqualTo("Bearer");
        assertThat(response.text("refreshToken")).as("WEB: refresh token không nằm trong body").isNull();
        assertThat(response.body().get("mustChangePassword").asBoolean()).isFalse();
        assertThat(response.body().get("user").get("id").asLong()).isEqualTo(userId);
        assertThat(response.header("Cache-Control")).contains("no-store");
        assertThat(response.setCookieHeader(COOKIE)).hasValueSatisfying(cookie -> assertThat(cookie)
                .contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/api/v1/auth"));
        assertThat(response.setCookieHeader("JSESSIONID")).as("API stateless").isEmpty();

        Jwt jwt = decode(response);
        assertThat(jwt.getSubject()).isEqualTo(Long.toString(userId));
        assertThat(orgClaim(jwt)).isEqualTo(orgId);
        assertThat(claimLong(jwt, "tv")).isZero();
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getClaims()).containsKeys("iat", "exp").doesNotContainKeys("permissions", "roles", "scope");
        assertThat(jwt.getExpiresAt()).isBetween(jwt.getIssuedAt().plusSeconds(899), jwt.getIssuedAt().plusSeconds(901));

        AuthTestData.UserState state = readUser(userId);
        assertThat(state.failedLoginCount()).isZero();
        assertThat(state.lockedUntil()).isNull();
        assertThat(state.lastLoginAt()).isNotNull();
        assertThat(countRefreshTokens(userId, "client_type = 'WEB' AND revoked_at IS NULL")).isEqualTo(1);
        assertThat(countAudit(orgId, "USER", userId, "SUCCESS", null)).isEqualTo(1);
    }

    @Test
    void mobileLoginReturnsRefreshTokenInBodyAndLoginByEmailOrPhone() throws SQLException {
        long userId = user(orgId, "bob", PASSWORD).email("Bob@Example.com").phone("+84901234567").create();

        Response byEmail = api.login(orgCode, "bob@example.COM", PASSWORD, "MOBILE");
        Response byPhone = api.login(orgCode, "+84901234567", PASSWORD, "MOBILE");

        assertThat(byEmail.status()).isEqualTo(200);
        assertThat(byEmail.text("refreshToken")).startsWith(orgId + ".");
        assertThat(byEmail.setCookieHeader(COOKIE)).isEmpty();
        assertThat(decode(byEmail).getSubject()).isEqualTo(Long.toString(userId));
        assertThat(byPhone.status()).isEqualTo(200);
        assertThat(countRefreshTokens(userId, "client_type = 'MOBILE'")).isEqualTo(2);
        assertThat(AuthTestData.queryInt("SELECT count(*) FROM refresh_token WHERE user_id = ?"
                + " AND token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')", userId, byEmail.text("refreshToken")))
                .as("chỉ lưu SHA-256 của refresh token").isEqualTo(1);
    }

    @Test
    void wrongPasswordReturns401AndPersistsCounterAndAudit() throws SQLException {
        long userId = user(orgId, "carol", PASSWORD).create();

        Response response = api.login(orgCode, "carol", WRONG, "WEB");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(response.text("traceId")).isEqualTo(response.header("X-Request-Id")).isNotBlank();
        assertThat(response.rawBody()).doesNotContain(WRONG);
        assertThat(readUser(userId).failedLoginCount()).isEqualTo(1);
        assertThat(countAudit(orgId, "USER", userId, "FAILURE", "BAD_CREDENTIALS")).isEqualTo(1);
        assertThat(countRefreshTokens(userId, "TRUE")).isZero();
    }

    @Test
    void unknownUserUnknownOrgAndSuspendedOrgAreIndistinguishableFromWrongPassword() throws SQLException {
        user(orgId, "dave", PASSWORD).create();
        String suspendedCode = uniqueOrgCode();
        long suspendedOrg = createOrganization(suspendedCode, "SUSPENDED");
        user(suspendedOrg, "dave", PASSWORD).create();

        Response wrongPassword = api.login(orgCode, "dave", WRONG, "WEB");
        Response unknownUser = api.login(orgCode, "nobody", PASSWORD, "WEB");
        Response unknownOrg = api.login("NO-SUCH-ORG", "dave", PASSWORD, "WEB");
        Response suspended = api.login(suspendedCode, "dave", PASSWORD, "WEB");

        for (Response r : List.of(unknownUser, unknownOrg, suspended)) {
            assertThat(r.status()).isEqualTo(wrongPassword.status()).isEqualTo(401);
            assertThat(r.code()).isEqualTo(wrongPassword.code());
            assertThat(r.text("message")).isEqualTo(wrongPassword.text("message"));
        }
        assertThat(countAudit(orgId, "SYSTEM", null, "FAILURE", "UNKNOWN_IDENTIFIER")).isEqualTo(1);
    }

    @Test
    void softDeletedUserCannotLogin() throws SQLException {
        user(orgId, "erin", PASSWORD).deleted().create();

        assertThat(api.login(orgCode, "erin", PASSWORD, "WEB").code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void locksAfterThresholdAndRejectsEvenCorrectPasswordWhileLocked() throws SQLException {
        long userId = user(orgId, "frank", PASSWORD).create();

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(api.login(orgCode, "frank", WRONG, "WEB").status()).isEqualTo(401);
            assertThat(readUser(userId).lockedNow()).isFalse();
        }
        assertThat(api.login(orgCode, "frank", WRONG, "WEB").status()).isEqualTo(401);
        AuthTestData.UserState locked = readUser(userId);
        assertThat(locked.failedLoginCount()).isEqualTo(5);
        assertThat(locked.lockedNow()).isTrue();
        assertThat(countAudit(orgId, "USER", userId, "FAILURE", "LOCKED_OUT")).isEqualTo(1);

        Response whileLocked = api.login(orgCode, "frank", PASSWORD, "WEB");

        assertThat(whileLocked.status()).isEqualTo(401);
        assertThat(whileLocked.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(readUser(userId).failedLoginCount()).isEqualTo(5);
        assertThat(countAudit(orgId, "USER", userId, "FAILURE", "TEMPORARILY_LOCKED")).isEqualTo(1);
        assertThat(countRefreshTokens(userId, "TRUE")).isZero();
    }

    @Test
    void expiredTemporaryLockAllowsCorrectPasswordAndResets() throws SQLException {
        long userId = user(orgId, "grace", PASSWORD)
                .failedLoginCount(5).lockedUntilSql("now() - interval '1 minute'").create();

        assertThat(api.login(orgCode, "grace", PASSWORD, "WEB").status()).isEqualTo(200);

        AuthTestData.UserState state = readUser(userId);
        assertThat(state.failedLoginCount()).isZero();
        assertThat(state.lockedUntil()).isNull();
    }

    @Test
    void failureAfterExpiredLockRestartsCounterInsteadOfRelocking() throws SQLException {
        long userId = user(orgId, "heidi", PASSWORD)
                .failedLoginCount(5).lockedUntilSql("now() - interval '1 minute'").create();

        assertThat(api.login(orgCode, "heidi", WRONG, "WEB").status()).isEqualTo(401);

        AuthTestData.UserState state = readUser(userId);
        assertThat(state.failedLoginCount()).isEqualTo(1);
        assertThat(state.lockedUntil()).isNull();
    }

    @Test
    void adminLockedAccountIsRevealedOnlyAfterCorrectPassword() throws SQLException {
        long userId = user(orgId, "ivan", PASSWORD).status("LOCKED").create();

        Response wrong = api.login(orgCode, "ivan", WRONG, "WEB");
        Response correct = api.login(orgCode, "ivan", PASSWORD, "WEB");

        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(correct.status()).isEqualTo(403);
        assertThat(correct.code()).isEqualTo("ACCOUNT_LOCKED");
        assertThat(countAudit(orgId, "USER", userId, "DENIED", "ACCOUNT_LOCKED")).isEqualTo(1);
        assertThat(countRefreshTokens(userId, "TRUE")).isZero();
    }

    @Test
    void disabledAccountIsDenied() throws SQLException {
        long userId = user(orgId, "judy", PASSWORD).status("DISABLED").create();

        Response response = api.login(orgCode, "judy", PASSWORD, "WEB");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("ACCOUNT_DISABLED");
        assertThat(countAudit(orgId, "USER", userId, "DENIED", "ACCOUNT_DISABLED")).isEqualTo(1);
    }

    @Test
    void mustChangePasswordIsReturned() throws SQLException {
        user(orgId, "ken", PASSWORD).mustChangePassword(true).create();

        Response response = api.login(orgCode, "ken", PASSWORD, "MOBILE");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("mustChangePassword").asBoolean()).isTrue();
    }

    @Test
    void tenantsAreIsolatedForSameUsername() throws SQLException {
        String otherCode = uniqueOrgCode();
        long otherOrg = createOrganization(otherCode, "ACTIVE");
        long userA = user(orgId, "shared", PASSWORD).create();
        long userB = user(otherOrg, "shared", "Other-Tenant-Pass1").create();

        Response crossTenant = api.login(orgCode, "shared", "Other-Tenant-Pass1", "WEB");

        assertThat(crossTenant.status()).isEqualTo(401);
        assertThat(readUser(userA).failedLoginCount()).isEqualTo(1);
        assertThat(readUser(userB).failedLoginCount()).isZero();

        Response ok = api.login(otherCode, "shared", "Other-Tenant-Pass1", "WEB");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(decode(ok).getSubject()).isEqualTo(Long.toString(userB));
        assertThat(orgClaim(decode(ok))).isEqualTo(otherOrg);
        assertThat(countAudit(otherOrg, "USER", userB, "SUCCESS", null)).isEqualTo(1);
        assertThat(countAudit(orgId, "USER", userB, "SUCCESS", null)).isZero();
    }

    @Test
    void concurrentFailuresAreAllCounted() throws Exception {
        long userId = user(orgId, "leo", PASSWORD).create();

        List<Response> responses = concurrently(4, () -> api.login(orgCode, "leo", WRONG, "WEB"));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(401));
        assertThat(readUser(userId).failedLoginCount()).isEqualTo(4);
        assertThat(countAudit(orgId, "USER", userId, "FAILURE", "BAD_CREDENTIALS")).isEqualTo(4);
    }

    @Test
    void concurrentFailuresBeyondThresholdLockTheAccount() throws Exception {
        long userId = user(orgId, "mia", PASSWORD).create();

        List<Response> responses = concurrently(10, () -> api.login(orgCode, "mia", WRONG, "WEB"));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(401));
        AuthTestData.UserState state = readUser(userId);
        assertThat(state.lockedNow()).isTrue();
        assertThat(state.failedLoginCount()).isBetween(5, 10);
        int counted = countAudit(orgId, "USER", userId, "FAILURE", "BAD_CREDENTIALS")
                + countAudit(orgId, "USER", userId, "FAILURE", "LOCKED_OUT");
        int skippedWhileLocked = countAudit(orgId, "USER", userId, "FAILURE", "TEMPORARILY_LOCKED");
        assertThat(counted).as("mọi lần sai được đếm đều phản ánh vào bộ đếm").isEqualTo(state.failedLoginCount());
        assertThat(counted + skippedWhileLocked).isEqualTo(10);
    }

    @Test
    void invalidRequestReturns400WithoutEchoingPassword() {
        Response missingClientType = api.post("/api/v1/auth/login",
                "{\"orgCode\":\"X\",\"identifier\":\"u\",\"password\":\"Secret-Value-123\"}");
        Response badClientType = api.post("/api/v1/auth/login",
                "{\"orgCode\":\"X\",\"identifier\":\"u\",\"password\":\"Secret-Value-123\",\"clientType\":\"DESKTOP\"}");

        assertThat(missingClientType.status()).isEqualTo(400);
        assertThat(missingClientType.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(missingClientType.rawBody()).doesNotContain("Secret-Value-123");
        assertThat(badClientType.status()).isEqualTo(400);
        assertThat(badClientType.code()).isEqualTo("INVALID_REQUEST");
        assertThat(badClientType.rawBody()).doesNotContain("Secret-Value-123");
    }

    @Test
    void frameworkErrorsKeepTheirOwnStatus() {
        Response response = api.send("POST", "/api/v1/auth/login", "orgCode=X", "text/plain");

        assertThat(response.status()).isEqualTo(415);
        assertThat(response.text("traceId")).isNotBlank();
    }

    @Test
    void errorDispatchIsNotMaskedAs403ButDirectErrorRequestIsDenied() throws Exception {
        MockHttpServletRequest errorDispatch = new MockHttpServletRequest("POST", "/error");
        errorDispatch.setDispatcherType(DispatcherType.ERROR);
        MockFilterChain errorChain = new MockFilterChain();
        springSecurityFilterChain.doFilter(errorDispatch, new MockHttpServletResponse(), errorChain);

        assertThat(errorChain.getRequest()).as("lượt ERROR dispatch phải đi qua security tới error controller").isNotNull();

        MockHttpServletRequest direct = new MockHttpServletRequest("GET", "/error");
        MockHttpServletResponse directResponse = new MockHttpServletResponse();
        MockFilterChain directChain = new MockFilterChain();
        springSecurityFilterChain.doFilter(direct, directResponse, directChain);

        assertThat(directChain.getRequest()).as("gọi thẳng /error vẫn bị chặn").isNull();
        assertThat(directResponse.getStatus()).isIn(401, 403);
    }

    static <T> List<T> concurrently(int count, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
