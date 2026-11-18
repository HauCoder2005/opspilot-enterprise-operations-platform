package dev.opspilot.services.auth;

import dev.opspilot.configs.auth.LoginProperties;
import dev.opspilot.enums.ErrorCode;
import dev.opspilot.exceptions.ApiException;
import dev.opspilot.repositories.audit.AuditLogRepository;
import dev.opspilot.repositories.audit.AuditLogRepository.AuditEntry;
import dev.opspilot.repositories.audit.AuditLogRepository.Result;
import dev.opspilot.repositories.auth.LoginAccountRepository;
import dev.opspilot.enums.iam.RefreshTokenRevokeReason;
import dev.opspilot.repositories.auth.UserCredentialRepository;
import dev.opspilot.repositories.auth.UserCredentialRepository.SessionUser;
import dev.opspilot.security.authentication.OpsPilotPrincipal;
import dev.opspilot.security.tenant.TenantDatabaseContext;
import dev.opspilot.validators.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Người dùng tự đổi mật khẩu (KAN-78), cũng là bước bắt buộc khi {@code must_change_password = TRUE}.
 *
 * <p>Sai mật khẩu hiện tại được tính vào cùng bộ đếm chống dò mật khẩu của đăng nhập (commit trước khi trả lỗi).
 * Thành công: hash mới (DelegatingPasswordEncoder), xoá must_change_password, tăng token_version, thu hồi mọi
 * refresh token (PASSWORD_CHANGED) — người dùng đăng nhập lại trên mọi thiết bị.
 */
@Service
public class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);
    static final String ACTION = "auth.password.change";

    private final UserCredentialRepository users;
    private final LoginAccountRepository loginAccounts;
    private final RefreshTokenService refreshTokens;
    private final AuditLogRepository auditLog;
    private final TenantDatabaseContext tenantContext;
    private final PasswordEncoder passwordEncoder;
    private final LoginProperties loginProperties;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;

    public PasswordChangeService(UserCredentialRepository users, LoginAccountRepository loginAccounts,
                                 RefreshTokenService refreshTokens, AuditLogRepository auditLog,
                                 TenantDatabaseContext tenantContext, PasswordEncoder passwordEncoder,
                                 LoginProperties loginProperties, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.loginAccounts = loginAccounts;
        this.refreshTokens = refreshTokens;
        this.auditLog = auditLog;
        this.tenantContext = tenantContext;
        this.passwordEncoder = passwordEncoder;
        this.loginProperties = loginProperties;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    public void changePassword(OpsPilotPrincipal principal, String currentPassword, String newPassword, ClientInfo client) {
        if (!PasswordPolicy.isAcceptable(newPassword)) {
            throw new ApiException(ErrorCode.PASSWORD_POLICY_VIOLATION);
        }
        long orgId = principal.organizationId();
        long userId = principal.userId();
        SessionUser user = readTransaction.execute(status -> {
            tenantContext.bind(orgId);
            return users.findSessionUser(orgId, userId).orElse(null);
        });
        if (user == null || !user.isActive() || user.temporarilyLocked()) {
            // Tài khoản bị khoá tạm vì dò mật khẩu: không cho tiếp tục thử mật khẩu hiện tại qua endpoint này.
            throw new ApiException(ErrorCode.CURRENT_PASSWORD_INCORRECT);
        }
        if (!matches(currentPassword, user.passwordHash())) {
            writeTransaction.executeWithoutResult(status -> {
                tenantContext.bind(orgId);
                var state = loginAccounts.recordFailedAttempt(orgId, userId,
                        loginProperties.maxFailedAttempts(), loginProperties.lockDuration());
                audit(orgId, userId, Result.FAILURE, "CURRENT_PASSWORD_INCORRECT",
                        state.map(s -> "{\"failedLoginCount\":" + s.failedLoginCount() + ",\"locked\":" + s.locked() + "}")
                                .orElse(null), client);
            });
            throw new ApiException(ErrorCode.CURRENT_PASSWORD_INCORRECT);
        }
        if (newPassword.equals(currentPassword)) {
            throw new ApiException(ErrorCode.PASSWORD_REUSE_NOT_ALLOWED);
        }
        String newHash = passwordEncoder.encode(newPassword);
        boolean changed = Boolean.TRUE.equals(writeTransaction.execute(status -> {
            tenantContext.bind(orgId);
            if (!users.changeOwnPassword(orgId, userId, user.passwordHash(), newHash)) {
                return false;
            }
            int revoked = refreshTokens.revokeAllInCurrentTransaction(orgId, userId, RefreshTokenRevokeReason.PASSWORD_CHANGED);
            audit(orgId, userId, Result.SUCCESS, null, "{\"revokedRefreshTokens\":" + revoked + "}", client);
            return true;
        }));
        if (!changed) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        log.info("Password changed: orgId={}, userId={}", orgId, userId);
    }

    private boolean matches(String raw, String hash) {
        try {
            return raw != null && passwordEncoder.matches(raw, hash);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private void audit(long orgId, long userId, Result result, String reason, String data, ClientInfo client) {
        auditLog.append(AuditEntry.ofUser(orgId, userId, ACTION, result, reason, data,
                client.ipAddress(), client.userAgent(), client.requestId()));
    }
}
