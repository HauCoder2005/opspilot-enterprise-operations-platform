package dev.opspilot.models.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

/**
 * Entity thuộc một doanh nghiệp (tenant). {@code organization_id} không đổi sau khi tạo; RLS chỉ cho thấy/ghi dòng của
 * tenant đã gắn vào giao dịch ({@code TenantDatabaseContext}). Giá trị phải lấy từ principal đã xác minh, không từ client.
 */
@MappedSuperclass
public abstract class TenantAuditableEntity extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    protected TenantAuditableEntity() {
    }

    protected TenantAuditableEntity(long organizationId) {
        this.organizationId = organizationId;
    }

    public Long getOrganizationId() {
        return organizationId;
    }
}
