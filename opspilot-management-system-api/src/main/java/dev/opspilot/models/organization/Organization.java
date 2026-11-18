package dev.opspilot.models.organization;

import dev.opspilot.enums.organization.OrganizationStatus;
import dev.opspilot.models.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Bảng {@code organization} — doanh nghiệp, đồng thời là tenant. RLS: chỉ thấy dòng của tenant hiện hành
 * ({@code id = app_current_org()}). Runtime {@code opspilot_app} chỉ có SELECT/UPDATE; tạo tenant do onboarding (KAN-95).
 */
@Entity
@Table(name = "organization")
public class Organization extends AuditableEntity {

    /** Mã nhập khi đăng nhập; unique không phân biệt hoa thường. */
    @Column(name = "code", nullable = false, length = 30, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "legal_name", length = 255)
    private String legalName;

    @Column(name = "tax_code", length = 20)
    private String taxCode;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone = "Asia/Ho_Chi_Minh";

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "bpchar(3)")
    private String currency = "VND";

    @Column(name = "locale", nullable = false, length = 10)
    private String locale = "vi-VN";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrganizationStatus status = OrganizationStatus.ACTIVE;

    /** JSON cấu hình riêng của doanh nghiệp (MOD-05). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", nullable = false, columnDefinition = "jsonb")
    private String settings = "{}";

    protected Organization() {
    }

    public Organization(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }
    public String getTaxCode() { return taxCode; }
    public void setTaxCode(String taxCode) { this.taxCode = taxCode; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public OrganizationStatus getStatus() { return status; }
    public void setStatus(OrganizationStatus status) { this.status = status; }
    public String getSettings() { return settings; }
    public void setSettings(String settings) { this.settings = settings; }
}
