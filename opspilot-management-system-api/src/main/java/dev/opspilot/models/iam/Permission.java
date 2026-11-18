package dev.opspilot.models.iam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * Bảng {@code permission} — danh mục quyền dạng {@code feature.resource.action} (vd {@code iam.user.manage}),
 * do migration seed. Doanh nghiệp chỉ gom quyền vào vai trò, không tạo quyền mới. Runtime chỉ đọc.
 */
@Entity
@Immutable
@Table(name = "permission")
public class Permission {

    @Id
    private Long id;

    @Column(name = "feature_id", nullable = false)
    private Long featureId;

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /** Quyền phân quyền hoặc tài chính; giao diện cảnh báo khi gán. */
    @Column(name = "is_sensitive", nullable = false)
    private boolean sensitive;

    protected Permission() {
    }

    public Long getId() { return id; }
    public Long getFeatureId() { return featureId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isSensitive() { return sensitive; }
}
