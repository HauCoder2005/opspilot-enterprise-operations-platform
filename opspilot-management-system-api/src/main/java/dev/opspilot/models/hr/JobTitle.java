package dev.opspilot.models.hr;

import dev.opspilot.enums.ActiveStatus;
import dev.opspilot.models.common.TenantAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Bảng {@code job_title} — chức danh nhân sự (Thu ngân, Thủ kho...). KHÔNG quyết định quyền hệ thống: quyền chỉ đến từ
 * {@code role_assignment}.
 */
@Entity
@Table(name = "job_title")
public class JobTitle extends TenantAuditableEntity {

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ActiveStatus status = ActiveStatus.ACTIVE;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected JobTitle() {
    }

    public JobTitle(long organizationId, String code, String name) {
        super(organizationId);
        this.code = code;
        this.name = name;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public ActiveStatus getStatus() { return status; }
    public void setStatus(ActiveStatus status) { this.status = status; }
    public Instant getDeletedAt() { return deletedAt; }
    public void markDeleted(Instant at) { this.deletedAt = at; }
}
