package dev.opspilot.auth;

import dev.opspilot.services.auth.AccessTokenService;
import dev.opspilot.support.ApiClient.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.SQLException;

import static dev.opspilot.auth.AuthTestData.countAudit;
import static dev.opspilot.auth.AuthTestData.countRefreshTokens;
import static dev.opspilot.auth.AuthTestData.readUser;
import static dev.opspilot.auth.AuthTestData.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** Lỗi bất ngờ trả 500 an toàn (không lộ chi tiết, có requestId) và rollback toàn bộ ghi nhận đăng nhập. */
class InternalErrorHandlingIntegrationTest extends AbstractAuthIntegrationTest {

    @MockitoSpyBean
    AccessTokenService accessTokenService;

    @Test
    void unexpectedErrorReturnsSafe500AndRollsBackLogin() throws SQLException {
        long userId = user(orgId, "err.user", PASSWORD).failedLoginCount(2).create();
        doThrow(new IllegalStateException("simulated failure: jdbc:postgresql://secret-host"))
                .when(accessTokenService).issue(any());

        Response response = api.login(orgCode, "err.user", PASSWORD, "WEB");

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.text("traceId")).isEqualTo(response.header("X-Request-Id")).isNotBlank();
        assertThat(response.rawBody()).doesNotContain("simulated").doesNotContain("postgresql").doesNotContain("Exception");
        AuthTestData.UserState state = readUser(userId);
        assertThat(state.failedLoginCount()).as("reset bộ đếm phải rollback").isEqualTo(2);
        assertThat(state.lastLoginAt()).isNull();
        assertThat(countRefreshTokens(userId, "TRUE")).as("refresh token cùng giao dịch phải rollback").isZero();
        assertThat(countAudit(orgId, "USER", userId, "SUCCESS", null)).isZero();
    }
}
