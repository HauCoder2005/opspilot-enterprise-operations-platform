package dev.opspilot.models.hr;

import dev.opspilot.models.common.TenantAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * Bảng {@code employee_assignment} — nhân viên làm việc tại đơn vị nào, chức danh gì, từ khi nào. Mỗi nhân viên tối đa
 * một nơi làm việc chính đang hiệu lực (unique index). KHÔNG cấp quyền: quyền chỉ đến từ {@code role_assignment}.
 */
@Entity
@Table(name = "employee_assignment")
public class EmployeeAssignment extends TenantAuditableEntity {

    @Column(name = "employee_id", nullable = false, updatable = false)
    private Long employeeId;

    @Column(name = "org_unit_id", nullable = false)
    private Long orgUnitId;

    @Column(name = "job_title_id")
    private Long jobTitleId;

    @Column(name = "is_primary", nullable = false)
    private boolean primary = true;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "note", length = 500)
    private String note;

    protected EmployeeAssignment() {
    }

    public EmployeeAssignment(long organizationId, long employeeId, long orgUnitId, Long jobTitleId,
                              boolean primary, LocalDate startDate) {
        super(organizationId);
        this.employeeId = employeeId;
        this.orgUnitId = orgUnitId;
        this.jobTitleId = jobTitleId;
        this.primary = primary;
        this.startDate = startDate;
    }

    public Long getEmployeeId() { return employeeId; }
    public Long getOrgUnitId() { return orgUnitId; }
    public void setOrgUnitId(Long orgUnitId) { this.orgUnitId = orgUnitId; }
    public Long getJobTitleId() { return jobTitleId; }
    public void setJobTitleId(Long jobTitleId) { this.jobTitleId = jobTitleId; }
    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public boolean isActiveOn(LocalDate date) {
        return !startDate.isAfter(date) && (endDate == null || !endDate.isBefore(date));
    }

    public void end(LocalDate date) {
        if (date.isBefore(startDate)) {
            throw new IllegalArgumentException("Ngày kết thúc không được trước ngày bắt đầu");
        }
        this.endDate = date;
    }
}
