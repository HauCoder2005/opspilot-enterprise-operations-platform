package dev.opspilot.models.organization;

import dev.opspilot.enums.ActiveStatus;
import dev.opspilot.enums.organization.OrgUnitType;
import dev.opspilot.models.common.TenantAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Bảng {@code org_unit} — cây đơn vị (công ty → khu vực → chi nhánh → nhóm), dùng làm phạm vi khi gán vai trò.
 *
 * <p>{@code path} (ltree, vd {@code 1.3.5}) và {@code depth} do trigger tính: JPA không ghi, chỉ đọc lại sau
 * insert/update. Đổi {@code parentId} sẽ khiến trigger dời cả cây con. Đơn vị cha phải cùng doanh nghiệp (FK 2 cột).
 */
@Entity
@Table(name = "org_unit")
public class OrgUnit extends TenantAuditableEntity {

    /** NULL chỉ ở đơn vị gốc; mỗi doanh nghiệp đúng một gốc. */
    @Column(name = "parent_id")
    private Long parentId;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "path", insertable = false, updatable = false, columnDefinition = "ltree")
    private String path;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "depth", insertable = false, updatable = false)
    private Short depth;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_type", nullable = false, length = 30)
    private OrgUnitType unitType;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "address", length = 500)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ActiveStatus status = ActiveStatus.ACTIVE;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private String metadata = "{}";

    /** Xoá mềm. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected OrgUnit() {
    }

    public OrgUnit(long organizationId, Long parentId, OrgUnitType unitType, String code, String name) {
        super(organizationId);
        this.parentId = parentId;
        this.unitType = unitType;
        this.code = code;
        this.name = name;
    }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getPath() { return path; }
    public Short getDepth() { return depth; }
    public OrgUnitType getUnitType() { return unitType; }
    public void setUnitType(OrgUnitType unitType) { this.unitType = unitType; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public ActiveStatus getStatus() { return status; }
    public void setStatus(ActiveStatus status) { this.status = status; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public Instant getDeletedAt() { return deletedAt; }
    public void markDeleted(Instant at) { this.deletedAt = at; }
}
