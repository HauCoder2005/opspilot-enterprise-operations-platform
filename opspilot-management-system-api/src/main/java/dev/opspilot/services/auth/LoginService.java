package dev.opspilot.services.auth;

import dev.opspilot.configs.auth.LoginProperties;
import dev.opspilot.dtos.auth.LoginRequest;
import dev.opspilot.dtos.auth.LoginResponse;
import dev.opspilot.enums.ErrorCode;
import dev.opspilot.exceptions.ApiException;
import dev.opspilot.repositories.audit.AuditLogRepository;
import dev.opspilot.repositories.audit.AuditLogRepository.ActorType;
import dev.opspilot.repositories.audit.AuditLogRepository.AuditEntry;
import dev.opspilot.repositories.audit.AuditLogRepository.Result;
import dev.opspilot.repositories.auth.LoginAccountRepository;
import dev.opspilot.repositories.auth.LoginAccountRepository.FailedAttemptState;
import dev.opspilot.repositories.auth.LoginAccountRepository.LoginAccount;
import dev.opspilot.repositories.auth.LoginAccountRepository.SuccessfulLogin;
import dev.opspilot.security.tenant.TenantDatabaseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Use case đăng nhập (KAN-75): mã doanh nghiệp + username/email/phone + mật khẩu.
 *
 * <p>Ranh giới giao dịch (lập trình bằng {@link TransactionTemplate}, không dùng {@code @Transactional}):
 * <ol>
 *   <li>Giao dịch đọc: tra doanh nghiệp qua {@code find_organization_id}, gắn tenant, đọc tài khoản.</li>
 *   <li>Kiểm tra mật khẩu NGOÀI giao dịch để không giữ connection trong lúc BCrypt chạy.</li>
 *   <li>Giao dịch ghi: gắn lại tenant, cập nhật bộ đếm/audit (và gọi dịch vụ token khi thành công).</li>
 * </ol>
 * Kết quả thất bại được COMMIT trước khi ném {@link ApiException}, nên bộ đếm và audit không bị rollback
 * khi phản hồi là 401/403.
 *
 * <p>Chống dò tài khoản: doanh nghiệp không tồn tại, tài khoản không tồn tại, sai mật khẩu và đang khoá tạm
 * đều trả cùng {@code INVALID_CREDENTIALS} và đều tốn một lần BCrypt (dummy hash khi cần).
 * Trạng thái LOCKED/DISABLED chỉ được tiết lộ sau khi mật khẩu đúng.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    static final String AUDIT_ACTION = "auth.login";
    static final String AUDIT_ENTITY = "app_user";

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_LOCKED = "LOCKED";
    private static final String TOKEN_TYPE = "Bearer";

    private final LoginAccountRepository accounts;
    private final AuditLogRepository auditLog;
    private final TenantDatabaseContext tenantContext;
    private final PasswordEncoder passwordEncoder;
    private final IAuthTokenIssuer tokenIssuer;
    private final LoginProperties properties;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;
    private final String dummyPasswordHash;

    public LoginService(
            LoginAccountRepository accounts,
            AuditLogRepository auditLog,
            TenantDatabaseContext tenantContext,
            PasswordEncoder passwordEncoder,
            IAuthTokenIssuer tokenIssuer,
            LoginProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.accounts = accounts;
        this.auditLog = auditLog;
        this.tenantContext = tenantContext;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.properties = properties;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.dummyPasswordHash = passwordEncoder.encode(randomSecret());
    }

    public LoginResponse login(LoginRequest request, ClientInfo client) {
        Lookup lookup = readTransaction.execute(status -> lookup(request));

        if (lookup.organizationId() == null) {
            burnPasswordCheck(request.password());
            log.info("Login rejected: reason=UNKNOWN_ORGANIZATION");
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        long orgId = lookup.organizationId();
        LoginAccount account = lookup.account();

        if (account == null) {
            burnPasswordCheck(request.password());
            recordAnonymousFailure(orgId, client);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (account.temporarilyLocked()) {
            // Không kiểm tra mật khẩu thật trong lúc khoá tạm: nếu không, phản hồi khác nhau giữa
            // đúng/sai mật khẩu sẽ biến lockout thành oracle dò mật khẩu.
            burnPasswordCheck(request.password());
            recordUserEvent(orgId, account.id(), Result.FAILURE, "TEMPORARILY_LOCKED", null, client);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (!passwordMatches(request.password(), account.passwordHash())) {
            recordFailedAttempt(orgId, account.id(), client);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (!STATUS_ACTIVE.equals(account.status())) {
            ErrorCode code = STATUS_LOCKED.equals(account.status()) ? ErrorCode.ACCOUNT_LOCKED : ErrorCode.ACCOUNT_DISABLED;
            recordUserEvent(orgId, account.id(), Result.DENIED, code.name(), null, client);
            throw new ApiException(code);
        }

        return writeTransaction.execute(status -> completeLogin(orgId, account, request, client, tokenIssuer));
    }

    private Lookup lookup(LoginRequest request) {
        Optional<Long> orgId = accounts.findActiveOrganizationId(request.orgCode().strip());
        if (orgId.isEmpty()) {
            return new Lookup(null, null);
        }
        // Chỉ chạm tới app_user sau khi đã có org scope hợp lệ trong giao dịch.
        tenantContext.bind(orgId.get());
        return new Lookup(orgId.get(), accounts.findByIdentifier(orgId.get(), request.identifier().strip()).orElse(null));
    }

    private LoginResponse completeLogin(long orgId, LoginAccount account, LoginRequest request,
                                        ClientInfo client, IAuthTokenIssuer issuer) {
        tenantContext.bind(orgId);
        Optional<SuccessfulLogin> updated = accounts.recordSuccessfulLogin(orgId, account.id(), account.passwordHash());
        if (updated.isEmpty()) {
            // Tài khoản vừa bị khoá/vô hiệu hoá/đổi mật khẩu đồng thời: không cấp token.
            log.info("Login rejected: reason=CONCURRENT_STATE_CHANGE, orgId={}, userId={}", orgId, account.id());
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        SuccessfulLogin user = updated.get();
        auditLog.append(userEntry(orgId, user.userId(), Result.SUCCESS, null,
                "{\"clientType\":\"" + request.clientType().name() + "\"}", client));

        IAuthTokenIssuer.IssuedTokens tokens = issuer.issue(
                new IAuthTokenIssuer.TokenSubject(user.userId(), orgId, user.tokenVersion(), user.mustChangePassword()),
                request.clientType(),
                client);

        log.info("Login succeeded: orgId={}, userId={}, clientType={}", orgId, user.userId(), request.clientType());
        return new LoginResponse(
                TOKEN_TYPE,
                tokens.accessToken(),
                tokens.accessTokenExpiresAt(),
                tokens.refreshToken(),
                tokens.refreshTokenExpiresAt(),
                user.mustChangePassword(),
                new LoginResponse.UserSummary(user.userId(), user.displayName()));
    }

    private void recordFailedAttempt(long orgId, long userId, ClientInfo client) {
        FailedAttemptState state = writeTransaction.execute(status -> {
            tenantContext.bind(orgId);
            FailedAttemptState result = accounts.recordFailedAttempt(
                    orgId, userId, properties.maxFailedAttempts(), properties.lockDuration()).orElse(null);
            String reason = result != null && result.locked() ? "LOCKED_OUT" : "BAD_CREDENTIALS";
            String data = result == null ? null
                    : "{\"failedLoginCount\":" + result.failedLoginCount() + ",\"locked\":" + result.locked() + "}";
            auditLog.append(userEntry(orgId, userId, Result.FAILURE, reason, data, client));
            return result;
        });
        log.info("Login rejected: reason=BAD_CREDENTIALS, orgId={}, userId={}, failedCount={}, locked={}",
                orgId, userId,
                state == null ? null : state.failedLoginCount(),
                state != null && state.locked());
    }

    private void recordUserEvent(long orgId, long userId, Result result, String reason, String data, ClientInfo client) {
        writeTransaction.executeWithoutResult(status -> {
            tenantContext.bind(orgId);
            auditLog.append(userEntry(orgId, userId, result, reason, data, client));
        });
        log.info("Login rejected: reason={}, orgId={}, userId={}", reason, orgId, userId);
    }

    private void recordAnonymousFailure(long orgId, ClientInfo client) {
        writeTransaction.executeWithoutResult(status -> {
            tenantContext.bind(orgId);
            auditLog.append(new AuditEntry(orgId, ActorType.SYSTEM, null, AUDIT_ACTION, AUDIT_ENTITY, null,
                    Result.FAILURE, "UNKNOWN_IDENTIFIER", null, client.ipAddress(), client.userAgent(), client.requestId()));
        });
        log.info("Login rejected: reason=UNKNOWN_IDENTIFIER, orgId={}", orgId);
    }

    private static AuditEntry userEntry(long orgId, long userId, Result result, String reason, String data,
                                        ClientInfo client) {
        return AuditEntry.ofUser(orgId, userId, AUDIT_ACTION, result, reason, data,
                client.ipAddress(), client.userAgent(), client.requestId());
    }

    private boolean passwordMatches(String rawPassword, String passwordHash) {
        try {
            return passwordEncoder.matches(rawPassword, passwordHash);
        } catch (IllegalArgumentException malformedHashOrInput) {
            // Hash không có prefix {id} hợp lệ hoặc input vượt giới hạn của thuật toán: coi như sai mật khẩu.
            log.warn("Password verification failed due to unsupported hash or input format");
            return false;
        }
    }

    /** Tiêu tốn thời gian tương đương một lần kiểm tra BCrypt thật để giảm chênh lệch thời gian phản hồi. */
    private void burnPasswordCheck(String rawPassword) {
        passwordMatches(rawPassword, dummyPasswordHash);
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private record Lookup(Long organizationId, LoginAccount account) {
    }
}
