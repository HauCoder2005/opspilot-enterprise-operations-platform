package dev.opspilot.services.iam;

import dev.opspilot.repositories.iam.PolicyRepository;
import dev.opspilot.security.authentication.OpsPilotPrincipal;
import dev.opspilot.security.tenant.TenantDatabaseContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;

@Service
public class PolicyService implements IPolicyService {

    private final PolicyRepository policies;
    private final TenantDatabaseContext tenantContext;
    private final TransactionTemplate readTransaction;

    public PolicyService(PolicyRepository policies, TenantDatabaseContext tenantContext,
                         PlatformTransactionManager transactionManager) {
        this.policies = policies;
        this.tenantContext = tenantContext;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public boolean has(OpsPilotPrincipal actor, String permission) {
        return Boolean.TRUE.equals(readTransaction.execute(status -> {
            tenantContext.bind(actor.organizationId());
            return policies.has(actor.organizationId(), actor.userId(), permission);
        }));
    }

    @Override
    public boolean hasOnUnit(OpsPilotPrincipal actor, String permission, long unitId) {
        return Boolean.TRUE.equals(readTransaction.execute(status -> {
            tenantContext.bind(actor.organizationId());
            return policies.hasOnUnit(actor.organizationId(), actor.userId(), permission, unitId);
        }));
    }

    @Override
    public Set<Long> unitsWithPermission(OpsPilotPrincipal actor, String permission) {
        Set<Long> units = readTransaction.execute(status -> {
            tenantContext.bind(actor.organizationId());
            return policies.unitsWithPermission(actor.organizationId(), actor.userId(), permission);
        });
        return units == null ? Set.of() : Set.copyOf(units);
    }
}
