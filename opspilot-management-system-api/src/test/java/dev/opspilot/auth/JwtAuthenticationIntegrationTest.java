package dev.opspilot.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import dev.opspilot.support.ApiClient.Response;
import dev.opspilot.support.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static dev.opspilot.auth.AuthTestData.createOrganization;
import static dev.opspilot.auth.AuthTestData.execute;
import static dev.opspilot.auth.AuthTestData.uniqueOrgCode;
import static dev.opspilot.auth.AuthTestData.user;
import static org.assertj.core.api.Assertions.assertThat;

/** KAN-76 — xác thực JWT: chữ ký, thời hạn, token_version, tenant, trạng thái user, 401/403 và stateless. */
class JwtAuthenticationIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String ME = "/api/v1/auth/me";

    @Test
    void validTokenAuthenticatesAndIsStateless() throws SQLException {
        long userId = user(orgId, "jwt.ok", PASSWORD).create();
        Response login = api.login(orgCode, "jwt.ok", PASSWORD, "MOBILE");

        Response me = api.get(ME, "Authorization", bearer(login));

        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("userId").asLong()).isEqualTo(userId);
        assertThat(me.body().get("organizationId").asLong()).isEqualTo(orgId);
        assertThat(me.text("username")).isEqualTo("jwt.ok");
        assertThat(me.setCookies()).as("không tạo session/cookie").isEmpty();
    }

    @Test
    void missingTokenReturns401WithBearerChallengeAndBackendTraceId() {
        Response response = api.get(ME, "X-Request-Id", "it-request-123456");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.header("Content-Type")).startsWith("application/json");
        assertThat(response.text("traceId")).isEqualTo(response.header("X-Request-Id"))
                .as("trace id do backend sinh, không lấy từ client").isNotEqualTo("it-request-123456");
    }

    @Test
    void expiredTokenIsRejected() throws SQLException {
        long userId = user(orgId, "jwt.expired", PASSWORD).create();
        Instant past = Instant.now().minusSeconds(3600);
        String token = sign(PostgresTestDatabase.JWT_SECRET, userId, orgId, 0, past, past.plusSeconds(900));

        Response response = api.get(ME, "Authorization", "Bearer " + token);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws SQLException {
        long userId = user(orgId, "jwt.forged", PASSWORD).create();
        String otherSecret = Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(48));
        String token = sign(otherSecret, userId, orgId, 0, Instant.now(), Instant.now().plusSeconds(900));

        assertThat(api.get(ME, "Authorization", "Bearer " + token).status()).isEqualTo(401);
    }

    @Test
    void malformedAndUnsignedTokensAreRejected() {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"1\",\"org_id\":" + orgId + ",\"tv\":0,\"iss\":\"opspilot-api\"}").getBytes(StandardCharsets.UTF_8));

        assertThat(api.get(ME, "Authorization", "Bearer " + header + "." + payload + ".").status()).isEqualTo(401);
        assertThat(api.get(ME, "Authorization", "Bearer not-a-jwt").status()).isEqualTo(401);
    }

    @Test
    void malformedTokenVersionClaimsAreRejectedAsInvalidTokens() throws SQLException {
        long userId = user(orgId, "jwt.tv.overflow", PASSWORD).create();
        String overflow = signWithTokenVersion(Long.toString(Integer.MAX_VALUE + 1L), userId, orgId);
        String fractional = signWithTokenVersion(new BigDecimal("0.5"), userId, orgId);

        Response overflowResponse = api.get(ME, "Authorization", "Bearer " + overflow);
        Response fractionalResponse = api.get(ME, "Authorization", "Bearer " + fractional);

        assertThat(overflowResponse.status()).isEqualTo(401);
        assertThat(overflowResponse.code()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(overflowResponse.header("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(fractionalResponse.status()).isEqualTo(401);
        assertThat(fractionalResponse.code()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void tokenVersionMismatchIsRejected() throws SQLException {
        long userId = user(orgId, "jwt.tv", PASSWORD).create();
        Response login = api.login(orgCode, "jwt.tv", PASSWORD, "MOBILE");
        assertThat(api.get(ME, "Authorization", bearer(login)).status()).isEqualTo(200);

        execute("UPDATE app_user SET token_version = token_version + 1 WHERE id = ?", userId);

        assertThat(api.get(ME, "Authorization", bearer(login)).status()).isEqualTo(401);
    }

    @Test
    void disabledOrDeletedUserTokenIsRejected() throws SQLException {
        long disabled = user(orgId, "jwt.disabled", PASSWORD).create();
        long deleted = user(orgId, "jwt.deleted", PASSWORD).create();
        Response disabledLogin = api.login(orgCode, "jwt.disabled", PASSWORD, "MOBILE");
        Response deletedLogin = api.login(orgCode, "jwt.deleted", PASSWORD, "MOBILE");

        execute("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", disabled);
        execute("UPDATE app_user SET deleted_at = now() WHERE id = ?", deleted);

        assertThat(api.get(ME, "Authorization", bearer(disabledLogin)).status()).isEqualTo(401);
        assertThat(api.get(ME, "Authorization", bearer(deletedLogin)).status()).isEqualTo(401);
    }

    @Test
    void validSignatureWithForeignTenantClaimIsRejectedByTenantScopedLookup() throws SQLException {
        long userA = user(orgId, "jwt.tenant", PASSWORD).create();
        long otherOrg = createOrganization(uniqueOrgCode(), "ACTIVE");
        // Mô phỏng token hợp lệ chữ ký nhưng org_id trỏ sang tenant khác: tra cứu user trong tenant B (RLS) không thấy A.
        String token = sign(PostgresTestDatabase.JWT_SECRET, userA, otherOrg, 0, Instant.now(), Instant.now().plusSeconds(900));

        assertThat(api.get(ME, "Authorization", "Bearer " + token).status()).isEqualTo(401);
    }

    @Test
    void mustChangePasswordRestrictsAccessToPasswordFlowOnly() throws SQLException {
        user(orgId, "jwt.mcp", PASSWORD).mustChangePassword(true).create();
        Response login = api.login(orgCode, "jwt.mcp", PASSWORD, "MOBILE");

        Response me = api.get(ME, "Authorization", bearer(login));
        Response protectedApi = api.post("/api/v1/iam/users/1/reset-password", null, "Authorization", bearer(login));

        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("mustChangePassword").asBoolean()).isTrue();
        assertThat(protectedApi.status()).isEqualTo(403);
        assertThat(protectedApi.code()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
    }

    @Test
    void unauthenticatedAccessToProtectedApiReturns401NotEmpty403() {
        Response response = api.post("/api/v1/iam/users/1/reset-password", null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void corsPreflightAllowsConfiguredOriginOnly() {
        Response allowed = api.send("OPTIONS", ME, null, null, "Origin", "http://localhost:3000",
                "Access-Control-Request-Method", "GET", "Access-Control-Request-Headers", "authorization");
        Response denied = api.send("OPTIONS", ME, null, null, "Origin", "https://evil.example",
                "Access-Control-Request-Method", "GET");

        assertThat(allowed.status()).isEqualTo(200);
        assertThat(allowed.header("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
        assertThat(denied.status()).isEqualTo(403);
    }

    private static String sign(String secret, long userId, long orgId, int tokenVersion, Instant issuedAt, Instant expiresAt) {
        return sign(secret, userId, orgId, (Object) tokenVersion, issuedAt, expiresAt);
    }

    private static String signWithTokenVersion(Object tokenVersion, long userId, long orgId) {
        return sign(PostgresTestDatabase.JWT_SECRET, userId, orgId, tokenVersion,
                Instant.now(), Instant.now().plusSeconds(900));
    }

    private static String sign(String secret, long userId, long orgId, Object tokenVersion,
                               Instant issuedAt, Instant expiresAt) {
        var key = new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("opspilot-api")
                .subject(Long.toString(userId))
                .claim("org_id", orgId)
                .claim("tv", tokenVersion)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
