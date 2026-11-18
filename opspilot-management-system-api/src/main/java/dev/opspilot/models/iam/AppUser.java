package dev.opspilot.models.iam;

import dev.opspilot.enums.iam.UserStatus;
import dev.opspilot.models.common.TenantAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/**
 * Bảng {@code app_user} — tài khoản đăng nhập (mã doanh nghiệp + username/email/phone). Không chứa hồ sơ nhân sự.
 *
 * <p><b>Cột bảo mật chỉ ghi khi tạo</b> ({@code updatable = false}): password_hash, must_change_password,
 * token_version, failed_login_count, locked_until, last_login_at, password_changed_at. Sau khi tạo, các cột này chỉ
 * được đổi bằng SQL nguyên tử trong {@code LoginAccountRepository}/{@code UserCredentialRepository} (đăng nhập, đổi/
 * reset mật khẩu, thu hồi phiên), để việc lưu entity bằng JPA không ghi đè bộ đếm hoặc token_version.
 * {@link DynamicUpdate} chỉ ghi các cột thực sự thay đổi.
 */
@Entity
@DynamicUpdate
@Table(name = "app_user")
public class AppUser extends TenantAuditableEntity {

    /** Unique trong doanh nghiệp, không phân biệt hoa thường. */
    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "email", length = 255)
    private String email;

    /** Chuẩn hoá dạng +84...; unique trong doanh nghiệp nếu có. */
    @Column(name = "phone", length = 20)
    private String phone;

    /** Hash DelegatingPasswordEncoder ({@code {bcrypt}...}); không bao giờ trả ra API hoặc log. */
    @Column(name = "password_hash", nullable = false, length = 255, updatable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "must_change_password", nullable = false, updatable = false)
    private boolean mustChangePassword = true;

    @Column(name = "token_version", nullable = false, updatable = false)
    private int tokenVersion;

    @Column(name = "failed_login_count", nullable = false, updatable = false)
    private short failedLoginCount;

    @Column(name = "locked_until", updatable = false)
    private Instant lockedUntil;

    @Column(name = "last_login_at", updatable = false)
    private Instant lastLoginAt;

    @Column(name = "password_changed_at", updatable = false)
    private Instant passwordChangedAt;

    @Column(name = "locale", length = 10)
    private String locale;

    @Column(name = "timezone", length = 50)
    private String timezone;

    /** Chừa sẵn cho MFA (IAM-14, P1). */
    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected AppUser() {
    }

    /** Tài khoản mới luôn bắt đổi mật khẩu ở lần đăng nhập đầu. */
    public AppUser(long organizationId, String username, String passwordHash, String displayName) {
        super(organizationId);
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public UserStatus getStatus() { return status; }
    /** Khoá/vô hiệu hoá phải đi kèm tăng token_version và thu hồi refresh token (IAM-04) ở tầng service. */
    public void setStatus(UserStatus status) { this.status = status; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public int getTokenVersion() { return tokenVersion; }
    public short getFailedLoginCount() { return failedLoginCount; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public boolean isMfaEnabled() { return mfaEnabled; }
    public Instant getDeletedAt() { return deletedAt; }
    public void markDeleted(Instant at) { this.deletedAt = at; }

    /** Không đưa password hash vào log. */
    @Override
    public String toString() {
        return "AppUser[id=" + getId() + ", organizationId=" + getOrganizationId() + ", username=" + username
                + ", status=" + status + "]";
    }
}
