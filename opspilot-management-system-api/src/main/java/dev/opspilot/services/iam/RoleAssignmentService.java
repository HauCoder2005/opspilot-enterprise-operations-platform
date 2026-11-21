package dev.opspilot.services.iam;

import dev.opspilot.dtos.iam.AssignRoleRequest;
import dev.opspilot.enums.ErrorCode;
import dev.opspilot.enums.SystemRoleCode;
import dev.opspilot.exceptions.ApiException;
import dev.opspilot.repositories.audit.AuditLogRepository;
import dev.opspilot.repositories.audit.AuditLogRepository.ActorType;
import dev.opspilot.repositories.audit.AuditLogRepository.AuditEntry;
import dev.opspilot.repositories.audit.AuditLogRepository.Result;
import dev.opspilot.repositories.iam.RoleAssignmentRepository;
import dev.opspilot.repositories.iam.RoleAssignmentRepository.AssignedRole;
import dev.opspilot.repositories.iam.RoleAssignmentRepository.RoleDetails;
import dev.opspilot.security.authentication.OpsPilotPrincipal;
import dev.opspilot.security.tenant.TenantDatabaseContext;
import dev.opspilot.services.auth.ClientInfo;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

@Service
public class RoleAssignmentService {

    static final String ROLE_ASSIGN = "iam.role.assign";
    static final String ACTION = "iam.role.assign";

    private final RoleAssignmentRepository assignments;
    private final IPolicyService policyService;
    private final AuditLogRepository auditLog;
    private final TenantDatabaseContext tenantContext;
    private final TransactionTemplate transaction;

    public RoleAssignmentService(RoleAssignmentRepository assignments, IPolicyService policyService,
                                 AuditLogRepository auditLog, TenantDatabaseContext tenantContext,
                                 PlatformTransactionManager transactionManager) {
        this.assignments = assignments;
        this.policyService = policyService;
        this.auditLog = auditLog;
        this.tenantContext = tenantContext;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Assignment assign(OpsPilotPrincipal actor, long targetUserId, AssignRoleRequest request, ClientInfo client) {
        Instant validFrom = request.validFrom() == null ? Instant.now() : request.validFrom();
        if (request.validTo() != null && !request.validTo().isAfter(validFrom)) {
            throw new ApiException(ErrorCode.ROLE_ASSIGNMENT_CONFLICT);
        }
        try {
            Assignment result = transaction.execute(status -> assignInTransaction(
                    actor, targetUserId, request, validFrom, client));
            if (result == null) {
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
            }
            return result;
        } catch (DataIntegrityViolationException conflict) {
            throw new ApiException(ErrorCode.ROLE_ASSIGNMENT_CONFLICT);
        }
    }

    private Assignment assignInTransaction(OpsPilotPrincipal actor, long targetUserId, AssignRoleRequest request,
                                           Instant validFrom, ClientInfo client) {
        long orgId = actor.organizationId();
        tenantContext.bind(orgId);
        if (!assignments.activeUserExists(orgId, targetUserId)) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        }
        RoleDetails role = assignments.findActiveRole(orgId, request.roleId())
                .orElseThrow(() -> new ApiException(ErrorCode.ROLE_NOT_FOUND));
        if (SystemRoleCode.OWNER.name().equals(role.code())) {
            throw new ApiException(ErrorCode.OWNER_ASSIGNMENT_REQUIRES_APPROVAL);
        }
        if (!policyService.hasOnUnit(actor, ROLE_ASSIGN, request.scopeUnitId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        boolean canGrantAllPermissions = role.permissions().stream()
                .allMatch(permission -> policyService.hasOnUnit(actor, permission, request.scopeUnitId()));
        if (!canGrantAllPermissions) {
            throw new ApiException(ErrorCode.PRIVILEGE_ESCALATION_NOT_ALLOWED);
        }
        AssignedRole assigned = assignments.insert(orgId, targetUserId, role.id(), request.scopeUnitId(),
                request.includeDescendants(), validFrom, request.validTo(), normalize(request.note()), actor.userId());
        auditLog.append(new AuditEntry(orgId, ActorType.USER, actor.userId(), ACTION, "role_assignment", assigned.id(),
                Result.SUCCESS, null, "{\"targetUserId\":" + targetUserId + ",\"roleId\":" + role.id() + "}",
                client.ipAddress(), client.userAgent(), client.requestId()));
        return new Assignment(assigned.id(), targetUserId, role.id(), request.scopeUnitId(),
                request.includeDescendants(), assigned.validFrom(), assigned.validTo());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record Assignment(long assignmentId, long userId, long roleId, long scopeUnitId,
                             boolean includeDescendants, Instant validFrom, Instant validTo) {
    }
}
