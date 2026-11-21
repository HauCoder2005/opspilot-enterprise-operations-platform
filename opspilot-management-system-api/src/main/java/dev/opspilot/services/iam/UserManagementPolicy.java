package dev.opspilot.services.iam;

import dev.opspilot.repositories.iam.PolicyRepository;
import dev.opspilot.security.authentication.OpsPilotPrincipal;
import dev.opspilot.security.tenant.TenantDatabaseContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserManagementPolicy implements IUserManagementPolicy {

    static final String USER_MANAGE = "iam.user.manage";

    private final IPolicyService policyService;
    private final PolicyRepository policies;
    private final TenantDatabaseContext tenantContext;
    private final TransactionTemplate readTransaction;

    public UserManagementPolicy(IPolicyService policyService, PolicyRepository policies,
                                TenantDatabaseContext tenantContext, PlatformTransactionManager transactionManager) {
        this.policyService = policyService;
        this.policies = policies;
        this.tenantContext = tenantContext;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public boolean canManageUser(OpsPilotPrincipal actor, long targetUserId) {
        Long targetUnitId = readTransaction.execute(status -> {
            tenantContext.bind(actor.organizationId());
            return policies.findPrimaryUnitForUser(actor.organizationId(), targetUserId).orElse(null);
        });
        return targetUnitId != null && policyService.hasOnUnit(actor, USER_MANAGE, targetUnitId);
    }
}
