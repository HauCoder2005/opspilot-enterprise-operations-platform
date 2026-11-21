package dev.opspilot.auth;

import dev.opspilot.security.authentication.OpsPilotPrincipal;
import dev.opspilot.services.iam.IUserManagementPolicy;
import dev.opspilot.support.ApiClient.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static dev.opspilot.auth.AuthTestData.countAuditAction;
import static dev.opspilot.auth.AuthTestData.countRefreshTokens;
import static dev.opspilot.auth.AuthTestData.createOrganization;
import static dev.opspilot.auth.AuthTestData.mustChangePassword;
import static dev.opspilot.auth.AuthTestData.tokenVersion;
import static dev.opspilot.auth.AuthTestData.uniqueOrgCode;
import static dev.opspilot.auth.AuthTestData.user;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAN-78 — admin reset với một policy TEST DOUBLE (allow-list actor→target). Kiểm chứng service tôn trọng quyết định
 * của policy, chống tự reset, cách ly tenant và hậu quả bảo mật. Không chứng minh logic phân quyền của KAN-81.
 */
@Import(AdminPasswordResetIntegrationTest.PolicyConfig.class)
class AdminPasswordResetIntegrationTest extends AbstractAuthIntegrationTest {

    @TestConfiguration
    static class PolicyConfig {
        @Bean
        @Primary
        AllowListPolicy allowListPolicy() {
            return new AllowListPolicy();
        }
    }

    static class AllowListPolicy implements IUserManagementPolicy {
        final Set<String> allowed = ConcurrentHashMap.newKeySet();

        @Override
        public boolean canManageUser(OpsPilotPrincipal actor, long targetUserId) {
            return allowed.contains(actor.userId() + ":" + targetUserId);
        }
    }

    @Autowired
    AllowListPolicy policy;

    @BeforeEach
    void resetPolicy() {
        policy.allowed.clear();
    }

    @Test
    void allowedResetIssuesOneTimeTemporaryPasswordAndRevokesTargetSessions() throws SQLException {
        long adminId = user(orgId, "ar.admin", PASSWORD).create();
        long targetId = user(orgId, "ar.target", PASSWORD).create();
        Response admin = api.login(orgCode, "ar.admin", PASSWORD, "MOBILE");
        Response targetSession = api.login(orgCode, "ar.target", PASSWORD, "MOBILE");
        int versionBefore = tokenVersion(targetId);
        policy.allowed.add(adminId + ":" + targetId);

        Response reset = reset(admin, targetId);

        assertThat(reset.status()).isEqualTo(200);
        String temporary = reset.text("temporaryPassword");
        assertThat(temporary).hasSize(16);
        assertThat(reset.rawBody()).doesNotContain("password_hash").doesNotContain("{bcrypt}");
        assertThat(reset.header("Cache-Control")).contains("no-store");
        assertThat(mustChangePassword(targetId)).isTrue();
        assertThat(tokenVersion(targetId)).isEqualTo(versionBefore + 1);
        assertThat(countRefreshTokens(targetId, "revoked_at IS NULL")).isZero();
        assertThat(countRefreshTokens(targetId, "revoke_reason = 'ADMIN'")).isEqualTo(1);
        assertThat(api.get("/api/v1/auth/me", "Authorization", bearer(targetSession)).status()).isEqualTo(401);
        assertThat(api.login(orgCode, "ar.target", PASSWORD, "WEB").status()).isEqualTo(401);
        Response withTemporary = api.login(orgCode, "ar.target", temporary, "WEB");
        assertThat(withTemporary.status()).isEqualTo(200);
        assertThat(withTemporary.body().get("mustChangePassword").asBoolean()).isTrue();
        assertThat(countAuditAction(orgId, "iam.user.password.reset", "SUCCESS", null)).isEqualTo(1);
    }

    @Test
    void deniedByPolicyReturns403AndIsAudited() throws SQLException {
        user(orgId, "ar.nopriv", PASSWORD).create();
        long targetId = user(orgId, "ar.victim", PASSWORD).create();
        Response actor = api.login(orgCode, "ar.nopriv", PASSWORD, "MOBILE");

        Response reset = reset(actor, targetId);

        assertThat(reset.status()).isEqualTo(403);
        assertThat(reset.code()).isEqualTo("ACCESS_DENIED");
        assertThat(countAuditAction(orgId, "iam.user.password.reset", "DENIED", "POLICY_DENIED")).isEqualTo(1);
        assertThat(api.login(orgCode, "ar.victim", PASSWORD, "WEB").status()).isEqualTo(200);
    }

    @Test
    void selfResetIsRejectedEvenIfPolicyAllows() throws SQLException {
        long adminId = user(orgId, "ar.self", PASSWORD).create();
        Response admin = api.login(orgCode, "ar.self", PASSWORD, "MOBILE");
        policy.allowed.add(adminId + ":" + adminId);

        Response reset = reset(admin, adminId);

        assertThat(reset.status()).isEqualTo(400);
        assertThat(reset.code()).isEqualTo("SELF_RESET_NOT_ALLOWED");
    }

    @Test
    void crossTenantTargetIsNotFoundEvenIfPolicyWronglyAllows() throws SQLException {
        long adminId = user(orgId, "ar.cross", PASSWORD).create();
        String otherCode = uniqueOrgCode();
        long otherOrg = createOrganization(otherCode, "ACTIVE");
        long foreignUser = user(otherOrg, "ar.foreign", PASSWORD).create();
        Response admin = api.login(orgCode, "ar.cross", PASSWORD, "MOBILE");
        policy.allowed.add(adminId + ":" + foreignUser);

        Response reset = reset(admin, foreignUser);

        assertThat(reset.status()).isEqualTo(404);
        assertThat(reset.code()).isEqualTo("USER_NOT_FOUND");
        assertThat(mustChangePassword(foreignUser)).isFalse();
        assertThat(api.login(otherCode, "ar.foreign", PASSWORD, "WEB").status()).isEqualTo(200);
    }

    private Response reset(Response actorLogin, long targetId) {
        return api.post("/api/v1/iam/users/" + targetId + "/reset-password", null, "Authorization", bearer(actorLogin));
    }
}
