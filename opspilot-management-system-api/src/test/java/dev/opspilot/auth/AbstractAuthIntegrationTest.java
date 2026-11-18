package dev.opspilot.auth;

import dev.opspilot.configs.JwtConfig;
import dev.opspilot.support.ApiClient;
import dev.opspilot.support.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.SQLException;

import static dev.opspilot.auth.AuthTestData.createOrganization;
import static dev.opspilot.auth.AuthTestData.uniqueOrgCode;

/**
 * Cấu hình chung cho integration test Authentication: ứng dụng thật trên cổng ngẫu nhiên, PostgreSQL 18
 * (Testcontainers) kết nối bằng opspilot_app nên RLS có hiệu lực. Các lớp con dùng chung một Spring context.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.rate-limit.enabled=false",
                "app.auth.login.max-failed-attempts=5",
                "app.auth.login.lock-duration=15m",
                "app.auth.token.refresh-cookie.secure=true"
        })
abstract class AbstractAuthIntegrationTest {

    static final String PASSWORD = "Correct-Horse-9!";
    static final String WRONG = "wrong-password";
    static final String COOKIE = "opspilot_rt";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.registerAppDataSource(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    JwtDecoder jwtDecoder;

    ApiClient api;
    String orgCode;
    long orgId;

    @BeforeEach
    void setUpTenant() throws SQLException {
        api = new ApiClient(port);
        orgCode = uniqueOrgCode();
        orgId = createOrganization(orgCode, "ACTIVE");
    }

    Jwt decode(ApiClient.Response response) {
        return jwtDecoder.decode(response.text("accessToken"));
    }

    static long claimLong(Jwt jwt, String claim) {
        return ((Number) jwt.getClaims().get(claim)).longValue();
    }

    static long orgClaim(Jwt jwt) {
        return claimLong(jwt, JwtConfig.CLAIM_ORG_ID);
    }

    static String bearer(ApiClient.Response loginResponse) {
        return "Bearer " + loginResponse.text("accessToken");
    }
}
