package dev.opspilot.security.authentication;

import dev.opspilot.configs.auth.JwtConfig;
import dev.opspilot.repositories.auth.UserCredentialRepository;
import dev.opspilot.repositories.auth.UserCredentialRepository.SessionUser;
import dev.opspilot.security.tenant.TenantDatabaseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * Chuyển JWT ĐÃ được {@link org.springframework.security.oauth2.jwt.JwtDecoder} xác minh (chữ ký HS256, exp, iss,
 * claim bắt buộc) thành {@link OpsPilotAuthenticationToken}.
 *
 * <p>Chỉ sau bước xác minh đó mới dùng {@code org_id} làm tenant: gắn vào một giao dịch chỉ đọc rồi đối chiếu
 * {@code app_user} (RLS) — user phải tồn tại trong đúng tenant, ACTIVE, chưa xoá, và {@code token_version} phải khớp
 * claim {@code tv}. Mọi sai lệch → 401 {@code invalid_token}.
 */
@Component
public class JwtPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(JwtPrincipalConverter.class);

    private final UserCredentialRepository users;
    private final TenantDatabaseContext tenantContext;
    private final TransactionTemplate readTransaction;

    public JwtPrincipalConverter(UserCredentialRepository users, TenantDatabaseContext tenantContext,
                                 PlatformTransactionManager transactionManager) {
        this.users = users;
        this.tenantContext = tenantContext;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        long userId = parseLong(jwt.getSubject(), "sub");
        long orgId = parseLong(jwt.getClaims().get(JwtConfig.CLAIM_ORG_ID), JwtConfig.CLAIM_ORG_ID);
        int tokenVersion = Math.toIntExact(parseLong(jwt.getClaims().get(JwtConfig.CLAIM_TOKEN_VERSION), JwtConfig.CLAIM_TOKEN_VERSION));

        Optional<SessionUser> user = readTransaction.execute(status -> {
            tenantContext.bind(orgId);
            return users.findSessionUser(orgId, userId);
        });
        if (user.isEmpty() || !user.get().isActive()) {
            log.info("Access token rejected: reason=USER_NOT_ACTIVE, orgId={}, userId={}", orgId, userId);
            throw new InvalidBearerTokenException("Access token is no longer valid");
        }
        if (user.get().tokenVersion() != tokenVersion) {
            log.info("Access token rejected: reason=TOKEN_VERSION_MISMATCH, orgId={}, userId={}", orgId, userId);
            throw new InvalidBearerTokenException("Access token is no longer valid");
        }
        OpsPilotPrincipal principal = new OpsPilotPrincipal(
                userId, orgId, tokenVersion, user.get().mustChangePassword(), jwt.getId());
        return new OpsPilotAuthenticationToken(principal, jwt);
    }

    private static long parseLong(Object value, String claim) {
        try {
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text) {
                return Long.parseLong(text);
            }
        } catch (NumberFormatException ignored) {
            // rơi xuống lỗi chung
        }
        throw new InvalidBearerTokenException("Invalid claim: " + claim);
    }
}
