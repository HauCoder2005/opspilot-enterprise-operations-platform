package dev.opspilot.security.authentication;

import java.security.Principal;

/**
 * Danh tính đã xác thực của một request, dựng từ JWT ĐÃ XÁC MINH chữ ký/thời hạn VÀ đã đối chiếu với
 * {@code app_user} trong đúng tenant (trạng thái ACTIVE, {@code token_version} khớp).
 *
 * <p>Không chứa permission: quyền theo đơn vị do policy service (KAN-81) quyết định ở từng use case.
 */
public record OpsPilotPrincipal(
        long userId,
        long organizationId,
        int tokenVersion,
        boolean mustChangePassword,
        String tokenId
) implements Principal {

    @Override
    public String getName() {
        return Long.toString(userId);
    }
}
