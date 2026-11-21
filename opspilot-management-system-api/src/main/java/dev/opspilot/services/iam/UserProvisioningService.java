package dev.opspilot.services.iam;

import dev.opspilot.dtos.iam.CreateUserRequest;
import dev.opspilot.enums.ErrorCode;
import dev.opspilot.exceptions.ApiException;
import dev.opspilot.repositories.audit.AuditLogRepository;
import dev.opspilot.repositories.audit.AuditLogRepository.ActorType;
import dev.opspilot.repositories.audit.AuditLogRepository.AuditEntry;
import dev.opspilot.repositories.audit.AuditLogRepository.Result;
import dev.opspilot.repositories.iam.UserProvisioningRepository;
import dev.opspilot.repositories.iam.UserProvisioningRepository.CreatedUser;
import dev.opspilot.repositories.iam.UserProvisioningRepository.EmployeeAccountTarget;
import dev.opspilot.security.authentication.OpsPilotPrincipal;
import dev.opspilot.security.tenant.TenantDatabaseContext;
import dev.opspilot.services.auth.ClientInfo;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserProvisioningService {

    static final String ACTION = "iam.user.create";

    private final UserProvisioningRepository users;
    private final IPolicyService policyService;
    private final TemporaryPasswordGenerator temporaryPasswords;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogRepository auditLog;
    private final TenantDatabaseContext tenantContext;
    private final TransactionTemplate transaction;

    public UserProvisioningService(UserProvisioningRepository users, IPolicyService policyService,
                                   TemporaryPasswordGenerator temporaryPasswords, PasswordEncoder passwordEncoder,
                                   AuditLogRepository auditLog, TenantDatabaseContext tenantContext,
                                   PlatformTransactionManager transactionManager) {
        this.users = users;
        this.policyService = policyService;
        this.temporaryPasswords = temporaryPasswords;
        this.passwordEncoder = passwordEncoder;
        this.auditLog = auditLog;
        this.tenantContext = tenantContext;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public ProvisionedUser create(OpsPilotPrincipal actor, CreateUserRequest request, ClientInfo client) {
        String temporaryPassword = temporaryPasswords.generate();
        String passwordHash = passwordEncoder.encode(temporaryPassword);
        try {
            ProvisionedUser created = transaction.execute(status -> createInTransaction(
                    actor, request, normalize(request.username()), normalize(request.email()),
                    normalize(request.phone()), passwordHash, temporaryPassword, client));
            if (created == null) {
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
            }
            return created;
        } catch (DataIntegrityViolationException conflict) {
            throw new ApiException(ErrorCode.ACCOUNT_IDENTIFIER_CONFLICT);
        }
    }

    private ProvisionedUser createInTransaction(OpsPilotPrincipal actor, CreateUserRequest request, String username,
                                                String email, String phone, String passwordHash,
                                                String temporaryPassword, ClientInfo client) {
        long orgId = actor.organizationId();
        tenantContext.bind(orgId);
        EmployeeAccountTarget employee = users.findEmployeeForUpdate(orgId, request.employeeId())
                .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND));
        if (employee.userId() != null) {
            throw new ApiException(ErrorCode.EMPLOYEE_ALREADY_HAS_ACCOUNT);
        }
        if ("TERMINATED".equals(employee.employmentStatus()) || employee.primaryUnitId() == null) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND);
        }
        if (!policyService.hasOnUnit(actor, UserManagementPolicy.USER_MANAGE, employee.primaryUnitId())) {
            audit(orgId, actor.userId(), null, Result.DENIED, "POLICY_DENIED", client);
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        CreatedUser user = users.insertUser(orgId, username, email, phone, passwordHash,
                employee.displayName(), actor.userId());
        if (!users.linkEmployee(orgId, employee.id(), user.id(), actor.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit(orgId, actor.userId(), user.id(), Result.SUCCESS, null, client);
        return new ProvisionedUser(user.id(), employee.id(), user.username(), temporaryPassword);
    }

    private void audit(long orgId, long actorId, Long userId, Result result, String reason, ClientInfo client) {
        auditLog.append(new AuditEntry(orgId, ActorType.USER, actorId, ACTION, "app_user", userId,
                result, reason, null, client.ipAddress(), client.userAgent(), client.requestId()));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record ProvisionedUser(long userId, long employeeId, String username, String temporaryPassword) {
        @Override
        public String toString() {
            return "ProvisionedUser[userId=" + userId + ", employeeId=" + employeeId + ", username=" + username
                    + ", temporaryPassword=***]";
        }
    }
}
