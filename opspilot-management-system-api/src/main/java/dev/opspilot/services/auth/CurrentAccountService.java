package dev.opspilot.services.auth;

import dev.opspilot.dtos.responses.CurrentUserResponse;
import dev.opspilot.enums.ErrorCode;
import dev.opspilot.exceptions.ApiException;
import dev.opspilot.repositories.auth.UserCredentialRepository;
import dev.opspilot.security.OpsPilotPrincipal;
import dev.opspilot.security.tenant.TenantDatabaseContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Thông tin tài khoản của người dùng đang đăng nhập; tenant lấy từ principal đã xác minh, không từ client. */
@Service
public class CurrentAccountService {

    private final UserCredentialRepository users;
    private final TenantDatabaseContext tenantContext;
    private final TransactionTemplate readTransaction;

    public CurrentAccountService(UserCredentialRepository users, TenantDatabaseContext tenantContext,
                                 PlatformTransactionManager transactionManager) {
        this.users = users;
        this.tenantContext = tenantContext;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public CurrentUserResponse describe(OpsPilotPrincipal principal) {
        return readTransaction.execute(status -> {
            tenantContext.bind(principal.organizationId());
            return users.findSessionUser(principal.organizationId(), principal.userId())
                    .map(user -> new CurrentUserResponse(user.id(), principal.organizationId(), user.username(),
                            user.displayName(), user.mustChangePassword()))
                    .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        });
    }
}
