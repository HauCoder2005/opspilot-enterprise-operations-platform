package dev.opspilot.services.auth;

import dev.opspilot.configs.auth.AuthTokenProperties;
import dev.opspilot.enums.ClientType;
import dev.opspilot.enums.ErrorCode;
import dev.opspilot.exceptions.ApiException;
import dev.opspilot.repositories.audit.AuditLogRepository;
import dev.opspilot.repositories.audit.AuditLogRepository.AuditEntry;
import dev.opspilot.repositories.audit.AuditLogRepository.Result;
import dev.opspilot.repositories.auth.RefreshTokenRepository;
import dev.opspilot.enums.iam.RefreshTokenRevokeReason;
import dev.opspilot.repositories.auth.RefreshTokenRepository.StoredToken;
import dev.opspilot.repositories.auth.RefreshTokenRepository.TokenRow;
import dev.opspilot.repositories.auth.UserCredentialRepository;
import dev.opspilot.repositories.auth.UserCredentialRepository.SessionUser;
import dev.opspilot.security.tenant.TenantDatabaseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Refresh token xoay vòng (KAN-77): tạo family khi đăng nhập, xoay vòng có khoá dòng, phát hiện dùng lại,
 * logout một phiên và logout mọi thiết bị.
 *
 * <p>Mọi kết quả thất bại (đặc biệt thu hồi cả family khi phát hiện dùng lại) được COMMIT trong giao dịch riêng,
 * rồi mới ném {@link ApiException} 401 — rollback không thể khôi phục token đã bị thu hồi.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    static final String ACTION_REFRESH = "auth.token.refresh";
    static final String ACTION_LOGOUT = "auth.logout";
    static final String ACTION_LOGOUT_ALL = "auth.logout_all";

    private final RefreshTokenRepository tokens;
    private final UserCredentialRepository users;
    private final AccessTokenService accessTokens;
    private final AuditLogRepository auditLog;
    private final TenantDatabaseContext tenantContext;
    private final AuthTokenProperties properties;
    private final TransactionTemplate transaction;

    public RefreshTokenService(RefreshTokenRepository tokens, UserCredentialRepository users,
                               AccessTokenService accessTokens, AuditLogRepository auditLog,
                               TenantDatabaseContext tenantContext, AuthTokenProperties properties,
                               PlatformTransactionManager transactionManager) {
        this.tokens = tokens;
        this.users = users;
        this.accessTokens = accessTokens;
        this.auditLog = auditLog;
        this.tenantContext = tenantContext;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Tạo token đầu tiên của một family. Phải gọi trong giao dịch đang hoạt động với tenant đã gắn
     * (giao dịch ghi nhận đăng nhập thành công).
     */
    public NewRefreshToken createFamily(long organizationId, long userId, ClientType clientType, ClientInfo client) {
        requireTransaction();
        return insert(organizationId, userId, null, clientType, client);
    }

    public RefreshResult refresh(String rawToken, ClientInfo client) {
        long orgId = RefreshTokenCodec.organizationIdOf(rawToken)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN));
        String hash = RefreshTokenCodec.hash(rawToken);

        Outcome outcome = transaction.execute(status -> {
            tenantContext.bind(orgId);
            Optional<TokenRow> found = tokens.findForUpdate(orgId, hash);
            if (found.isEmpty()) {
                return Outcome.rejected("UNKNOWN_TOKEN", null);
            }
            TokenRow row = found.get();
            if (row.revoked()) {
                if (RefreshTokenRevokeReason.ROTATED.name().equals(row.revokeReason())) {
                    int revoked = tokens.revokeFamily(orgId, row.familyId(), RefreshTokenRevokeReason.REUSE_DETECTED);
                    audit(orgId, row.userId(), ACTION_REFRESH, Result.FAILURE, "REFRESH_TOKEN_REUSE",
                            "{\"familyId\":" + row.familyId() + ",\"revoked\":" + revoked + "}", client);
                    log.warn("Refresh token reuse detected: orgId={}, userId={}, familyId={}, revoked={}",
                            orgId, row.userId(), row.familyId(), revoked);
                    return Outcome.rejected("REFRESH_TOKEN_REUSE", row.userId());
                }
                audit(orgId, row.userId(), ACTION_REFRESH, Result.FAILURE, "REVOKED_TOKEN", null, client);
                return Outcome.rejected("REVOKED_TOKEN", row.userId());
            }
            if (row.expired()) {
                audit(orgId, row.userId(), ACTION_REFRESH, Result.FAILURE, "EXPIRED_TOKEN", null, client);
                return Outcome.rejected("EXPIRED_TOKEN", row.userId());
            }
            Optional<SessionUser> user = users.findSessionUser(orgId, row.userId());
            if (user.isEmpty() || !user.get().isActive()) {
                tokens.revokeFamily(orgId, row.familyId(), RefreshTokenRevokeReason.ADMIN);
                audit(orgId, row.userId(), ACTION_REFRESH, Result.DENIED, "ACCOUNT_NOT_ACTIVE", null, client);
                return Outcome.rejected("ACCOUNT_NOT_ACTIVE", row.userId());
            }
            SessionUser sessionUser = user.get();
            ClientType clientType = ClientType.valueOf(row.clientType());
            NewRefreshToken next = insert(orgId, sessionUser.id(), row.familyId(), clientType, client);
            tokens.markRotated(orgId, row.id(), next.id());
            AccessTokenService.AccessToken access = accessTokens.issue(new IAuthTokenIssuer.TokenSubject(
                    sessionUser.id(), orgId, sessionUser.tokenVersion(), sessionUser.mustChangePassword()));
            audit(orgId, sessionUser.id(), ACTION_REFRESH, Result.SUCCESS, null,
                    "{\"familyId\":" + row.familyId() + "}", client);
            return Outcome.success(new RefreshResult(
                    new IAuthTokenIssuer.IssuedTokens(access.value(), access.expiresAt(), next.rawToken(), next.expiresAt()),
                    clientType, sessionUser.id(), sessionUser.displayName(), sessionUser.mustChangePassword()));
        });

        if (outcome.result() == null) {
            log.info("Refresh rejected: reason={}, orgId={}, userId={}", outcome.reason(), orgId, outcome.userId());
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        return outcome.result();
    }

    /** Logout phiên hiện tại: thu hồi cả family của refresh token được trình ra. Luôn thành công (idempotent). */
    public void logout(String rawToken, ClientInfo client) {
        Optional<Long> orgId = RefreshTokenCodec.organizationIdOf(rawToken);
        if (orgId.isEmpty()) {
            return;
        }
        String hash = RefreshTokenCodec.hash(rawToken);
        transaction.executeWithoutResult(status -> {
            tenantContext.bind(orgId.get());
            tokens.findForUpdate(orgId.get(), hash).ifPresent(row -> {
                int revoked = tokens.revokeFamily(orgId.get(), row.familyId(), RefreshTokenRevokeReason.LOGOUT);
                if (revoked > 0) {
                    audit(orgId.get(), row.userId(), ACTION_LOGOUT, Result.SUCCESS, null,
                            "{\"familyId\":" + row.familyId() + ",\"revoked\":" + revoked + "}", client);
                }
            });
        });
    }

    /** Logout mọi thiết bị: thu hồi mọi refresh token và tăng token_version để vô hiệu access token đang lưu hành. */
    public void logoutAll(long organizationId, long userId, ClientInfo client) {
        transaction.executeWithoutResult(status -> {
            tenantContext.bind(organizationId);
            int revoked = tokens.revokeAllForUser(organizationId, userId, RefreshTokenRevokeReason.LOGOUT);
            users.incrementTokenVersion(organizationId, userId);
            audit(organizationId, userId, ACTION_LOGOUT_ALL, Result.SUCCESS, null, "{\"revoked\":" + revoked + "}", client);
        });
    }

    /** Thu hồi mọi refresh token của user trong giao dịch hiện tại (đổi/reset mật khẩu). */
    public int revokeAllInCurrentTransaction(long organizationId, long userId, RefreshTokenRevokeReason reason) {
        requireTransaction();
        return tokens.revokeAllForUser(organizationId, userId, reason);
    }

    private NewRefreshToken insert(long orgId, long userId, Long familyId, ClientType clientType, ClientInfo client) {
        String raw = RefreshTokenCodec.generate(orgId);
        StoredToken stored = tokens.insert(orgId, userId, RefreshTokenCodec.hash(raw), familyId, ttlFor(clientType),
                clientType.name(), client.userAgent(), client.ipAddress());
        return new NewRefreshToken(stored.id(), stored.familyId(), raw, stored.expiresAt());
    }

    private Duration ttlFor(ClientType clientType) {
        return clientType == ClientType.MOBILE ? properties.refreshTokenTtlMobile() : properties.refreshTokenTtlWeb();
    }

    private void audit(long orgId, long userId, String action, Result result, String reason, String data, ClientInfo client) {
        auditLog.append(AuditEntry.ofUser(orgId, userId, action, result, reason, data,
                client.ipAddress(), client.userAgent(), client.requestId()));
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Thao tác refresh token phải chạy trong giao dịch đang hoạt động");
        }
    }

    public record NewRefreshToken(long id, long familyId, String rawToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "NewRefreshToken[id=" + id + ", familyId=" + familyId + ", rawToken=***, expiresAt=" + expiresAt + "]";
        }
    }

    public record RefreshResult(IAuthTokenIssuer.IssuedTokens tokens, ClientType clientType, long userId,
                                String displayName, boolean mustChangePassword) {
    }

    private record Outcome(RefreshResult result, String reason, Long userId) {
        static Outcome success(RefreshResult result) {
            return new Outcome(result, null, result.userId());
        }

        static Outcome rejected(String reason, Long userId) {
            return new Outcome(null, reason, userId);
        }
    }
}
