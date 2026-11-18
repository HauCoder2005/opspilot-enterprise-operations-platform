package dev.opspilot.models.iam;

import dev.opspilot.models.common.TenantAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Bảng {@code role_assignment} — ai có vai trò gì, tại đơn vị nào, trong thời gian nào. Quyền tại đơn vị X = quyền của
 * vai trò gán tại X, cộng vai trò gán tại tổ tiên của X có {@code include_descendants = TRUE}.
 *
 * <p>Đang hiệu lực khi {@code revoked_at IS NULL AND valid_from <= now() < valid_to} (valid_to NULL = vô hạn).
 * Thu hồi bằng {@link #revoke} — không xoá dòng, để giữ lịch sử. DB chặn hai lần gán chồng thời gian (exclusion).
 */
@Entity
@Table(name = "role_assignment")
public class RoleAssignment extends TenantAuditableEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "role_id", nullable = false, updatable = false)
    private Long roleId;

    @Column(name = "scope_unit_id", nullable = false, updatable = false)
    private Long scopeUnitId;

    @Column(name = "include_descendants", nullable = false, updatable = false)
    private boolean includeDescendants;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "assigned_by", updatable = false)
    private Long assignedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "note", length = 500)
    private String note;

    protected RoleAssignment() {
    }

    public RoleAssignment(long organizationId, long userId, long roleId, long scopeUnitId, boolean includeDescendants,
                          Instant validFrom, Instant validTo, Long assignedBy) {
        super(organizationId);
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("valid_to phải sau valid_from");
        }
        this.userId = userId;
        this.roleId = roleId;
        this.scopeUnitId = scopeUnitId;
        this.includeDescendants = includeDescendants;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.assignedBy = assignedBy;
    }

    public Long getUserId() { return userId; }
    public Long getRoleId() { return roleId; }
    public Long getScopeUnitId() { return scopeUnitId; }
    public boolean isIncludeDescendants() { return includeDescendants; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidTo() { return validTo; }
    public Long getAssignedBy() { return assignedBy; }
    public Instant getRevokedAt() { return revokedAt; }
    public Long getRevokedBy() { return revokedBy; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public boolean isEffectiveAt(Instant at) {
        return revokedAt == null && !validFrom.isAfter(at) && (validTo == null || validTo.isAfter(at));
    }

    /** Thu hồi một lần; service phải tăng token_version của user sau khi thu hồi. */
    public void revoke(Instant at, Long by) {
        if (revokedAt != null) {
            throw new IllegalStateException("Lần gán vai trò đã bị thu hồi");
        }
        this.revokedAt = at;
        this.revokedBy = by;
    }
}
