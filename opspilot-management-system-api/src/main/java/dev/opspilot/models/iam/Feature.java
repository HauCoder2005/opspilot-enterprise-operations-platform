package dev.opspilot.models.iam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Bảng {@code feature} — danh mục tính năng do migration seed, giống nhau cho mọi tenant. Runtime chỉ đọc. */
@Entity
@Immutable
@Table(name = "feature")
public class Feature {

    @Id
    private Long id;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /** TRUE: luôn bật, không tắt được. */
    @Column(name = "is_core", nullable = false)
    private boolean core;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected Feature() {
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isCore() { return core; }
    public int getSortOrder() { return sortOrder; }
}
