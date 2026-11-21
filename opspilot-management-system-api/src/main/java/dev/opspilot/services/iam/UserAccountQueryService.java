package dev.opspilot.services.iam;

import dev.opspilot.dtos.iam.UserAccountResponse;
import dev.opspilot.enums.ErrorCode;
import dev.opspilot.enums.iam.UserStatus;
import dev.opspilot.exceptions.ApiException;
import dev.opspilot.repositories.iam.UserAccountQueryRepository;
import dev.opspilot.security.authentication.OpsPilotPrincipal;
import dev.opspilot.security.tenant.TenantDatabaseContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;

@Service
public class UserAccountQueryService {

    static final String USER_VIEW = "iam.user.view";

    private final IPolicyService policyService;
    private final UserAccountQueryRepository accounts;
    private final TenantDatabaseContext tenantContext;
    private final TransactionTemplate readTransaction;

    public UserAccountQueryService(IPolicyService policyService, UserAccountQueryRepository accounts,
                                   TenantDatabaseContext tenantContext,
                                   PlatformTransactionManager transactionManager) {
        this.policyService = policyService;
        this.accounts = accounts;
        this.tenantContext = tenantContext;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public Page<UserAccountResponse> find(OpsPilotPrincipal actor, int page, int size, String search,
                                          UserStatus status, Long unitId) {
        Set<Long> visibleUnits = visibleUnits(actor, unitId);
        String normalizedSearch = normalize(search);
        PageRequest pageable = PageRequest.of(page, size);
        Page<UserAccountResponse> result = readTransaction.execute(transactionStatus -> {
            tenantContext.bind(actor.organizationId());
            var found = accounts.find(actor.organizationId(), visibleUnits, normalizedSearch, status, page, size);
            return new PageImpl<>(found.content(), pageable, found.totalElements());
        });
        if (result == null) {
            return Page.empty(pageable);
        }
        return result;
    }

    private Set<Long> visibleUnits(OpsPilotPrincipal actor, Long unitId) {
        if (!policyService.has(actor, USER_VIEW)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        if (unitId != null) {
            if (!policyService.hasOnUnit(actor, USER_VIEW, unitId)) {
                throw new ApiException(ErrorCode.ACCESS_DENIED);
            }
            return Set.of(unitId);
        }
        Set<Long> units = policyService.unitsWithPermission(actor, USER_VIEW);
        if (units.isEmpty()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        return units;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
