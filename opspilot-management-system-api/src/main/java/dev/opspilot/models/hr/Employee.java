package dev.opspilot.models.hr;

import dev.opspilot.enums.hr.EmploymentStatus;
import dev.opspilot.enums.hr.Gender;
import dev.opspilot.models.common.TenantAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Bảng {@code employee} — hồ sơ nhân sự. Liên kết tuỳ chọn tới tài khoản ({@code user_id}, mỗi tài khoản tối đa một
 * nhân viên); có nhân viên chưa được cấp tài khoản (KAN-89 tạo tài khoản từ nhân viên).
 */
@Entity
@Table(name = "employee")
public class Employee extends TenantAuditableEntity {

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "employee_code", nullable = false, length = 30)
    private String employeeCode;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 10)
    private Gender gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "termination_date")
    private LocalDate terminationDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_status", nullable = false, length = 20)
    private EmploymentStatus employmentStatus = EmploymentStatus.ACTIVE;

    /** Quản lý trực tiếp (cùng doanh nghiệp, không phải chính mình). */
    @Column(name = "manager_employee_id")
    private Long managerEmployeeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private String metadata = "{}";

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Employee() {
    }

    public Employee(long organizationId, String employeeCode, String fullName) {
        super(organizationId);
        this.employeeCode = employeeCode;
        this.fullName = fullName;
    }

    public Long getUserId() { return userId; }
    /** Gắn tài khoản: phải cùng doanh nghiệp (FK 2 cột) và chưa gắn với nhân viên khác (UNIQUE). */
    public void linkUser(Long userId) { this.userId = userId; }
    public String getEmployeeCode() { return employeeCode; }
    public void setEmployeeCode(String employeeCode) { this.employeeCode = employeeCode; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public Gender getGender() { return gender; }
    public void setGender(Gender gender) { this.gender = gender; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public LocalDate getHireDate() { return hireDate; }
    public void setHireDate(LocalDate hireDate) { this.hireDate = hireDate; }
    public LocalDate getTerminationDate() { return terminationDate; }
    public EmploymentStatus getEmploymentStatus() { return employmentStatus; }
    public void setEmploymentStatus(EmploymentStatus employmentStatus) { this.employmentStatus = employmentStatus; }
    public Long getManagerEmployeeId() { return managerEmployeeId; }
    public void setManagerEmployeeId(Long managerEmployeeId) { this.managerEmployeeId = managerEmployeeId; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public Instant getDeletedAt() { return deletedAt; }
    public void markDeleted(Instant at) { this.deletedAt = at; }

    /**
     * Cho nghỉ việc. Việc khoá tài khoản liên kết, thu hồi phiên và vai trò là nghiệp vụ ở tầng service
     * (OPEN DECISION: chính sách xử lý quyền khi nghỉ việc).
     */
    public void terminate(LocalDate date) {
        if (hireDate != null && date.isBefore(hireDate)) {
            throw new IllegalArgumentException("Ngày nghỉ việc không được trước ngày vào làm");
        }
        this.terminationDate = date;
        this.employmentStatus = EmploymentStatus.TERMINATED;
    }

    @Override
    public String toString() {
        return "Employee[id=" + getId() + ", organizationId=" + getOrganizationId() + ", employeeCode=" + employeeCode + "]";
    }
}
