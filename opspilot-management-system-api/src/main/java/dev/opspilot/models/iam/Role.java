package dev.opspilot.models.iam;

import dev.opspilot.enums.ActiveStatus;
import dev.opspilot.models.common.TenantAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Bảng {@code role} — vai trò của doanh nghiệp (tập quyền có tên). Chưa gắn đơn vị: đơn vị chỉ định lúc gán ở
 * {@link RoleAssignment}. Vai trò hệ thống ({@code is_system}) do {@code seed_system_roles} tạo, không xoá được (CHECK).
 */
@Entity
@Table(name = "role")
public class Role extends TenantAuditableEntity {

    /** Vd BRANCH_MANAGER; unique trong doanh nghiệp, không phân biệt hoa thường. */
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "is_system", nullable = false, updatable = false)
    private boolean system;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ActiveStatus status = ActiveStatus.ACTIVE;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Role() {
    }

    /** Vai trò tự tạo (IAM-11) luôn {@code is_system = FALSE}. */
    public Role(long organizationId, String code, String name) {
        super(organizationId);
        this.code = code;
        this.name = name;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isSystem() { return system; }
    public ActiveStatus getStatus() { return status; }
    public void setStatus(ActiveStatus status) { this.status = status; }
    public Instant getDeletedAt() { return deletedAt; }

    /** Vai trò hệ thống không được xoá (DB cũng chặn bằng {@code ck_role_system_not_deleted}). */
    public void markDeleted(Instant at) {
        if (system) {
            throw new IllegalStateException("Không thể xoá vai trò hệ thống");
        }
        this.deletedAt = at;
    }
}
