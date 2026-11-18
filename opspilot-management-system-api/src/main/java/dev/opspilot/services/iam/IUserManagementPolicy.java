package dev.opspilot.services.iam;

import dev.opspilot.security.OpsPilotPrincipal;

/**
 * Cổng phân quyền quản trị tài khoản, do policy service của KAN-81 cung cấp implementation.
 *
 * <p>Implementation PHẢI kiểm tra đủ: actor có permission {@code iam.user.manage} qua role_assignment còn hiệu lực,
 * tại đơn vị bao phủ người dùng đích (unit scope), cùng doanh nghiệp, và các quy tắc nghiệp vụ chống leo thang
 * quyền (vd không cho tác động tới OWNER nếu actor không đủ thẩm quyền). Không được quyết định theo tên role hay URL.
 *
 * <p>Chưa có implementation production: endpoint dùng cổng này fail-closed với {@code 503 POLICY_SERVICE_UNAVAILABLE}.
 */
public interface IUserManagementPolicy {

    /** @return TRUE nếu actor được quản lý (reset mật khẩu, khoá...) tài khoản {@code targetUserId} */
    boolean canManageUser(OpsPilotPrincipal actor, long targetUserId);
}
