package dev.opspilot.services.iam;

import dev.opspilot.enums.ErrorCode;
import dev.opspilot.exceptions.ApiException;
import dev.opspilot.repositories.audit.AuditLogRepository;
import dev.opspilot.repositories.audit.AuditLogRepository.ActorType;
import dev.opspilot.repositories.audit.AuditLogRepository.AuditEntry;
import dev.opspilot.repositories.audit.AuditLogRepository.Result;
import dev.opspilot.repositories.auth.RefreshTokenRepository.RevokeReason;
import dev.opspilot.repositories.auth.UserCredentialRepository;
import dev.opspilot.security.OpsPilotPrincipal;
import dev.opspilot.security.tenant.TenantDatabaseContext;
import dev.opspilot.services.auth.ClientInfo;
import dev.opspilot.services.auth.RefreshTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;

/**
 * Quản trị viên reset mật khẩu người dùng (KAN-78). Quyền và phạm vi do {@link IUserManagementPolicy} (KAN-81)
 * quyết định; khi chưa có implementation thì fail-closed (503), không có đường tắt theo role name.
 *
 * <p>Sinh mật khẩu tạm ngẫu nhiên, trả về DUY NHẤT một lần trong response (cách giao mật khẩu tạm là OPEN DECISION),
 * đặt must_change_password, tăng token_version, xoá khoá tạm và thu hồi mọi refresh token (ADMIN).
 */
@Service
public class AdminPasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordResetService.class);
    static final String ACTION = "iam.user.password.reset";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int TEMP_PASSWORD_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectProvider<IUserManagementPolicy> policy;
    private final UserCredentialRepository users;
    private final RefreshTokenService refreshTokens;
    private final AuditLogRepository auditLog;
    private final TenantDatabaseContext tenantContext;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;

    public AdminPasswordResetService(ObjectProvider<IUserManagementPolicy> policy, UserCredentialRepository users,
                                     RefreshTokenService refreshTokens, AuditLogRepository auditLog,
                                     TenantDatabaseContext tenantContext, PasswordEncoder passwordEncoder,
                                     PlatformTransactionManager transactionManager) {
        this.policy = policy;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.auditLog = auditLog;
        this.tenantContext = tenantContext;
        this.passwordEncoder = passwordEncoder;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public ResetResult resetPassword(OpsPilotPrincipal actor, long targetUserId, ClientInfo client) {
        IUserManagementPolicy userPolicy = policy.getIfAvailable();
        if (userPolicy == null) {
            log.error("Admin password reset unavailable: no IUserManagementPolicy implementation (KAN-81 pending)");
            throw new ApiException(ErrorCode.POLICY_SERVICE_UNAVAILABLE);
        }
        if (targetUserId == actor.userId()) {
            throw new ApiException(ErrorCode.SELF_RESET_NOT_ALLOWED);
        }
        long orgId = actor.organizationId();
        if (!userPolicy.canManageUser(actor, targetUserId)) {
            transaction.executeWithoutResult(status -> {
                tenantContext.bind(orgId);
                audit(orgId, actor, targetUserId, Result.DENIED, "POLICY_DENIED", null, client);
            });
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        String temporaryPassword = generateTemporaryPassword();
        String hash = passwordEncoder.encode(temporaryPassword);
        Integer revoked = transaction.execute(status -> {
            tenantContext.bind(orgId);
            if (!users.adminResetPassword(orgId, targetUserId, hash, actor.userId())) {
                return null;
            }
            int count = refreshTokens.revokeAllInCurrentTransaction(orgId, targetUserId, RevokeReason.ADMIN);
            audit(orgId, actor, targetUserId, Result.SUCCESS, null, "{\"revokedRefreshTokens\":" + count + "}", client);
            return count;
        });
        if (revoked == null) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        }
        log.info("Password reset by admin: orgId={}, actorId={}, targetUserId={}", orgId, actor.userId(), targetUserId);
        return new ResetResult(targetUserId, temporaryPassword);
    }

    private void audit(long orgId, OpsPilotPrincipal actor, long targetUserId, Result result, String reason, String data,
                       ClientInfo client) {
        auditLog.append(new AuditEntry(orgId, ActorType.USER, actor.userId(), ACTION, "app_user", targetUserId,
                result, reason, data, client.ipAddress(), client.userAgent(), client.requestId()));
    }

    private static String generateTemporaryPassword() {
        StringBuilder builder = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    public record ResetResult(long userId, String temporaryPassword) {
        @Override
        public String toString() {
            return "ResetResult[userId=" + userId + ", temporaryPassword=***]";
        }
    }
}
