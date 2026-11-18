package dev.opspilot.models.iam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * Bảng {@code role_permission} — vai trò gồm quyền nào. Gán/gỡ = thêm/xoá dòng (runtime có quyền DELETE);
 * mọi thay đổi phải tăng token_version của user đang giữ vai trò đó (tầng service, KAN-87).
 */
@Entity
@Immutable
@Table(name = "role_permission")
public class RolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "permission_id", nullable = false)
    private Long permissionId;

    /** Mặc định DB {@code now()}, đọc lại sau insert. */
    @Generated(event = EventType.INSERT)
    @Column(name = "granted_at", insertable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "granted_by")
    private Long grantedBy;

    protected RolePermission() {
    }

    public RolePermission(long organizationId, long roleId, long permissionId, Long grantedBy) {
        this.organizationId = organizationId;
        this.roleId = roleId;
        this.permissionId = permissionId;
        this.grantedBy = grantedBy;
    }

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public Long getRoleId() { return roleId; }
    public Long getPermissionId() { return permissionId; }
    public Instant getGrantedAt() { return grantedAt; }
    public Long getGrantedBy() { return grantedBy; }
}
