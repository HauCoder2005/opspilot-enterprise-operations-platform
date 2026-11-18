package dev.opspilot.security.tenant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Gắn tenant vào giao dịch PostgreSQL hiện hành để các policy RLS ({@code app_current_org()}) có hiệu lực.
 *
 * <p>Dùng {@code set_config('opspilot.org_id', ..., true)}: giá trị chỉ sống trong giao dịch hiện tại và tự
 * mất khi commit/rollback, nên connection trả về pool không mang tenant cũ. Bắt buộc gọi bên trong giao dịch.
 *
 * <p>Giá trị org id phải đến từ nguồn đã được xác minh (hàm {@code find_organization_id} khi đăng nhập,
 * hoặc JWT đã xác minh ở KAN-76), không bao giờ lấy trực tiếp từ dữ liệu client.
 */
@Component
public class TenantDatabaseContext {

    private final JdbcClient jdbcClient;

    public TenantDatabaseContext(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void bind(long organizationId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Tenant context phải được đặt bên trong một giao dịch đang hoạt động");
        }
        jdbcClient.sql("SELECT set_config('opspilot.org_id', :orgId, true)")
                .param("orgId", Long.toString(organizationId))
                .query(String.class)
                .single();
    }
}
