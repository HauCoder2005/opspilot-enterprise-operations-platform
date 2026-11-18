package dev.opspilot.models.iam;

import dev.opspilot.enums.ClientType;
import dev.opspilot.enums.iam.RefreshTokenRevokeReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * Bảng {@code refresh_token} — chỉ lưu SHA-256 của token. Entity CHỈ ĐỌC (tra cứu/hiển thị phiên): phát hành, xoay vòng
 * và thu hồi phải đi qua {@code RefreshTokenService}/{@code RefreshTokenRepository} để có khoá dòng, phát hiện dùng lại
 * và audit đúng giao dịch.
 */
@Entity
@Immutable
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64, columnDefinition = "bpchar(64)")
    private String tokenHash;

    /** Id token đầu tiên của lần đăng nhập; các token xoay vòng dùng chung. */
    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Column(name = "replaced_by_id")
    private Long replacedById;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 30)
    private RefreshTokenRevokeReason revokeReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", nullable = false, length = 20)
    private ClientType clientType;

    @Column(name = "device_name", length = 100)
    private String deviceName;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /** Kiểu INET, đọc dưới dạng chuỗi. */
    @Column(name = "ip_address", columnDefinition = "inet")
    private String ipAddress;

    protected RefreshToken() {
    }

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public Long getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public Long getFamilyId() { return familyId; }
    public Long getReplacedById() { return replacedById; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public RefreshTokenRevokeReason getRevokeReason() { return revokeReason; }
    public ClientType getClientType() { return clientType; }
    public String getDeviceName() { return deviceName; }
    public String getUserAgent() { return userAgent; }
    public String getIpAddress() { return ipAddress; }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    @Override
    public String toString() {
        return "RefreshToken[id=" + id + ", userId=" + userId + ", familyId=" + familyId + ", tokenHash=***]";
    }
}
