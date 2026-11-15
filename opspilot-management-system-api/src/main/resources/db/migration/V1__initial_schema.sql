-- ========================= 2 / 3: V1–V14 SCHEMA ===============================
-- Drop superuser privileges for all schema objects; they will be OWNED by
-- opspilot_owner. PostgreSQL superuser may SET ROLE to this freshly created role.
SET ROLE opspilot_owner;
-- Partition boundaries in the approved database are calendar-month boundaries
-- in the business timezone. Make their initial creation independent of the
-- PostgreSQL host/container default timezone.
SET TIME ZONE 'Asia/Ho_Chi_Minh';

-- =============================================================================
-- OpsPilot — Toàn bộ schema PostgreSQL, gộp V1 đến V14 (một file)
-- Ngày: 14/11/2026 · 77 bảng trong file này + doc_chunk (hoãn, file riêng) = 78 bảng thiết kế
--
--   V1  organization       organization, org_unit                                     (2)
--   V2  identity_access    feature, permission, app_user, refresh_token, role,
--                          role_permission, role_assignment                           (7)
--   V3  workforce          job_title, employee, employee_assignment                   (3)
--   V4  audit              audit_log [partition tháng] + ensure_monthly_partitions,
--                          default_partition_row_count                                (1)
--   V5  catalog            product_category, unit_of_measure, product, product_price,
--                          payment_method, customer, supplier                         (7)
--   V6  inventory          stock_balance, product_batch, inventory_movement,
--                          stock_count, stock_count_item, stock_transfer,
--                          stock_transfer_item                                        (7)
--   V7  sales              promotion, sales_order, order_status_history,
--                          sales_order_item, sales_return, sales_return_item, payment (7)
--   V8  platform_support   document_sequence, idempotency_record, organization_feature,
--                          stored_file, notification [partition tháng]                (5)
--   V9  task               task_recurrence, task, task_status_history, task_comment,
--                          task_checklist_item                                        (5)
--   V10 data_report        import_column_mapping, import_job, import_row_error,
--                          export_job, saved_view, metric_definition, report_schedule (7)
--   V11 operations         shift_template, shift_assignment, shift_handover,
--                          attendance_record, expense_category, approval_request,
--                          approval_step, expense, budget, appointment, service_order (11)
--   V12 saas_integration   platform_user, subscription_plan, organization_subscription,
--                          integration_connection, sync_job                           (5)
--   V13 ai_ml              ml_dataset, ml_model, ai_conversation, ai_message,
--                          ai_tool_call, ai_action_request, forecast_run,
--                          forecast_value [partition tháng], anomaly_alert, ai_eval_case (10)
--   V14 tenant_rls         RLS + least-privilege ACL (app, job, etl)                (0)
--
-- Extension: ltree, btree_gist (có trong PostgreSQL chuẩn). KHÔNG cần pgvector.
-- Chạy db/roles_setup.sql trước bằng superuser TRÊN DATABASE RIÊNG của OpsPilot.
-- Script tạo role nhưng KHÔNG đặt password (cấp bằng secret manager ngoài Git).
-- Sau đó chạy schema bằng role opspilot_owner (không cần superuser):
--   psql -U opspilot_owner -d opspilot -v ON_ERROR_STOP=1 -f OpsPilot_schema_V1-V14.sql
-- Ứng dụng kết nối bằng opspilot_app, đặt opspilot.org_id BÊN TRONG mỗi giao
--   dịch và gọi rls_session_check() lúc khởi động. Job nền dùng opspilot_job.
-- ETL dùng opspilot_etl: chỉ được SELECT các cột rõ ràng trong whitelist.
-- Flyway: có thể đặt tên V1__init_schema.sql thay cho 14 file riêng (không giữ cả hai),
--   chỉ khi chưa có môi trường nào đã chạy migration cũ.
-- Trạng thái: V1–V8 bán hàng – kho theo tài liệu quy tắc nghiệp vụ; V9–V13 và bảng
--   mới trong V5–V8 là BẢN NHÁP. Đã chạy thử trên PostgreSQL 16.15 và 18.7.
-- =============================================================================


-- Preflight: ONLY for a fresh database. Do not replace already-applied Flyway
-- migrations with this merged file: write a new versioned ALTER migration instead.
DO $$
BEGIN
    IF current_user <> 'opspilot_owner' THEN
        RAISE EXCEPTION 'Chạy migration bằng opspilot_owner, không phải %', current_user;
    END IF;
    IF to_regclass('public.organization') IS NOT NULL THEN
        RAISE EXCEPTION 'OpsPilot schema đã tồn tại: không chạy lại init SQL; dùng migration mới';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opspilot_app')
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opspilot_job')
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opspilot_etl') THEN
        RAISE EXCEPTION 'Chạy roles_setup.sql bằng superuser trước khi chạy schema';
    END IF;
END;
$$;

-- ############################################################################
-- ## V1__organization.sql
-- ############################################################################

-- =============================================================================
-- V1__organization.sql — Organization Foundation
-- Bảng: organization, org_unit
--
-- Quy ước chung cho mọi migration của OpsPilot:
--   * Khoá chính: BIGINT GENERATED BY DEFAULT AS IDENTITY (số nguyên tự tăng)
--   * Bảng có tenant mang organization_id và UNIQUE (organization_id, id), để bảng
--     khác tham chiếu bằng khoá ngoại 2 cột (organization_id, x_id). Nhờ vậy DB
--     từ chối mọi tham chiếu sang doanh nghiệp khác.
--   * Cột chung: created_at, created_by, updated_at, updated_by, version.
--     updated_at/updated_by do JPA Auditing (@LastModifiedDate/@LastModifiedBy) ghi;
--     SQL viết tay phải tự cập nhật hai cột này.
--   * created_by/updated_by chỉ lưu id user, không đặt khoá ngoại.
--   * deleted_at chỉ ở bảng danh mục (xoá mềm).
--   * Trạng thái/loại: VARCHAR + CHECK.
--   * Index khoá ngoại khi có truy vấn theo cột đó, hoặc khi bảng cha có thể bị
--     xoá cứng. Bảng chỉ xoá mềm (app_user, role...) không cần index cho mọi cột *_by.
-- =============================================================================

-- ltree: kiểu dữ liệu đường dẫn cây (vd '1.3.5'), dùng cho org_unit.path
CREATE EXTENSION IF NOT EXISTS ltree;


-- -----------------------------------------------------------------------------
-- organization — Doanh nghiệp (tenant)
--
-- Vai trò: gốc của toàn bộ dữ liệu. Mỗi khách hàng dùng OpsPilot là một dòng ở
--   đây; mọi bảng nghiệp vụ khác đều mang organization_id trỏ về bảng này, nên
--   dữ liệu của doanh nghiệp A không bao giờ lẫn sang doanh nghiệp B.
-- Dùng khi: đăng nhập (người dùng nhập mã doanh nghiệp), tính ranh giới ngày/tháng
--   của kỳ báo cáo theo timezone, hiển thị tiền tệ và ngôn ngữ.
-- Ví dụ: code = 'ABC', name = 'Công ty ABC', timezone = 'Asia/Ho_Chi_Minh'.
-- -----------------------------------------------------------------------------
CREATE TABLE organization (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code        VARCHAR(30)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    legal_name  VARCHAR(255),
    tax_code    VARCHAR(20),
    email       VARCHAR(255),
    phone       VARCHAR(20),
    address     VARCHAR(500),
    timezone    VARCHAR(50)  NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    currency    CHAR(3)      NOT NULL DEFAULT 'VND',
    locale      VARCHAR(10)  NOT NULL DEFAULT 'vi-VN',
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    settings    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  BIGINT,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  BIGINT,
    version     INT          NOT NULL DEFAULT 0,
    CONSTRAINT ck_organization_status      CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_organization_code_format CHECK (code ~ '^[A-Za-z0-9_-]{2,30}$')
);
CREATE UNIQUE INDEX uq_organization_code ON organization (lower(code));
COMMENT ON TABLE  organization IS 'Doanh nghiệp, đồng thời là tenant.';
COMMENT ON COLUMN organization.code IS 'Mã doanh nghiệp nhập khi đăng nhập; sau này dùng làm subdomain.';
COMMENT ON COLUMN organization.timezone IS 'Múi giờ dùng để cắt ngày, tháng của kỳ báo cáo.';
COMMENT ON COLUMN organization.status IS 'ACTIVE: đang dùng; SUSPENDED: tạm khoá; CLOSED: đã ngừng.';
COMMENT ON COLUMN organization.settings IS 'Cấu hình riêng của doanh nghiệp dạng JSON (MOD-05).';


-- -----------------------------------------------------------------------------
-- org_unit — Cây đơn vị trong doanh nghiệp
--
-- Vai trò: mô tả cơ cấu tổ chức (công ty → khu vực → chi nhánh → nhóm) để
--   (1) gắn dữ liệu nghiệp vụ vào đúng đơn vị: đơn hàng thuộc chi nhánh nào,
--       kho thuộc đâu, nhân viên làm ở đâu;
--   (2) làm PHẠM VI khi gán vai trò ở role_assignment.
-- Quy tắc: mỗi doanh nghiệp có đúng một đơn vị gốc (parent_id NULL); đơn vị cha
--   phải cùng doanh nghiệp; path và depth do trigger tự tính, không ghi tay.
-- Ví dụ cây:  ROOT (path 1)
--               └─ MN Miền Nam (1.3)
--                    ├─ CN-A Chi nhánh A (1.3.5)
--                    │    └─ Kho A (1.3.5.7)
--                    └─ CN-B Chi nhánh B (1.3.6)
--   Lấy cả cây con của Miền Nam: WHERE path <@ '1.3'
-- -----------------------------------------------------------------------------
CREATE TABLE org_unit (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organization (id),
    parent_id        BIGINT,
    path             LTREE        NOT NULL,   -- trigger tính, ví dụ 1.4.9
    depth            SMALLINT     NOT NULL,   -- trigger tính, 0 ở gốc
    unit_type        VARCHAR(30)  NOT NULL,
    code             VARCHAR(50)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    phone            VARCHAR(20),
    email            VARCHAR(255),
    address          VARCHAR(500),
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    sort_order       INT          NOT NULL DEFAULT 0,
    metadata         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT          NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT uq_org_unit_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_org_unit_parent FOREIGN KEY (organization_id, parent_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT ck_org_unit_type CHECK (unit_type IN
        ('COMPANY', 'LEGAL_ENTITY', 'REGION', 'BUSINESS_UNIT', 'BRANCH', 'DEPARTMENT', 'TEAM', 'WAREHOUSE')),
    CONSTRAINT ck_org_unit_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_org_unit_root   CHECK ((parent_id IS NULL) = (depth = 0)),
    CONSTRAINT ck_org_unit_not_self_parent CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE UNIQUE INDEX uq_org_unit_code ON org_unit (organization_id, lower(code)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_org_unit_root ON org_unit (organization_id) WHERE parent_id IS NULL;
CREATE INDEX ix_org_unit_parent ON org_unit (organization_id, parent_id);
CREATE INDEX ix_org_unit_path   ON org_unit USING GIST (path);
COMMENT ON TABLE org_unit IS 'Cây tổ chức. Mỗi doanh nghiệp đúng một đơn vị gốc. Đơn vị cha phải cùng doanh nghiệp (khoá ngoại 2 cột).';
COMMENT ON COLUMN org_unit.parent_id IS 'Đơn vị quản lý trực tiếp (ORG-05). NULL chỉ ở đơn vị gốc.';
COMMENT ON COLUMN org_unit.path IS 'Chuỗi id từ gốc tới chính nó, vd 1.3.5. Do trigger tính; JPA map insertable = false, updatable = false.';
COMMENT ON COLUMN org_unit.depth IS 'Độ sâu trong cây, 0 ở gốc. Do trigger tính.';
COMMENT ON COLUMN org_unit.unit_type IS 'Loại đơn vị: COMPANY, LEGAL_ENTITY (pháp nhân, P2), REGION, BUSINESS_UNIT, BRANCH, DEPARTMENT, TEAM, WAREHOUSE.';
COMMENT ON COLUMN org_unit.status IS 'ACTIVE hoặc INACTIVE (ngừng hoạt động, ORG-02).';


-- Tính path/depth từ đơn vị cha và chặn tạo vòng khi đổi cha.
-- (Ràng buộc cùng doanh nghiệp đã do khoá ngoại fk_org_unit_parent đảm nhận.)
CREATE OR REPLACE FUNCTION org_unit_set_path() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_parent_path LTREE;
BEGIN
    IF NEW.parent_id IS NULL THEN
        NEW.path  := text2ltree(NEW.id::text);
        NEW.depth := 0;
        RETURN NEW;
    END IF;

    SELECT path INTO v_parent_path FROM org_unit WHERE id = NEW.parent_id;
    IF v_parent_path IS NULL THEN
        RAISE EXCEPTION 'org_unit cha % không tồn tại', NEW.parent_id;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF v_parent_path <@ OLD.path THEN
            RAISE EXCEPTION 'Không thể chuyển org_unit % vào cây con của chính nó', NEW.id;
        END IF;
    END IF;

    NEW.path  := v_parent_path || text2ltree(NEW.id::text);
    NEW.depth := nlevel(NEW.path) - 1;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_org_unit_set_path
    BEFORE INSERT OR UPDATE OF parent_id ON org_unit
    FOR EACH ROW EXECUTE FUNCTION org_unit_set_path();


-- Khi đổi cha: cập nhật path/depth của cả cây con.
-- Không tăng version của các đơn vị con: path/depth là cột dẫn xuất, JPA không ghi
-- hai cột này, nên tăng version chỉ gây xung đột giả khi có người đang sửa tên đơn vị con.
CREATE OR REPLACE FUNCTION org_unit_move_subtree() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.path IS DISTINCT FROM OLD.path THEN
        UPDATE org_unit
           SET path  = NEW.path || subpath(path, nlevel(OLD.path)),
               depth = nlevel(NEW.path || subpath(path, nlevel(OLD.path))) - 1
         WHERE path <@ OLD.path
           AND id <> NEW.id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_org_unit_move_subtree
    AFTER UPDATE OF parent_id ON org_unit
    FOR EACH ROW EXECUTE FUNCTION org_unit_move_subtree();

-- ############################################################################
-- ## V2__identity_access.sql
-- ############################################################################

-- =============================================================================
-- V2__identity_access.sql — Identity & Access Management
-- Bảng: feature, permission (danh mục hệ thống), app_user, refresh_token,
--       role, role_permission, role_assignment
-- Phụ thuộc: V1 (organization, org_unit)
-- =============================================================================

-- btree_gist: cho exclusion constraint chống gán trùng vai trò trong cùng khoảng thời gian
CREATE EXTENSION IF NOT EXISTS btree_gist;


-- -----------------------------------------------------------------------------
-- Danh mục hệ thống (không có organization_id)
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- feature — Danh mục tính năng của OpsPilot
--
-- Vai trò: liệt kê các khối chức năng (Tổ chức, Nhân sự, Bán hàng, Tồn kho...).
--   Tính năng lõi (is_core) luôn bật; tính năng còn lại doanh nghiệp bật/tắt
--   theo nhu cầu (bảng cấu hình theo doanh nghiệp thêm ở migration sau).
--   Mỗi permission thuộc một feature, nên tắt feature thì quyền của nó bị ẩn.
-- Đặt tên "feature" thay vì "module" để không nhầm với module của Spring Modulith.
-- Danh mục hệ thống: do migration seed, giống nhau cho mọi doanh nghiệp.
-- -----------------------------------------------------------------------------
CREATE TABLE feature (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code         VARCHAR(30)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    description  VARCHAR(500),
    is_core      BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order   INT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_feature_code UNIQUE (code)
);
COMMENT ON TABLE feature IS 'Danh mục tính năng. Tính năng lõi (is_core) luôn bật.';
COMMENT ON COLUMN feature.is_core IS 'TRUE: luôn bật, không tắt được (ORG, IAM, HR...).';
COMMENT ON COLUMN feature.sort_order IS 'Thứ tự hiển thị trên menu (MOD-04).';


-- -----------------------------------------------------------------------------
-- permission — Danh mục quyền thao tác
--
-- Vai trò: liệt kê mọi hành động cần kiểm soát, đặt tên theo dạng
--   feature.resource.action. Code Java kiểm tra quyền theo đúng mã này,
--   vd trước khi tạo đơn hàng kiểm 'sales.order.create'.
-- Do migration seed; doanh nghiệp không tự tạo permission, chỉ gom chúng vào vai trò.
-- Ví dụ: hr.employee.view, hr.employee.manage, iam.role.assign.
-- -----------------------------------------------------------------------------
CREATE TABLE permission (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    feature_id    BIGINT       NOT NULL REFERENCES feature (id),
    code          VARCHAR(100) NOT NULL,
    name          VARCHAR(200) NOT NULL,
    description   VARCHAR(500),
    is_sensitive  BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_permission_code UNIQUE (code),
    CONSTRAINT ck_permission_code_format CHECK (code ~ '^[a-z]+(\.[a-z_]+)+$')
);
CREATE INDEX ix_permission_feature ON permission (feature_id);
COMMENT ON TABLE permission IS 'Danh mục quyền do code định nghĩa, dạng feature.resource.action.';
COMMENT ON COLUMN permission.is_sensitive IS 'Quyền phân quyền hoặc tài chính; giao diện cảnh báo khi gán.';


-- -----------------------------------------------------------------------------
-- app_user — Tài khoản đăng nhập
--
-- Vai trò: danh tính dùng để đăng nhập và nhận quyền. KHÔNG chứa thông tin nhân sự
--   (hồ sơ nằm ở employee), vì có nhân viên chưa được cấp tài khoản và có tài
--   khoản không phải nhân viên (vd quản trị viên thuê ngoài).
-- Đăng nhập: mã doanh nghiệp + (username | email | phone) + mật khẩu.
--   Ba định danh đều unique trong phạm vi một doanh nghiệp.
-- Bảo mật:
--   * token_version tăng khi khoá tài khoản hoặc đổi quyền → JWT cũ bị từ chối ngay.
--   * failed_login_count + locked_until chống dò mật khẩu.
--   * must_change_password: admin reset → bắt đổi mật khẩu ở lần đăng nhập đầu.
-- -----------------------------------------------------------------------------
CREATE TABLE app_user (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id       BIGINT       NOT NULL REFERENCES organization (id),
    username              VARCHAR(50)  NOT NULL,
    email                 VARCHAR(255),
    phone                 VARCHAR(20),
    password_hash         VARCHAR(255) NOT NULL,
    display_name          VARCHAR(200) NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    must_change_password  BOOLEAN      NOT NULL DEFAULT TRUE,
    token_version         INT          NOT NULL DEFAULT 0,
    failed_login_count    SMALLINT     NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    password_changed_at   TIMESTAMPTZ,
    locale                VARCHAR(10),
    timezone              VARCHAR(50),
    mfa_enabled           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by            BIGINT,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by            BIGINT,
    version               INT          NOT NULL DEFAULT 0,
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT uq_app_user_org_id UNIQUE (organization_id, id),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    CONSTRAINT ck_app_user_failed_login CHECK (failed_login_count >= 0)
);
CREATE UNIQUE INDEX uq_app_user_username ON app_user (organization_id, lower(username))
    WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_app_user_email ON app_user (organization_id, lower(email))
    WHERE email IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX uq_app_user_phone ON app_user (organization_id, phone)
    WHERE phone IS NOT NULL AND deleted_at IS NULL;
COMMENT ON TABLE  app_user IS 'Tài khoản đăng nhập: mã doanh nghiệp + username/email/phone.';
COMMENT ON COLUMN app_user.token_version IS 'Tăng khi khoá tài khoản hoặc đổi quyền; JWT mang version cũ bị từ chối.';
COMMENT ON COLUMN app_user.username IS 'Do admin cấp (IAM-03); unique trong doanh nghiệp, không phân biệt hoa thường.';
COMMENT ON COLUMN app_user.email IS 'Tuỳ chọn; nếu có thì unique trong doanh nghiệp.';
COMMENT ON COLUMN app_user.phone IS 'Tuỳ chọn, chuẩn hoá dạng +84...; nếu có thì unique trong doanh nghiệp.';
COMMENT ON COLUMN app_user.password_hash IS 'Băm bằng BCrypt hoặc Argon2 qua Spring Security; không lưu mật khẩu gốc.';
COMMENT ON COLUMN app_user.status IS 'ACTIVE: dùng được; LOCKED: admin khoá; DISABLED: vô hiệu hoá (IAM-04).';
COMMENT ON COLUMN app_user.must_change_password IS 'TRUE sau khi admin cấp hoặc reset mật khẩu.';
COMMENT ON COLUMN app_user.failed_login_count IS 'Số lần nhập sai liên tiếp; về 0 khi đăng nhập đúng.';
COMMENT ON COLUMN app_user.locked_until IS 'Khoá tạm tới thời điểm này sau nhiều lần nhập sai.';
COMMENT ON COLUMN app_user.mfa_enabled IS 'Chừa sẵn cho xác thực đa yếu tố (IAM-14, P1).';


-- -----------------------------------------------------------------------------
-- refresh_token — Phiên đăng nhập dài hạn
--
-- Vai trò: cấp lại access token (JWT sống 15 phút) mà không bắt nhập lại mật khẩu.
--   Mỗi lần dùng, token cũ bị thu hồi (ROTATED) và sinh token mới cùng family_id.
--   Nếu một token ĐÃ xoay bị dùng lại → nghi bị đánh cắp → thu hồi cả family.
-- Thời hạn: 7 ngày cho web, 30 ngày cho mobile (client_type).
-- Chỉ lưu SHA-256 của token; lộ bảng này cũng không dùng được token.
-- -----------------------------------------------------------------------------
CREATE TABLE refresh_token (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    token_hash       CHAR(64)     NOT NULL,
    family_id        BIGINT       NOT NULL,   -- để NULL ở token đầu tiên, trigger gán = id
    replaced_by_id   BIGINT       REFERENCES refresh_token (id),
    issued_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    revoked_at       TIMESTAMPTZ,
    revoke_reason    VARCHAR(30),
    client_type      VARCHAR(20)  NOT NULL,
    device_name      VARCHAR(100),
    user_agent       VARCHAR(500),
    ip_address       INET,
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (organization_id, user_id)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT uq_refresh_token_hash   UNIQUE (token_hash),
    CONSTRAINT ck_refresh_token_client CHECK (client_type IN ('WEB', 'MOBILE')),
    CONSTRAINT ck_refresh_token_reason CHECK (revoke_reason IS NULL OR revoke_reason IN
        ('LOGOUT', 'ROTATED', 'REUSE_DETECTED', 'ADMIN', 'PASSWORD_CHANGED')),
    CONSTRAINT ck_refresh_token_expiry CHECK (expires_at > issued_at)
);
CREATE INDEX ix_refresh_token_user_active ON refresh_token (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_refresh_token_family      ON refresh_token (family_id);
CREATE INDEX ix_refresh_token_expires     ON refresh_token (expires_at);
COMMENT ON TABLE refresh_token IS 'Refresh token xoay vòng; chỉ lưu SHA-256. Dùng lại token đã xoay -> thu hồi cả family.';
COMMENT ON COLUMN refresh_token.token_hash IS 'SHA-256 (hex) của refresh token gửi cho client.';
COMMENT ON COLUMN refresh_token.family_id IS 'Id token đầu tiên của một lần đăng nhập; các token xoay vòng sau đó dùng chung giá trị này.';
COMMENT ON COLUMN refresh_token.replaced_by_id IS 'Token mới sinh ra khi token này được dùng để xoay vòng.';
COMMENT ON COLUMN refresh_token.revoke_reason IS 'LOGOUT, ROTATED, REUSE_DETECTED, ADMIN, PASSWORD_CHANGED.';
COMMENT ON COLUMN refresh_token.client_type IS 'WEB hoặc MOBILE; quyết định thời hạn token.';

CREATE OR REPLACE FUNCTION refresh_token_set_family() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.family_id IS NULL THEN
        NEW.family_id := NEW.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_refresh_token_set_family
    BEFORE INSERT ON refresh_token
    FOR EACH ROW EXECUTE FUNCTION refresh_token_set_family();


-- -----------------------------------------------------------------------------
-- role — Vai trò của doanh nghiệp
--
-- Vai trò: một tập quyền có tên (Owner, Branch Manager, Sales Staff...).
--   Bản thân vai trò CHƯA gắn với đơn vị nào; đơn vị được chỉ định lúc gán vai trò
--   cho người dùng ở role_assignment. Nhờ vậy một vai trò Branch Manager dùng
--   chung cho mọi chi nhánh.
-- is_system = TRUE: 8 vai trò mặc định do seed_system_roles tạo, không xoá được.
--   Vai trò tự tạo (IAM-11, P1) có is_system = FALSE.
-- Khác job_title: job_title là chức danh nhân sự; role là quyền trong hệ thống.
-- -----------------------------------------------------------------------------
CREATE TABLE role (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organization (id),
    code             VARCHAR(50)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    description      VARCHAR(500),
    is_system        BOOLEAN      NOT NULL DEFAULT FALSE,
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT          NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT uq_role_org_id UNIQUE (organization_id, id),
    CONSTRAINT ck_role_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_role_system_not_deleted CHECK (NOT (is_system AND deleted_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_role_code ON role (organization_id, lower(code)) WHERE deleted_at IS NULL;
COMMENT ON TABLE role IS 'Vai trò của từng doanh nghiệp. Vai trò hệ thống (is_system) được seed và không xoá được.';
COMMENT ON COLUMN role.code IS 'Mã vai trò, vd BRANCH_MANAGER; unique trong doanh nghiệp.';
COMMENT ON COLUMN role.name IS 'Tên hiển thị; doanh nghiệp đổi được.';
COMMENT ON COLUMN role.is_system IS 'TRUE: vai trò mặc định, không xoá được.';


-- -----------------------------------------------------------------------------
-- role_permission — Vai trò gồm những quyền nào
--
-- Vai trò: bảng nối nhiều-nhiều giữa role và permission.
-- Ví dụ: BRANCH_MANAGER có org.unit.view, iam.user.view, hr.employee.view,
--   hr.employee.manage. Đổi quyền của một vai trò = thêm/xoá dòng ở bảng này,
--   và phải tăng token_version của những user đang giữ vai trò đó.
-- -----------------------------------------------------------------------------
CREATE TABLE role_permission (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL,
    role_id          BIGINT       NOT NULL,
    permission_id    BIGINT       NOT NULL REFERENCES permission (id),
    granted_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    granted_by       BIGINT,
    CONSTRAINT fk_role_permission_role FOREIGN KEY (organization_id, role_id)
        REFERENCES role (organization_id, id),
    CONSTRAINT fk_role_permission_granted_by FOREIGN KEY (organization_id, granted_by)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT uq_role_permission UNIQUE (role_id, permission_id)
);
CREATE INDEX ix_role_permission_permission ON role_permission (permission_id);
COMMENT ON TABLE role_permission IS 'Vai trò gồm những permission nào (nhiều-nhiều).';


-- -----------------------------------------------------------------------------
-- role_assignment — Ai có vai trò gì, tại đơn vị nào, trong thời gian nào
--
-- Vai trò: trái tim của phân quyền theo phạm vi.
--   Quyền của user tại đơn vị X = quyền của các vai trò được gán TẠI X,
--   cộng với vai trò gán tại đơn vị cha của X có include_descendants = TRUE.
-- Ví dụ: user rm.south được gán REGIONAL_MANAGER tại Miền Nam,
--   include_descendants = TRUE → xem được CN-A, CN-B và các nhóm bên dưới,
--   nhưng không thấy Miền Bắc.
-- Gán có thời hạn: valid_from / valid_to (vd quản lý tạm thay 2 tuần).
-- Thu hồi: đặt revoked_at, không xoá dòng, để giữ lịch sử ai từng có quyền gì.
-- Quyền KHÔNG tự suy ra từ nơi làm việc (employee_assignment) hay vị trí trong cây.
-- -----------------------------------------------------------------------------
CREATE TABLE role_assignment (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id      BIGINT       NOT NULL,
    user_id              BIGINT       NOT NULL,
    role_id              BIGINT       NOT NULL,
    scope_unit_id        BIGINT       NOT NULL,
    include_descendants  BOOLEAN      NOT NULL DEFAULT TRUE,
    valid_from           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    valid_to             TIMESTAMPTZ,              -- NULL = không thời hạn
    assigned_by          BIGINT,
    revoked_at           TIMESTAMPTZ,
    revoked_by           BIGINT,
    note                 VARCHAR(500),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by           BIGINT,
    version              INT          NOT NULL DEFAULT 0,
    -- Mọi tham chiếu phải cùng doanh nghiệp với chính dòng gán quyền
    CONSTRAINT fk_role_assignment_user  FOREIGN KEY (organization_id, user_id)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT fk_role_assignment_role  FOREIGN KEY (organization_id, role_id)
        REFERENCES role (organization_id, id),
    CONSTRAINT fk_role_assignment_scope FOREIGN KEY (organization_id, scope_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_role_assignment_assigned_by FOREIGN KEY (organization_id, assigned_by)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT fk_role_assignment_revoked_by  FOREIGN KEY (organization_id, revoked_by)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT ck_role_assignment_validity CHECK (valid_to IS NULL OR valid_to > valid_from),
    -- Không có hai lần gán cùng user + vai trò + đơn vị với khoảng hiệu lực chồng nhau.
    -- Lần gán đã hết hạn (valid_to đã qua) không cản việc gán lại.
    CONSTRAINT ex_role_assignment_overlap EXCLUDE USING gist (
        user_id        WITH =,
        role_id        WITH =,
        scope_unit_id  WITH =,
        tstzrange(valid_from, valid_to, '[)') WITH &&
    ) WHERE (revoked_at IS NULL)
);
CREATE INDEX ix_role_assignment_user_active ON role_assignment (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_role_assignment_role        ON role_assignment (role_id);
CREATE INDEX ix_role_assignment_scope_unit  ON role_assignment (scope_unit_id);
COMMENT ON TABLE role_assignment IS
    'Gán vai trò cho user TẠI một đơn vị. Đang hiệu lực = revoked_at IS NULL AND valid_from <= now() < valid_to (valid_to NULL = vô hạn).';
COMMENT ON COLUMN role_assignment.scope_unit_id IS 'Đơn vị được cấp quyền.';
COMMENT ON COLUMN role_assignment.include_descendants IS 'TRUE: áp dụng cho cả cây con của scope_unit; FALSE: chỉ đúng đơn vị đó.';
COMMENT ON COLUMN role_assignment.valid_from IS 'Bắt đầu hiệu lực.';
COMMENT ON COLUMN role_assignment.valid_to IS 'Hết hiệu lực (không tính thời điểm này); NULL = không thời hạn.';
COMMENT ON COLUMN role_assignment.revoked_at IS 'Thời điểm thu hồi; dòng đã thu hồi không còn hiệu lực nhưng vẫn giữ lại.';


-- -----------------------------------------------------------------------------
-- Seed danh mục hệ thống
-- -----------------------------------------------------------------------------

INSERT INTO feature (code, name, is_core, sort_order) VALUES
    ('ORG',    'Tổ chức',                  TRUE,   10),
    ('IAM',    'Tài khoản và phân quyền',  TRUE,   20),
    ('HR',     'Nhân sự',                  TRUE,   30),
    ('TASK',   'Công việc',                TRUE,   40),
    ('SALES',  'Bán hàng',                 FALSE,  50),
    ('INV',    'Tồn kho',                  FALSE,  60),
    ('DATA',   'Nhập xuất dữ liệu',        TRUE,   70),
    ('BM',     'Chỉ số kinh doanh',        FALSE,  80),
    ('REPORT', 'Báo cáo và dashboard',     TRUE,   90),
    ('AUDIT',  'Lịch sử thao tác',         TRUE,  100);

INSERT INTO permission (feature_id, code, name, is_sensitive)
SELECT f.id, v.code, v.name, v.is_sensitive
FROM (VALUES
    ('ORG',   'org.unit.view',      'Xem cây tổ chức và đơn vị',        FALSE),
    ('ORG',   'org.unit.manage',    'Tạo, sửa, ngừng hoạt động đơn vị', FALSE),
    ('ORG',   'feature.manage',     'Bật, tắt và cấu hình tính năng',   TRUE),
    ('IAM',   'iam.user.view',      'Xem tài khoản',                    FALSE),
    ('IAM',   'iam.user.manage',    'Cấp, khoá, reset tài khoản',       TRUE),
    ('IAM',   'iam.role.manage',    'Tạo, sửa vai trò',                 TRUE),
    ('IAM',   'iam.role.assign',    'Gán, thu hồi vai trò',             TRUE),
    ('HR',    'hr.employee.view',   'Xem hồ sơ nhân viên',              FALSE),
    ('HR',    'hr.employee.manage', 'Tạo, sửa hồ sơ nhân viên',         FALSE),
    ('AUDIT', 'audit.view',         'Xem lịch sử thao tác',             TRUE)
) AS v(feature_code, code, name, is_sensitive)
JOIN feature f ON f.code = v.feature_code;


-- -----------------------------------------------------------------------------
-- Tạo vai trò hệ thống cho một doanh nghiệp: SELECT seed_system_roles(<org_id>);
--
-- Idempotent theo TỪNG vai trò: gọi lại chỉ tạo vai trò còn thiếu.
-- Quyền mặc định chỉ gán cho vai trò vừa được tạo trong lần gọi này, để không
-- trả lại những quyền mà quản trị viên đã chủ động gỡ khỏi vai trò hệ thống.
-- Permission mới ở các phiên bản sau được cấp cho vai trò hệ thống bằng chính
-- migration thêm permission đó.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION seed_system_roles(p_org_id BIGINT) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    WITH new_roles AS (
        INSERT INTO role (organization_id, code, name, is_system)
        SELECT p_org_id, v.code, v.name, TRUE
        FROM (VALUES
            ('OWNER',            'Owner / Director'),
            ('ORG_ADMIN',        'Organization Administrator'),
            ('REGIONAL_MANAGER', 'Regional Manager'),
            ('BRANCH_MANAGER',   'Branch Manager'),
            ('SHIFT_LEADER',     'Shift / Team Leader'),
            ('SALES_STAFF',      'Sales Staff'),
            ('INVENTORY_STAFF',  'Inventory Staff'),
            ('EMPLOYEE',         'General Employee')
        ) AS v(code, name)
        ON CONFLICT (organization_id, (lower(code))) WHERE deleted_at IS NULL DO NOTHING
        RETURNING id, code
    )
    INSERT INTO role_permission (organization_id, role_id, permission_id)
    SELECT p_org_id, nr.id, p.id
    FROM new_roles nr
    JOIN (VALUES
        ('OWNER',            'org.unit.view'),
        ('OWNER',            'org.unit.manage'),
        ('OWNER',            'iam.user.view'),
        ('OWNER',            'iam.role.assign'),
        ('OWNER',            'hr.employee.view'),
        ('OWNER',            'feature.manage'),
        ('OWNER',            'audit.view'),
        ('ORG_ADMIN',        'org.unit.view'),
        ('ORG_ADMIN',        'org.unit.manage'),
        ('ORG_ADMIN',        'iam.user.view'),
        ('ORG_ADMIN',        'iam.user.manage'),
        ('ORG_ADMIN',        'iam.role.manage'),
        ('ORG_ADMIN',        'iam.role.assign'),
        ('ORG_ADMIN',        'hr.employee.view'),
        ('ORG_ADMIN',        'hr.employee.manage'),
        ('ORG_ADMIN',        'feature.manage'),
        ('ORG_ADMIN',        'audit.view'),
        ('REGIONAL_MANAGER', 'org.unit.view'),
        ('REGIONAL_MANAGER', 'iam.user.view'),
        ('REGIONAL_MANAGER', 'hr.employee.view'),
        ('BRANCH_MANAGER',   'org.unit.view'),
        ('BRANCH_MANAGER',   'iam.user.view'),
        ('BRANCH_MANAGER',   'hr.employee.view'),
        ('BRANCH_MANAGER',   'hr.employee.manage'),
        ('SHIFT_LEADER',     'org.unit.view'),
        ('SHIFT_LEADER',     'hr.employee.view')
    ) AS m(role_code, permission_code) ON m.role_code = nr.code
    JOIN permission p ON p.code = m.permission_code
    ON CONFLICT (role_id, permission_id) DO NOTHING;
END;
$$;

COMMENT ON FUNCTION seed_system_roles(BIGINT) IS
    'Tạo các vai trò hệ thống còn thiếu cho một doanh nghiệp, kèm quyền mặc định của vai trò vừa tạo.';

-- ############################################################################
-- ## V3__workforce.sql
-- ############################################################################

-- =============================================================================
-- V3__workforce.sql — Workforce
-- Bảng: job_title, employee, employee_assignment
-- Phụ thuộc: V1 (organization, org_unit), V2 (app_user)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- job_title — Chức danh công việc
--
-- Vai trò: danh mục chức danh nhân sự của doanh nghiệp (Thu ngân, Thủ kho,
--   Quản lý chi nhánh...). Dùng để hiển thị và lọc nhân sự (HR-04).
-- KHÔNG quyết định quyền trong hệ thống: một Thủ kho chỉ thao tác được kho khi
--   được gán vai trò INVENTORY_STAFF ở role_assignment.
-- -----------------------------------------------------------------------------
CREATE TABLE job_title (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organization (id),
    code             VARCHAR(50)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    description      VARCHAR(500),
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT          NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT uq_job_title_org_id UNIQUE (organization_id, id),
    CONSTRAINT ck_job_title_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE UNIQUE INDEX uq_job_title_code ON job_title (organization_id, lower(code)) WHERE deleted_at IS NULL;
COMMENT ON TABLE job_title IS 'Chức danh công việc. Không phải vai trò phân quyền.';
COMMENT ON COLUMN job_title.code IS 'Mã chức danh, vd INV_STAFF; unique trong doanh nghiệp.';


-- -----------------------------------------------------------------------------
-- employee — Hồ sơ nhân sự
--
-- Vai trò: thông tin người làm việc cho doanh nghiệp: mã nhân viên, họ tên,
--   ngày vào làm, trạng thái, người quản lý trực tiếp. Task Management dùng bảng
--   này để chọn người được giao việc; báo cáo nhân sự đếm trên bảng này.
-- user_id: tài khoản đăng nhập nếu nhân viên đã được cấp (0 hoặc 1 tài khoản).
-- Chỉ lưu dữ liệu cá nhân cần cho vận hành; CCCD, lương không thuộc MVP.
-- -----------------------------------------------------------------------------
CREATE TABLE employee (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id      BIGINT       NOT NULL REFERENCES organization (id),
    user_id              BIGINT,
    employee_code        VARCHAR(30)  NOT NULL,
    full_name            VARCHAR(200) NOT NULL,
    gender               VARCHAR(10),
    date_of_birth        DATE,
    phone                VARCHAR(20),
    email                VARCHAR(255),
    hire_date            DATE,
    termination_date     DATE,
    employment_status    VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    manager_employee_id  BIGINT,
    metadata             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by           BIGINT,
    version              INT          NOT NULL DEFAULT 0,
    deleted_at           TIMESTAMPTZ,
    CONSTRAINT uq_employee_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_employee_user   UNIQUE (user_id),
    CONSTRAINT fk_employee_user FOREIGN KEY (organization_id, user_id)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT fk_employee_manager FOREIGN KEY (organization_id, manager_employee_id)
        REFERENCES employee (organization_id, id),
    CONSTRAINT ck_employee_gender CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER')),
    CONSTRAINT ck_employee_status CHECK (employment_status IN ('ACTIVE', 'ON_LEAVE', 'TERMINATED')),
    CONSTRAINT ck_employee_dates  CHECK (termination_date IS NULL OR hire_date IS NULL OR termination_date >= hire_date),
    CONSTRAINT ck_employee_not_own_manager CHECK (manager_employee_id IS NULL OR manager_employee_id <> id)
);
CREATE UNIQUE INDEX uq_employee_code ON employee (organization_id, lower(employee_code)) WHERE deleted_at IS NULL;
CREATE INDEX ix_employee_org_status ON employee (organization_id, employment_status);
CREATE INDEX ix_employee_manager    ON employee (manager_employee_id);
COMMENT ON TABLE  employee IS 'Hồ sơ nhân sự. Có thể chưa có tài khoản (user_id NULL).';
COMMENT ON COLUMN employee.manager_employee_id IS 'Người quản lý trực tiếp, phải cùng doanh nghiệp.';
COMMENT ON COLUMN employee.user_id IS 'Tài khoản đăng nhập của nhân viên; NULL nếu chưa cấp.';
COMMENT ON COLUMN employee.employee_code IS 'Mã nhân viên (HR-02); unique trong doanh nghiệp.';
COMMENT ON COLUMN employee.employment_status IS 'ACTIVE: đang làm; ON_LEAVE: tạm nghỉ; TERMINATED: đã nghỉ việc.';
COMMENT ON COLUMN employee.termination_date IS 'Ngày nghỉ việc (HR-08).';


-- -----------------------------------------------------------------------------
-- employee_assignment — Nhân viên làm việc ở đâu, chức danh gì, từ khi nào
--
-- Vai trò: lịch sử công tác của nhân viên.
--   * Chuyển đơn vị (HR-07) = đóng dòng cũ bằng end_date, mở dòng mới.
--   * is_primary đánh dấu nơi làm việc chính; sau này một người kiêm nhiệm nhiều
--     đơn vị chỉ cần thêm dòng is_primary = FALSE (ORG-09, HR-09).
-- Lưu ý: nơi làm việc KHÔNG cấp quyền. Quyền chỉ đến từ role_assignment.
-- -----------------------------------------------------------------------------
CREATE TABLE employee_assignment (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL,
    employee_id      BIGINT       NOT NULL,
    org_unit_id      BIGINT       NOT NULL,
    job_title_id     BIGINT,
    is_primary       BOOLEAN      NOT NULL DEFAULT TRUE,
    start_date       DATE         NOT NULL DEFAULT CURRENT_DATE,
    end_date         DATE,
    note             VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_employee_assignment_employee FOREIGN KEY (organization_id, employee_id)
        REFERENCES employee (organization_id, id),
    CONSTRAINT fk_employee_assignment_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_employee_assignment_job_title FOREIGN KEY (organization_id, job_title_id)
        REFERENCES job_title (organization_id, id),
    CONSTRAINT ck_employee_assignment_dates CHECK (end_date IS NULL OR end_date >= start_date)
);
CREATE UNIQUE INDEX uq_employee_assignment_primary ON employee_assignment (employee_id)
    WHERE is_primary AND end_date IS NULL;
CREATE INDEX ix_employee_assignment_employee ON employee_assignment (employee_id);
CREATE INDEX ix_employee_assignment_unit     ON employee_assignment (org_unit_id) WHERE end_date IS NULL;
COMMENT ON TABLE employee_assignment IS 'Nhân viên làm việc tại đơn vị nào, chức danh gì. KHÔNG cấp quyền.';
COMMENT ON COLUMN employee_assignment.is_primary IS 'Nơi làm việc chính; mỗi người chỉ một nơi chính đang hiệu lực.';
COMMENT ON COLUMN employee_assignment.end_date IS 'NULL: đang làm việc tại đây; có giá trị: đã chuyển đi.';

-- ############################################################################
-- ## V4__audit.sql
-- ############################################################################

-- =============================================================================
-- V4__audit.sql — Audit
-- Bảng: audit_log (chia partition theo tháng)
-- Hàm dùng chung: ensure_monthly_partitions(), default_partition_row_count(),
--                 drop_old_partitions()
-- Quyền gọi các hàm này bị giới hạn cho role opspilot_job ở V14; có allowlist bảng.
-- Phụ thuộc: V1 (organization, org_unit), V2 (app_user)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Tạo sẵn partition theo tháng cho một bảng chia partition RANGE trên cột thời gian.
--   SELECT ensure_monthly_partitions('audit_log', 3);     -- tháng trước → 3 tháng tới
-- Job db-scheduler gọi hằng ngày cho mọi bảng chia partition (audit_log,
-- notification, forecast_value). Tên partition: <bảng>_YYYYMM. Mốc tháng tính
-- theo UTC; đây là cách lưu trữ, không ảnh hưởng cách cắt ngày của báo cáo.
--
-- Partition DEFAULT giữ dòng rơi ngoài các tháng đã tạo (vd job ngừng chạy vài
-- tháng). PostgreSQL không cho tạo partition mới khi DEFAULT đang chứa dòng của
-- tháng đó, nên hàm xử lý: tạo bảng rời, chuyển các dòng của tháng đó từ DEFAULT
-- sang, rồi ATTACH. Sau mỗi lần chạy, job gọi default_partition_row_count(); khác 0
-- nghĩa là có dòng nằm ngoài khoảng p_months_back..p_months_ahead, cần cảnh báo.
--
-- SECURITY DEFINER: chạy với quyền chủ sở hữu bảng (tạo partition cần quyền này).
-- Chỉ role opspilot_job được gọi (V14 thu hồi quyền của PUBLIC), và hàm chỉ nhận
-- các bảng trong danh sách cho phép, không nhận bảng tuỳ ý.
-- Partition mới được bật RLS nếu bảng cha đang bật, để role ứng dụng không đọc
-- thẳng partition. Dọn dữ liệu cũ: drop_old_partitions().
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ensure_monthly_partitions(
    p_parent        REGCLASS,
    p_months_ahead  INT DEFAULT 3,
    p_months_back   INT DEFAULT 1)
RETURNS INT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_month    DATE := date_trunc('month', now() AT TIME ZONE 'UTC')::date;
    v_from     DATE;
    v_lo       TIMESTAMPTZ;
    v_hi       TIMESTAMPTZ;
    v_name     TEXT;
    v_key      TEXT;
    v_default  REGCLASS;
    v_rls      BOOLEAN;
    v_moved    BIGINT;
    v_created  INT := 0;
BEGIN
    -- So sánh theo OID của bảng thực, không phụ thuộc search_path/tên hiển thị.
    -- to_regclass() vì V4 chạy trước khi notification và forecast_value được tạo.
    IF NOT coalesce(
          p_parent = to_regclass('public.audit_log')
       OR p_parent = to_regclass('public.notification')
       OR p_parent = to_regclass('public.forecast_value'), FALSE) THEN
        RAISE EXCEPTION 'ensure_monthly_partitions không áp dụng cho %', p_parent USING ERRCODE = '42501';
    END IF;
    IF p_months_ahead IS NULL OR p_months_back IS NULL
       OR p_months_ahead NOT BETWEEN 0 AND 24 OR p_months_back NOT BETWEEN 0 AND 24 THEN
        RAISE EXCEPTION 'months_ahead/months_back phải từ 0 đến 24' USING ERRCODE = '22023';
    END IF;

    SELECT a.attname INTO v_key
      FROM pg_partitioned_table pt
      JOIN pg_attribute a ON a.attrelid = pt.partrelid AND a.attnum = pt.partattrs[0]
     WHERE pt.partrelid = p_parent;
    IF v_key IS NULL THEN
        RAISE EXCEPTION '% không phải bảng chia partition', p_parent;
    END IF;

    SELECT i.inhrelid::regclass INTO v_default
      FROM pg_inherits i
      JOIN pg_class c ON c.oid = i.inhrelid
     WHERE i.inhparent = p_parent
       AND pg_get_expr(c.relpartbound, c.oid) = 'DEFAULT';

    SELECT relrowsecurity INTO v_rls FROM pg_class WHERE oid = p_parent;

    FOR i IN -p_months_back..p_months_ahead LOOP
        v_from := (v_month + make_interval(months => i))::date;
        v_lo   := v_from::timestamp AT TIME ZONE 'UTC';
        v_hi   := (v_from + INTERVAL '1 month')::timestamp AT TIME ZONE 'UTC';
        v_name := p_parent::text || '_' || to_char(v_from, 'YYYYMM');
        CONTINUE WHEN to_regclass(v_name) IS NOT NULL;

        v_moved := 0;
        IF v_default IS NOT NULL THEN
            EXECUTE format('SELECT count(*) FROM %s WHERE %I >= $1 AND %I < $2', v_default, v_key, v_key)
               INTO v_moved USING v_lo, v_hi;
        END IF;

        IF v_moved = 0 THEN
            EXECUTE format('CREATE TABLE %I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
                           v_name, p_parent, v_lo, v_hi);
        ELSE
            EXECUTE format('CREATE TABLE %I (LIKE %s INCLUDING DEFAULTS INCLUDING CONSTRAINTS)', v_name, p_parent);
            EXECUTE format('WITH moved AS (DELETE FROM %s WHERE %I >= $1 AND %I < $2 RETURNING *) '
                           'INSERT INTO %I SELECT * FROM moved', v_default, v_key, v_key, v_name)
               USING v_lo, v_hi;
            EXECUTE format('ALTER TABLE %s ATTACH PARTITION %I FOR VALUES FROM (%L) TO (%L)',
                           p_parent, v_name, v_lo, v_hi);
            RAISE NOTICE 'Đã chuyển % dòng từ % sang %', v_moved, v_default, v_name;
        END IF;

        IF v_rls THEN
            EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', v_name);
        END IF;
        v_created := v_created + 1;
    END LOOP;
    RETURN v_created;
END;
$$;
COMMENT ON FUNCTION ensure_monthly_partitions(REGCLASS, INT, INT) IS
    'Tạo partition từ p_months_back tháng trước tới p_months_ahead tháng tới nếu chưa có; tự chuyển dòng đang nằm trong DEFAULT. Trả về số partition vừa tạo.';


-- Số dòng đang nằm trong partition DEFAULT; job cảnh báo khi khác 0.
CREATE OR REPLACE FUNCTION default_partition_row_count(p_parent REGCLASS)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_default  REGCLASS;
    v_count    BIGINT := 0;
BEGIN
    -- SECURITY DEFINER: không biến thành công cụ đọc số dòng của mọi bảng khác.
    IF NOT coalesce(
          p_parent = to_regclass('public.audit_log')
       OR p_parent = to_regclass('public.notification')
       OR p_parent = to_regclass('public.forecast_value'), FALSE) THEN
        RAISE EXCEPTION 'default_partition_row_count không áp dụng cho %', p_parent USING ERRCODE = '42501';
    END IF;
    SELECT i.inhrelid::regclass INTO v_default
      FROM pg_inherits i
      JOIN pg_class c ON c.oid = i.inhrelid
     WHERE i.inhparent = p_parent
       AND pg_get_expr(c.relpartbound, c.oid) = 'DEFAULT';
    IF v_default IS NOT NULL THEN
        EXECUTE format('SELECT count(*) FROM %s', v_default) INTO v_count;
    END IF;
    RETURN v_count;
END;
$$;
COMMENT ON FUNCTION default_partition_row_count(REGCLASS) IS 'Số dòng trong partition DEFAULT của bảng; khác 0 thì cần cảnh báo.';


-- Xoá partition các tháng cũ hơn p_keep_months (DETACH rồi DROP). Chỉ cho
-- notification và forecast_value; thời gian giữ audit_log còn MỞ (SAAS-15) nên
-- chưa cho xoá. Trả về số partition đã xoá.
CREATE OR REPLACE FUNCTION drop_old_partitions(p_parent REGCLASS, p_keep_months INT)
RETURNS INT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_cutoff   DATE := (date_trunc('month', now() AT TIME ZONE 'UTC') - make_interval(months => p_keep_months))::date;
    v_dropped  INT := 0;
    r          RECORD;
BEGIN
    IF NOT coalesce(
          p_parent = to_regclass('public.notification')
       OR p_parent = to_regclass('public.forecast_value'), FALSE) THEN
        RAISE EXCEPTION 'drop_old_partitions không áp dụng cho %', p_parent USING ERRCODE = '42501';
    END IF;
    IF p_keep_months < 1 THEN
        RAISE EXCEPTION 'p_keep_months phải >= 1';
    END IF;
    FOR r IN
        SELECT c.relname
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
         WHERE i.inhparent = p_parent
           AND c.relname ~ ('^' || p_parent::text || '_[0-9]{6}$')
    LOOP
        IF to_date(right(r.relname, 6), 'YYYYMM') < v_cutoff THEN
            EXECUTE format('ALTER TABLE %s DETACH PARTITION %I', p_parent, r.relname);
            EXECUTE format('DROP TABLE %I', r.relname);
            v_dropped := v_dropped + 1;
        END IF;
    END LOOP;
    RETURN v_dropped;
END;
$$;
COMMENT ON FUNCTION drop_old_partitions(REGCLASS, INT) IS 'Xoá partition cũ hơn p_keep_months tháng của notification, forecast_value.';


-- -----------------------------------------------------------------------------
-- audit_log — Lịch sử thao tác quan trọng
--
-- Vai trò: trả lời "ai, làm gì, khi nào, ở đơn vị nào, với đối tượng nào, thành
--   công hay bị từ chối" cho các thao tác như đổi quyền, khoá tài khoản, huỷ đơn,
--   điều chỉnh kho, import dữ liệu (AUDIT-01→06).
-- actor_type: USER (người dùng), AI (trợ lý AI), SYSTEM (job nền).
-- result = DENIED ghi lại cả những lần truy cập trái quyền.
-- Chỉ ghi thêm, không sửa, không xoá. Không thay thế bản ghi giao dịch nghiệp vụ.
-- Chia partition theo tháng trên occurred_at vì đây là bảng lớn nhanh nhất và
--   không bảng nào tham chiếu tới nó. Khoá chính gồm (id, occurred_at) theo yêu cầu
--   của PostgreSQL với bảng chia partition, nên DB chỉ bảo đảm cặp này duy nhất.
--   id dùng GENERATED ALWAYS chỉ chặn việc vô tình tự chèn id: role có quyền INSERT
--   vẫn ghi đè được bằng OVERRIDING SYSTEM VALUE, và database không có ràng buộc
--   unique riêng cho id trên toàn bảng. Vì vậy định danh một dòng
--   luôn là cặp (id, occurred_at): mọi tra cứu theo dòng phải dùng cả hai.
-- Chỉ ghi thêm: trigger chặn UPDATE; DELETE chỉ được làm bởi chủ sở hữu bảng (hàm
--   bảo trì partition chuyển dòng khỏi DEFAULT). V14 còn thu hồi quyền UPDATE,
--   DELETE của role ứng dụng.
-- Truy cập: KHÔNG tạo JPA entity. Ghi và đọc bằng JdbcClient (ghi nhiều, đọc theo
--   khoảng thời gian; luôn kèm điều kiện occurred_at để PostgreSQL chỉ quét partition cần).
-- -----------------------------------------------------------------------------
CREATE TABLE audit_log (
    id               BIGINT GENERATED ALWAYS AS IDENTITY,
    organization_id  BIGINT       NOT NULL REFERENCES organization (id),
    occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor_type       VARCHAR(10)  NOT NULL,
    actor_user_id    BIGINT,
    org_unit_id      BIGINT,
    action           VARCHAR(100) NOT NULL,
    entity_type      VARCHAR(50),
    entity_id        BIGINT,
    result           VARCHAR(10)  NOT NULL,
    reason           VARCHAR(500),
    before_data      JSONB,
    after_data       JSONB,
    ip_address       INET,
    user_agent       VARCHAR(500),
    request_id       VARCHAR(64),
    CONSTRAINT pk_audit_log PRIMARY KEY (id, occurred_at),
    CONSTRAINT fk_audit_log_actor FOREIGN KEY (organization_id, actor_user_id)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT fk_audit_log_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT ck_audit_log_actor_type CHECK (actor_type IN ('USER', 'AI', 'SYSTEM')),
    CONSTRAINT ck_audit_log_result     CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED')),
    CONSTRAINT ck_audit_log_user_actor CHECK (actor_type <> 'USER' OR actor_user_id IS NOT NULL)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE audit_log_default PARTITION OF audit_log DEFAULT;
SELECT ensure_monthly_partitions('audit_log', 3);

CREATE INDEX ix_audit_log_org_time ON audit_log (organization_id, occurred_at DESC);
CREATE INDEX ix_audit_log_entity   ON audit_log (organization_id, entity_type, entity_id);
CREATE INDEX ix_audit_log_actor    ON audit_log (organization_id, actor_user_id, occurred_at DESC);
COMMENT ON TABLE audit_log IS 'Lịch sử thao tác quan trọng. Chỉ ghi thêm (trigger + quyền). Chia partition theo tháng; định danh dòng = (id, occurred_at).';
COMMENT ON COLUMN audit_log.actor_type IS 'USER, AI hoặc SYSTEM.';
COMMENT ON COLUMN audit_log.actor_user_id IS 'Người thực hiện; bắt buộc khi actor_type = USER.';
COMMENT ON COLUMN audit_log.org_unit_id IS 'Đơn vị nơi thao tác diễn ra.';
COMMENT ON COLUMN audit_log.action IS 'Mã hành động, vd iam.role.assign, sales.order.cancel.';
COMMENT ON COLUMN audit_log.entity_type IS 'Loại đối tượng bị tác động, vd role_assignment.';
COMMENT ON COLUMN audit_log.result IS 'SUCCESS, FAILURE hoặc DENIED (truy cập trái quyền).';
COMMENT ON COLUMN audit_log.before_data IS 'Giá trị trước khi đổi (JSON).';
COMMENT ON COLUMN audit_log.after_data IS 'Giá trị sau khi đổi (JSON).';
COMMENT ON COLUMN audit_log.request_id IS 'Mã request để nối với log ứng dụng.';


-- Chặn sửa audit_log; chỉ chủ sở hữu bảng được xoá (hàm bảo trì partition).
-- Kiểm tra theo role, không theo biến phiên, nên phiên SQL thường không tự mở được.
CREATE OR REPLACE FUNCTION audit_log_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE'
       AND pg_has_role(current_user, (SELECT relowner FROM pg_class WHERE oid = TG_RELID), 'MEMBER') THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'audit_log chỉ ghi thêm: không sửa, không xoá (id %)', OLD.id USING ERRCODE = 'OPS01';
END;
$$;

CREATE TRIGGER trg_audit_log_guard
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_guard();

-- ############################################################################
-- ## V5__catalog.sql
-- ############################################################################

-- =============================================================================
-- V5__catalog.sql — Danh mục bán hàng
-- Bảng: product_category, unit_of_measure, product, product_price, payment_method,
--       customer, supplier
-- Phụ thuộc: V1 (organization, org_unit), V2 (extension btree_gist)
-- Quy tắc nghiệp vụ: tài liệu "OpsPilot — Quy tắc nghiệp vụ Bán hàng, Thanh toán, Kho"
-- Số version V5–V8 là tạm: nếu làm Task (đợt 2) trước, đổi số cho đúng thứ tự
-- thực tế trước lần chạy đầu tiên.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- product_category — Nhóm sản phẩm
--
-- Vai trò: gom sản phẩm thành nhóm để lọc, báo cáo doanh số theo nhóm và làm
--   đặc trưng cho mô hình dự báo (nhóm "Đồ uống" bán chạy cuối tuần...).
-- Có thể lồng nhau một cấp hay nhiều cấp qua parent_id (cùng doanh nghiệp).
-- -----------------------------------------------------------------------------
CREATE TABLE product_category (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organization (id),
    parent_id        BIGINT,
    code             VARCHAR(50)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    sort_order       INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT          NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT uq_product_category_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_product_category_parent FOREIGN KEY (organization_id, parent_id)
        REFERENCES product_category (organization_id, id),
    CONSTRAINT ck_product_category_not_self_parent CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE UNIQUE INDEX uq_product_category_code ON product_category (organization_id, lower(code))
    WHERE deleted_at IS NULL;
CREATE INDEX ix_product_category_parent ON product_category (organization_id, parent_id);
COMMENT ON TABLE product_category IS 'Nhóm sản phẩm, lồng nhau được. Xoá mềm.';
COMMENT ON COLUMN product_category.parent_id IS 'Nhóm cha cùng doanh nghiệp; NULL là nhóm cấp cao nhất.';


-- -----------------------------------------------------------------------------
-- unit_of_measure — Đơn vị tính
--
-- Vai trò: danh mục đơn vị tính của doanh nghiệp (INV-02): cái, hộp, kg, lon,
--   thùng... Mỗi sản phẩm có một đơn vị cơ sở (product.unit_id); tồn kho và số
--   lượng trên đơn luôn tính theo đơn vị cơ sở.
-- Quy đổi: đơn vị đóng gói trỏ về đơn vị cơ sở kèm hệ số, vd THUNG24 = 24 × LON
--   (base_unit_id = LON, conversion_factor = 24). Service quy đổi khi nhập/xuất
--   theo thùng. Đóng gói khác nhau thì tạo mã khác nhau (THUNG12, THUNG24).
-- allow_decimal: FALSE thì số lượng phải là số nguyên (cái, hộp); TRUE cho kg, lít.
-- -----------------------------------------------------------------------------
CREATE TABLE unit_of_measure (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id    BIGINT         NOT NULL REFERENCES organization (id),
    code               VARCHAR(20)    NOT NULL,
    name               VARCHAR(50)    NOT NULL,
    base_unit_id       BIGINT,
    conversion_factor  NUMERIC(14, 6),
    allow_decimal      BOOLEAN        NOT NULL DEFAULT FALSE,
    status             VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by         BIGINT,
    version            INT            NOT NULL DEFAULT 0,
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT uq_unit_of_measure_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_unit_of_measure_base FOREIGN KEY (organization_id, base_unit_id)
        REFERENCES unit_of_measure (organization_id, id),
    CONSTRAINT ck_unit_of_measure_conversion CHECK (
        (base_unit_id IS NULL) = (conversion_factor IS NULL)
        AND (conversion_factor IS NULL OR conversion_factor > 0)
        AND (base_unit_id IS NULL OR base_unit_id <> id)),
    CONSTRAINT ck_unit_of_measure_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE UNIQUE INDEX uq_unit_of_measure_code ON unit_of_measure (organization_id, lower(code))
    WHERE deleted_at IS NULL;
COMMENT ON TABLE unit_of_measure IS 'Đơn vị tính; đơn vị đóng gói quy đổi về đơn vị cơ sở bằng hệ số.';
COMMENT ON COLUMN unit_of_measure.base_unit_id IS 'Đơn vị cơ sở nếu đây là đơn vị đóng gói; NULL nếu chính nó là đơn vị cơ sở.';
COMMENT ON COLUMN unit_of_measure.conversion_factor IS '1 đơn vị này = conversion_factor × đơn vị cơ sở.';
COMMENT ON COLUMN unit_of_measure.allow_decimal IS 'Cho phép số lượng lẻ (kg, lít).';


-- -----------------------------------------------------------------------------
-- product — Sản phẩm và dịch vụ
--
-- Vai trò: danh mục thứ được bán (SALES-01, INV-01). Dịch vụ dùng chung bảng này
--   với product_type = SERVICE (SERVICE-01).
-- track_inventory: TRUE thì có tồn kho, được giữ hàng và xuất kho; dịch vụ luôn
--   FALSE (BR-ORD-14). Dòng đơn hàng chụp lại giá trị này lúc tạo, nên đổi cờ về
--   sau không ảnh hưởng đơn cũ. Service chặn đổi cờ khi sản phẩm còn tồn.
-- code ổn định theo thời gian (nguyên tắc 7 của kho dữ liệu): đổi tên thì giữ mã.
-- -----------------------------------------------------------------------------
CREATE TABLE product (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT        NOT NULL REFERENCES organization (id),
    category_id      BIGINT,
    code             VARCHAR(50)   NOT NULL,
    name             VARCHAR(255)  NOT NULL,
    product_type     VARCHAR(20)   NOT NULL DEFAULT 'GOODS',
    unit_id          BIGINT        NOT NULL,
    barcode          VARCHAR(50),
    track_inventory  BOOLEAN       NOT NULL DEFAULT TRUE,
    status           VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    description      VARCHAR(1000),
    metadata         JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT           NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT uq_product_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_product_category FOREIGN KEY (organization_id, category_id)
        REFERENCES product_category (organization_id, id),
    CONSTRAINT fk_product_unit FOREIGN KEY (organization_id, unit_id)
        REFERENCES unit_of_measure (organization_id, id),
    CONSTRAINT ck_product_type   CHECK (product_type IN ('GOODS', 'SERVICE')),
    CONSTRAINT ck_product_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_product_service_no_stock CHECK (product_type <> 'SERVICE' OR NOT track_inventory)
);
CREATE UNIQUE INDEX uq_product_code ON product (organization_id, lower(code)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_product_barcode ON product (organization_id, barcode)
    WHERE barcode IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_product_category ON product (organization_id, category_id);
COMMENT ON TABLE product IS 'Sản phẩm (GOODS) và dịch vụ (SERVICE). Xoá mềm.';
COMMENT ON COLUMN product.code IS 'Mã sản phẩm, không đổi theo thời gian; unique trong doanh nghiệp.';
COMMENT ON COLUMN product.product_type IS 'GOODS: hàng hoá; SERVICE: dịch vụ (không quản lý tồn).';
COMMENT ON COLUMN product.unit_id IS 'Đơn vị tính cơ sở; tồn kho và số lượng trên đơn tính theo đơn vị này.';
COMMENT ON COLUMN product.track_inventory IS 'TRUE: có tồn kho, giữ hàng khi xác nhận đơn, trừ khi xuất (BR-INV-01).';
COMMENT ON COLUMN product.status IS 'ACTIVE: đang bán; INACTIVE: ngừng bán, không thêm được vào đơn mới.';


-- -----------------------------------------------------------------------------
-- product_price — Lịch sử giá bán
--
-- Vai trò: giá bán theo thời gian (SALES-02). Đổi giá = đóng dòng hiện tại
--   (valid_to = thời điểm đổi) rồi thêm dòng mới; không sửa giá cũ, nên luôn biết
--   giá ở bất kỳ thời điểm nào (cần cho phân tích độ nhạy giá).
-- Ràng buộc: một sản phẩm không có hai khoảng giá chồng nhau.
-- Giá theo từng chi nhánh: chưa hỗ trợ (MỞ).
-- -----------------------------------------------------------------------------
CREATE TABLE product_price (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT         NOT NULL REFERENCES organization (id),
    product_id       BIGINT         NOT NULL,
    price            NUMERIC(18, 2) NOT NULL,
    valid_from       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    valid_to         TIMESTAMPTZ,
    note             VARCHAR(500),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT            NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_price_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_product_price_product FOREIGN KEY (organization_id, product_id)
        REFERENCES product (organization_id, id),
    CONSTRAINT ck_product_price_amount CHECK (price >= 0),
    CONSTRAINT ck_product_price_range  CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ex_product_price_overlap EXCLUDE USING gist (
        product_id WITH =,
        tstzrange(valid_from, valid_to, '[)') WITH &&
    )
);
COMMENT ON TABLE product_price IS 'Giá bán theo khoảng thời gian; không chồng khoảng. Đổi giá = đóng dòng cũ, thêm dòng mới.';
COMMENT ON COLUMN product_price.valid_to IS 'NULL: giá đang áp dụng.';


-- -----------------------------------------------------------------------------
-- payment_method — Hình thức thanh toán
--
-- Vai trò: danh mục cách khách trả tiền (SALES-07): tiền mặt, chuyển khoản, thẻ,
--   ví điện tử. Doanh nghiệp tự đặt tên; method_type dùng để gom báo cáo và đối
--   soát tiền mặt cuối ca.
-- Khi tạo doanh nghiệp, service tạo sẵn CASH và BANK_TRANSFER.
-- -----------------------------------------------------------------------------
CREATE TABLE payment_method (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organization (id),
    code             VARCHAR(30)  NOT NULL,
    name             VARCHAR(100) NOT NULL,
    method_type      VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    sort_order       INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT          NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT uq_payment_method_org_id UNIQUE (organization_id, id),
    CONSTRAINT ck_payment_method_type   CHECK (method_type IN ('CASH', 'BANK_TRANSFER', 'CARD', 'E_WALLET', 'OTHER')),
    CONSTRAINT ck_payment_method_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE UNIQUE INDEX uq_payment_method_code ON payment_method (organization_id, lower(code))
    WHERE deleted_at IS NULL;
COMMENT ON TABLE payment_method IS 'Hình thức thanh toán của doanh nghiệp. Xoá mềm.';
COMMENT ON COLUMN payment_method.method_type IS 'CASH, BANK_TRANSFER, CARD, E_WALLET, OTHER; dùng để gom báo cáo.';


-- -----------------------------------------------------------------------------
-- customer — Khách hàng
--
-- Vai trò: khách mua hàng, đặt cọc, đặt lịch dịch vụ; cần cho đơn đặt trước (giao
--   sau), công nợ, lịch hẹn và phân nhóm khách.
-- DỮ LIỆU CÁ NHÂN: full_name, phone, email, address, date_of_birth, tax_code.
--   - Không đưa các cột này sang kho dữ liệu hay bộ dữ liệu huấn luyện; kho chỉ
--     nhận id, customer_type, segment, home_unit_id.
--   - Gửi tin quảng cáo chỉ khi marketing_consent = TRUE (Nghị định 13/2023 về bảo
--     vệ dữ liệu cá nhân).
--   - Khách yêu cầu xoá: service ẩn danh các cột cá nhân và ghi anonymized_at,
--     không xoá dòng, để đơn cũ và báo cáo vẫn đúng.
-- Đơn bán lẻ tại quầy không bắt buộc có khách (sales_order.customer_id NULL).
-- -----------------------------------------------------------------------------
CREATE TABLE customer (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id    BIGINT        NOT NULL REFERENCES organization (id),
    code               VARCHAR(30)   NOT NULL,
    customer_type      VARCHAR(20)   NOT NULL DEFAULT 'INDIVIDUAL',
    full_name          VARCHAR(200)  NOT NULL,
    phone              VARCHAR(20),
    email              VARCHAR(255),
    address            VARCHAR(500),
    date_of_birth      DATE,
    gender             VARCHAR(10),
    tax_code           VARCHAR(20),
    home_unit_id       BIGINT,
    segment            VARCHAR(30),
    marketing_consent  BOOLEAN       NOT NULL DEFAULT FALSE,
    consent_at         TIMESTAMPTZ,
    anonymized_at      TIMESTAMPTZ,
    status             VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    note               VARCHAR(1000),
    metadata           JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by         BIGINT,
    version            INT           NOT NULL DEFAULT 0,
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT uq_customer_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_customer_home_unit FOREIGN KEY (organization_id, home_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT ck_customer_type    CHECK (customer_type IN ('INDIVIDUAL', 'BUSINESS')),
    CONSTRAINT ck_customer_gender  CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER')),
    CONSTRAINT ck_customer_status  CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_customer_consent CHECK (NOT marketing_consent OR consent_at IS NOT NULL)
);
CREATE UNIQUE INDEX uq_customer_code ON customer (organization_id, lower(code)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_customer_phone ON customer (organization_id, phone)
    WHERE phone IS NOT NULL AND deleted_at IS NULL;
COMMENT ON TABLE customer IS 'Khách hàng. Có dữ liệu cá nhân: không đưa sang kho dữ liệu; xoá theo yêu cầu bằng ẩn danh.';
COMMENT ON COLUMN customer.segment IS 'Nhóm khách do doanh nghiệp đặt (VIP, thường...); được đưa sang kho dữ liệu.';
COMMENT ON COLUMN customer.marketing_consent IS 'Đồng ý nhận tin quảng cáo; TRUE thì bắt buộc có consent_at.';
COMMENT ON COLUMN customer.anonymized_at IS 'Lúc các cột cá nhân bị ẩn danh theo yêu cầu của khách.';


-- -----------------------------------------------------------------------------
-- supplier — Nhà cung cấp
--
-- Vai trò: nơi nhập hàng (INV-14). Phiếu nhập kho (inventory_movement RECEIPT)
--   ghi supplier_id; lô hàng ghi nhà cung cấp.
-- lead_time_days: số ngày từ lúc đặt tới lúc nhận hàng; đầu vào cho đề xuất bổ
--   sung tồn kho (AI-11, INV-17).
-- -----------------------------------------------------------------------------
CREATE TABLE supplier (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id     BIGINT        NOT NULL REFERENCES organization (id),
    code                VARCHAR(30)   NOT NULL,
    name                VARCHAR(255)  NOT NULL,
    tax_code            VARCHAR(20),
    contact_name        VARCHAR(200),
    phone               VARCHAR(20),
    email               VARCHAR(255),
    address             VARCHAR(500),
    lead_time_days      SMALLINT,
    payment_terms_days  SMALLINT,
    status              VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    note                VARCHAR(1000),
    metadata            JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by          BIGINT,
    version             INT           NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_supplier_org_id UNIQUE (organization_id, id),
    CONSTRAINT ck_supplier_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_supplier_days   CHECK (
        (lead_time_days IS NULL OR lead_time_days >= 0)
        AND (payment_terms_days IS NULL OR payment_terms_days >= 0))
);
CREATE UNIQUE INDEX uq_supplier_code ON supplier (organization_id, lower(code)) WHERE deleted_at IS NULL;
COMMENT ON TABLE supplier IS 'Nhà cung cấp. Xoá mềm.';
COMMENT ON COLUMN supplier.lead_time_days IS 'Số ngày giao hàng thường lệ; dùng cho đề xuất bổ sung tồn kho.';
COMMENT ON COLUMN supplier.payment_terms_days IS 'Số ngày được nợ tiền hàng.';

-- ############################################################################
-- ## V6__inventory.sql
-- ############################################################################

-- =============================================================================
-- V6__inventory.sql — Tồn kho
-- Bảng: stock_balance, product_batch, inventory_movement, stock_count,
--       stock_count_item, stock_transfer, stock_transfer_item
-- Hàm dùng chung: forbid_update_delete() (bảng chỉ ghi thêm)
-- Phụ thuộc: V1 (organization, org_unit), V5 (product, supplier)
--
-- Kho không có bảng riêng: mỗi kho là một org_unit loại WAREHOUSE, hoặc chính chi
-- nhánh nếu chi nhánh tự giữ hàng. Service kiểm tra loại đơn vị khi ghi kho.
--
-- Mã lỗi riêng do trigger trả về (service đổi sang ProblemDetail):
--   OPS01  sửa hoặc xoá dòng ở bảng chỉ ghi thêm
-- =============================================================================

-- Chặn UPDATE/DELETE trên bảng sổ (ledger). Sai thì ghi dòng mới để bù, không sửa.
CREATE OR REPLACE FUNCTION forbid_update_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Bảng % chỉ ghi thêm: không sửa, không xoá (id %)', TG_TABLE_NAME, OLD.id
        USING ERRCODE = 'OPS01',
              HINT = 'Ghi một dòng mới để điều chỉnh thay vì sửa dòng cũ.';
END;
$$;
COMMENT ON FUNCTION forbid_update_delete() IS 'Trigger cho bảng chỉ ghi thêm. Lỗi OPS01.';


-- -----------------------------------------------------------------------------
-- stock_balance — Tồn hiện tại theo kho và sản phẩm
--
-- Vai trò: trả lời nhanh "kho này còn bao nhiêu, đang giữ bao nhiêu, bán được
--   bao nhiêu" mà không phải cộng lại toàn bộ sổ biến động (INV-06).
--   on_hand_qty   : hàng thực tế trong kho
--   reserved_qty  : đang giữ cho đơn đã xác nhận, chưa xuất (BR-INV-01)
--   available_qty : on_hand − reserved, phần còn bán được (cột sinh tự động)
-- Là bản tổng hợp của inventory_movement: service cập nhật trong CÙNG giao dịch
--   với dòng biến động, bằng UPDATE có điều kiện (BR-CON-03). Job đối soát đêm
--   kiểm tra on_hand_qty = tổng quantity trong sổ (BR-INV-10).
-- Không có version: không sửa qua JPA. Entity JPA chỉ đọc; ghi bằng JdbcClient.
-- Không chặn available_qty < 0: kiểm kê thấy thiếu có thể làm số đang giữ lớn
--   hơn hàng thật, phải ghi đúng thực tế rồi cảnh báo (BR-INV-09).
-- -----------------------------------------------------------------------------
CREATE TABLE stock_balance (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id    BIGINT         NOT NULL REFERENCES organization (id),
    warehouse_unit_id  BIGINT         NOT NULL,
    product_id         BIGINT         NOT NULL,
    on_hand_qty        NUMERIC(14, 3) NOT NULL DEFAULT 0,
    reserved_qty       NUMERIC(14, 3) NOT NULL DEFAULT 0,
    available_qty      NUMERIC(14, 3) GENERATED ALWAYS AS (on_hand_qty - reserved_qty) STORED,
    avg_unit_cost      NUMERIC(18, 4),
    last_movement_at   TIMESTAMPTZ,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_stock_balance_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_stock_balance_key UNIQUE (organization_id, warehouse_unit_id, product_id),
    CONSTRAINT fk_stock_balance_warehouse FOREIGN KEY (organization_id, warehouse_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_stock_balance_product FOREIGN KEY (organization_id, product_id)
        REFERENCES product (organization_id, id),
    CONSTRAINT ck_stock_balance_on_hand  CHECK (on_hand_qty >= 0),
    CONSTRAINT ck_stock_balance_reserved CHECK (reserved_qty >= 0),
    CONSTRAINT ck_stock_balance_cost     CHECK (avg_unit_cost IS NULL OR avg_unit_cost >= 0)
);
CREATE INDEX ix_stock_balance_product ON stock_balance (organization_id, product_id);
COMMENT ON TABLE stock_balance IS 'Tồn hiện tại theo kho × sản phẩm; bản tổng hợp của inventory_movement, cập nhật cùng giao dịch.';
COMMENT ON COLUMN stock_balance.warehouse_unit_id IS 'Kho: org_unit loại WAREHOUSE hoặc chi nhánh tự giữ hàng.';
COMMENT ON COLUMN stock_balance.on_hand_qty IS 'Hàng thực tế. Không âm (BR-INV-08, đề xuất cho BD-05).';
COMMENT ON COLUMN stock_balance.reserved_qty IS 'Đang giữ cho đơn CONFIRMED chưa xuất. Tăng khi xác nhận, giảm khi xuất hoặc huỷ.';
COMMENT ON COLUMN stock_balance.available_qty IS 'on_hand − reserved. Cột sinh; JPA map insertable = false, updatable = false.';
COMMENT ON COLUMN stock_balance.avg_unit_cost IS 'Giá vốn bình quân. Để trống cho tới khi chốt BD-09.';


-- -----------------------------------------------------------------------------
-- product_batch — Lô hàng và hạn sử dụng
--
-- Vai trò: số lô, ngày sản xuất, hạn dùng của hàng nhập (INV-15, P2). Biến động
--   kho của sản phẩm có quản lý lô ghi batch_id; tồn theo lô tính từ sổ biến động.
-- Cảnh báo sắp hết hạn và dự báo hàng hết hạn (AI) dựa trên expires_on.
-- -----------------------------------------------------------------------------
CREATE TABLE product_batch (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organization (id),
    product_id       BIGINT       NOT NULL,
    batch_no         VARCHAR(50)  NOT NULL,
    manufactured_on  DATE,
    expires_on       DATE,
    supplier_id      BIGINT,
    note             VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_batch_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_product_batch_product_id UNIQUE (organization_id, product_id, id),
    CONSTRAINT fk_product_batch_product FOREIGN KEY (organization_id, product_id)
        REFERENCES product (organization_id, id),
    CONSTRAINT fk_product_batch_supplier FOREIGN KEY (organization_id, supplier_id)
        REFERENCES supplier (organization_id, id),
    CONSTRAINT ck_product_batch_dates CHECK (
        expires_on IS NULL OR manufactured_on IS NULL OR expires_on >= manufactured_on)
);
CREATE UNIQUE INDEX uq_product_batch_no ON product_batch (organization_id, product_id, lower(batch_no));
CREATE INDEX ix_product_batch_expiry ON product_batch (organization_id, expires_on) WHERE expires_on IS NOT NULL;
COMMENT ON TABLE product_batch IS 'Lô hàng: số lô, ngày sản xuất, hạn dùng.';
COMMENT ON COLUMN product_batch.expires_on IS 'Hạn sử dụng; dùng cho cảnh báo sắp hết hạn.';


-- -----------------------------------------------------------------------------
-- inventory_movement — Sổ biến động kho
--
-- Vai trò: mỗi lần hàng vào hoặc ra kho là một dòng (INV-04, 05, 07, 09). Đây là
--   nguồn sự thật của tồn kho; stock_balance chỉ là số tổng hợp.
-- Chỉ ghi thêm (BR-INV-04): không sửa, không xoá. Ghi sai thì thêm dòng
--   ADJUSTMENT có reversal_of_id trỏ về dòng sai.
-- quantity có dấu: dương là vào kho, âm là ra kho (BR-INV-05).
-- Tham chiếu chứng từ gốc bằng ref_type/ref_id/ref_line_id, không đặt khoá ngoại
--   sang bảng của module khác (Spring Modulith): Inventory không phụ thuộc Sales.
--   Service Sales truyền tham chiếu khi gọi Inventory; job đối soát kiểm tra.
-- Giữ hàng KHÔNG ghi vào sổ, vì không làm đổi hàng thực tế.
-- Hai mốc thời gian: occurred_at (lúc hàng thật sự vào/ra) và created_at (lúc ghi).
-- -----------------------------------------------------------------------------
CREATE TABLE inventory_movement (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id    BIGINT         NOT NULL REFERENCES organization (id),
    warehouse_unit_id  BIGINT         NOT NULL,
    product_id         BIGINT         NOT NULL,
    movement_type      VARCHAR(30)    NOT NULL,
    quantity           NUMERIC(14, 3) NOT NULL,
    unit_cost          NUMERIC(18, 4),
    occurred_at        TIMESTAMPTZ    NOT NULL,
    batch_id           BIGINT,
    supplier_id        BIGINT,
    ref_type           VARCHAR(30),
    ref_id             BIGINT,
    ref_line_id        BIGINT,
    reversal_of_id     BIGINT,
    reason             VARCHAR(500),
    source             VARCHAR(20)    NOT NULL DEFAULT 'MANUAL',
    import_job_id      BIGINT,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         BIGINT,
    CONSTRAINT uq_inventory_movement_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_inventory_movement_warehouse FOREIGN KEY (organization_id, warehouse_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_inventory_movement_product FOREIGN KEY (organization_id, product_id)
        REFERENCES product (organization_id, id),
    CONSTRAINT fk_inventory_movement_reversal FOREIGN KEY (organization_id, reversal_of_id)
        REFERENCES inventory_movement (organization_id, id),
    CONSTRAINT fk_inventory_movement_batch FOREIGN KEY (organization_id, product_id, batch_id)
        REFERENCES product_batch (organization_id, product_id, id),
    CONSTRAINT fk_inventory_movement_supplier FOREIGN KEY (organization_id, supplier_id)
        REFERENCES supplier (organization_id, id),
    CONSTRAINT ck_inventory_movement_supplier CHECK (supplier_id IS NULL OR movement_type = 'RECEIPT'),
    CONSTRAINT ck_inventory_movement_type CHECK (movement_type IN (
        'OPENING', 'RECEIPT', 'SALE_ISSUE', 'SALE_RETURN',
        'ADJUSTMENT', 'COUNT_ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN')),
    CONSTRAINT ck_inventory_movement_sign CHECK (
           (movement_type IN ('OPENING', 'RECEIPT', 'SALE_RETURN', 'TRANSFER_IN') AND quantity > 0)
        OR (movement_type IN ('SALE_ISSUE', 'TRANSFER_OUT')                     AND quantity < 0)
        OR (movement_type IN ('ADJUSTMENT', 'COUNT_ADJUSTMENT')                 AND quantity <> 0)),
    CONSTRAINT ck_inventory_movement_ref_type CHECK (ref_type IS NULL OR ref_type IN (
        'SALES_ORDER', 'SALES_RETURN', 'STOCK_COUNT', 'STOCK_TRANSFER', 'GOODS_RECEIPT')),
    CONSTRAINT ck_inventory_movement_ref_pair CHECK ((ref_type IS NULL) = (ref_id IS NULL)),
    CONSTRAINT ck_inventory_movement_sale_ref CHECK (
        movement_type <> 'SALE_ISSUE' OR (ref_type = 'SALES_ORDER' AND ref_line_id IS NOT NULL)),
    CONSTRAINT ck_inventory_movement_return_ref CHECK (
        movement_type <> 'SALE_RETURN' OR (ref_type = 'SALES_RETURN' AND ref_line_id IS NOT NULL)),
    CONSTRAINT ck_inventory_movement_reason CHECK (
        movement_type NOT IN ('ADJUSTMENT', 'COUNT_ADJUSTMENT') OR reason IS NOT NULL),
    CONSTRAINT ck_inventory_movement_reversal CHECK (reversal_of_id IS NULL OR movement_type = 'ADJUSTMENT'),
    CONSTRAINT ck_inventory_movement_source CHECK (source IN ('MANUAL', 'IMPORT', 'API')),
    CONSTRAINT ck_inventory_movement_cost   CHECK (unit_cost IS NULL OR unit_cost >= 0)
);
CREATE INDEX ix_inventory_movement_ledger ON inventory_movement
    (organization_id, warehouse_unit_id, product_id, occurred_at);
CREATE INDEX ix_inventory_movement_ref ON inventory_movement (organization_id, ref_type, ref_id)
    WHERE ref_type IS NOT NULL;
-- BRIN: rất nhỏ, hợp với bảng ghi thêm theo thời gian; dùng cho ETL lấy dòng mới theo created_at.
CREATE INDEX ix_inventory_movement_created ON inventory_movement USING brin (created_at);

CREATE TRIGGER trg_inventory_movement_append_only
    BEFORE UPDATE OR DELETE ON inventory_movement
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();

COMMENT ON TABLE inventory_movement IS 'Sổ biến động kho, chỉ ghi thêm. Nguồn sự thật của tồn kho.';
COMMENT ON COLUMN inventory_movement.movement_type IS 'OPENING, RECEIPT, SALE_ISSUE, SALE_RETURN, ADJUSTMENT, COUNT_ADJUSTMENT, TRANSFER_OUT, TRANSFER_IN.';
COMMENT ON COLUMN inventory_movement.quantity IS 'Có dấu: dương vào kho, âm ra kho; dấu bị kiểm tra theo loại.';
COMMENT ON COLUMN inventory_movement.unit_cost IS 'Giá vốn đơn vị tại thời điểm biến động; để trống cho tới khi chốt BD-09.';
COMMENT ON COLUMN inventory_movement.occurred_at IS 'Lúc hàng thật sự vào/ra kho (giờ nghiệp vụ).';
COMMENT ON COLUMN inventory_movement.batch_id IS 'Lô hàng (sản phẩm có quản lý lô); khoá ngoại bảo đảm lô thuộc đúng sản phẩm.';
COMMENT ON COLUMN inventory_movement.supplier_id IS 'Nhà cung cấp; chỉ với RECEIPT.';
COMMENT ON COLUMN inventory_movement.ref_type IS 'Loại chứng từ gốc: SALES_ORDER, SALES_RETURN, STOCK_COUNT, STOCK_TRANSFER, GOODS_RECEIPT.';
COMMENT ON COLUMN inventory_movement.ref_id IS 'Id chứng từ gốc (vd sales_order.id). Không khoá ngoại, xem ghi chú đầu bảng.';
COMMENT ON COLUMN inventory_movement.ref_line_id IS 'Id dòng chứng từ (vd sales_order_item.id); bắt buộc với SALE_ISSUE, SALE_RETURN.';
COMMENT ON COLUMN inventory_movement.reversal_of_id IS 'Dòng bị đảo khi sửa sai; chỉ dùng với ADJUSTMENT.';
COMMENT ON COLUMN inventory_movement.reason IS 'Lý do; bắt buộc với ADJUSTMENT, COUNT_ADJUSTMENT.';
COMMENT ON COLUMN inventory_movement.source IS 'MANUAL, IMPORT hoặc API: dữ liệu đến từ đâu.';
COMMENT ON COLUMN inventory_movement.import_job_id IS 'Lần import tạo ra dòng này. Khoá ngoại thêm ở migration đợt 4.';


-- -----------------------------------------------------------------------------
-- stock_count — Phiếu kiểm kê
--
-- Vai trò: một lần đếm hàng thực tế tại một kho (INV-10). Vòng đời:
--   DRAFT → COUNTING → COMPLETED, hoặc → CANCELLED.
-- counted_at: mốc chụp số hệ thống để so sánh. Khi hoàn tất, mỗi dòng lệch tạo
--   một biến động COUNT_ADJUSTMENT (ref_type = STOCK_COUNT) trong cùng giao dịch.
-- Quy tắc nghiệp vụ chi tiết: BẢN NHÁP, chốt khi làm tài liệu quy tắc kho.
-- -----------------------------------------------------------------------------
CREATE TABLE stock_count (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id    BIGINT        NOT NULL REFERENCES organization (id),
    code               VARCHAR(30)   NOT NULL,
    warehouse_unit_id  BIGINT        NOT NULL,
    status             VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    counted_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    cancelled_at       TIMESTAMPTZ,
    note               VARCHAR(1000),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by         BIGINT,
    version            INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_count_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_stock_count_code   UNIQUE (organization_id, code),
    CONSTRAINT fk_stock_count_warehouse FOREIGN KEY (organization_id, warehouse_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT ck_stock_count_status CHECK (status IN ('DRAFT', 'COUNTING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_stock_count_times CHECK (
        (status NOT IN ('COUNTING', 'COMPLETED') OR counted_at IS NOT NULL)
        AND ((status = 'COMPLETED') = (completed_at IS NOT NULL))
        AND ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)))
);
CREATE INDEX ix_stock_count_warehouse ON stock_count (organization_id, warehouse_unit_id, counted_at DESC);
COMMENT ON TABLE stock_count IS 'Phiếu kiểm kê một kho. Hoàn tất thì sinh COUNT_ADJUSTMENT cho dòng lệch.';
COMMENT ON COLUMN stock_count.counted_at IS 'Mốc chụp số tồn hệ thống để so với số đếm.';


-- -----------------------------------------------------------------------------
-- stock_count_item — Dòng kiểm kê
--
-- Vai trò: số trên hệ thống và số đếm được của từng sản phẩm (hoặc từng lô).
--   difference_qty = counted − system (cột sinh). adjustment_movement_id trỏ tới
--   dòng sổ kho đã điều chỉnh, để biết dòng nào đã được xử lý.
-- -----------------------------------------------------------------------------
CREATE TABLE stock_count_item (
    id                      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id         BIGINT         NOT NULL REFERENCES organization (id),
    stock_count_id          BIGINT         NOT NULL,
    product_id              BIGINT         NOT NULL,
    batch_id                BIGINT,
    system_qty              NUMERIC(14, 3) NOT NULL,
    counted_qty             NUMERIC(14, 3),
    difference_qty          NUMERIC(14, 3) GENERATED ALWAYS AS (counted_qty - system_qty) STORED,
    reason                  VARCHAR(500),
    adjustment_movement_id  BIGINT,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by              BIGINT,
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by              BIGINT,
    version                 INT            NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_count_item_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_stock_count_item_line UNIQUE NULLS NOT DISTINCT (stock_count_id, product_id, batch_id),
    CONSTRAINT fk_stock_count_item_count FOREIGN KEY (organization_id, stock_count_id)
        REFERENCES stock_count (organization_id, id),
    CONSTRAINT fk_stock_count_item_product FOREIGN KEY (organization_id, product_id)
        REFERENCES product (organization_id, id),
    CONSTRAINT fk_stock_count_item_batch FOREIGN KEY (organization_id, product_id, batch_id)
        REFERENCES product_batch (organization_id, product_id, id),
    CONSTRAINT fk_stock_count_item_movement FOREIGN KEY (organization_id, adjustment_movement_id)
        REFERENCES inventory_movement (organization_id, id),
    CONSTRAINT ck_stock_count_item_qty CHECK (system_qty >= 0 AND (counted_qty IS NULL OR counted_qty >= 0)),
    CONSTRAINT ck_stock_count_item_adjusted CHECK (adjustment_movement_id IS NULL OR counted_qty IS NOT NULL)
);
COMMENT ON TABLE stock_count_item IS 'Dòng kiểm kê: số hệ thống, số đếm, chênh lệch và dòng sổ kho đã điều chỉnh.';
COMMENT ON COLUMN stock_count_item.counted_qty IS 'NULL khi chưa đếm.';
COMMENT ON COLUMN stock_count_item.difference_qty IS 'counted − system. Cột sinh.';


-- -----------------------------------------------------------------------------
-- stock_transfer — Phiếu điều chuyển kho
--
-- Vai trò: chuyển hàng từ kho này sang kho khác (INV-12). Vòng đời:
--   DRAFT → IN_TRANSIT (kho đi ghi TRANSFER_OUT) → RECEIVED (kho đến ghi
--   TRANSFER_IN), hoặc DRAFT → CANCELLED. Hàng đang đi đường không nằm trong tồn
--   của kho nào. Nhận thiếu: ghi received_qty, phần thiếu xử lý bằng điều chỉnh.
-- Quy tắc nghiệp vụ chi tiết: BẢN NHÁP.
-- -----------------------------------------------------------------------------
CREATE TABLE stock_transfer (
    id                      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id         BIGINT        NOT NULL REFERENCES organization (id),
    code                    VARCHAR(30)   NOT NULL,
    from_warehouse_unit_id  BIGINT        NOT NULL,
    to_warehouse_unit_id    BIGINT        NOT NULL,
    status                  VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    shipped_at              TIMESTAMPTZ,
    received_at             TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    cancel_reason           VARCHAR(500),
    note                    VARCHAR(1000),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by              BIGINT,
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by              BIGINT,
    version                 INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_transfer_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_stock_transfer_code   UNIQUE (organization_id, code),
    CONSTRAINT fk_stock_transfer_from FOREIGN KEY (organization_id, from_warehouse_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_stock_transfer_to FOREIGN KEY (organization_id, to_warehouse_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT ck_stock_transfer_route  CHECK (from_warehouse_unit_id <> to_warehouse_unit_id),
    CONSTRAINT ck_stock_transfer_status CHECK (status IN ('DRAFT', 'IN_TRANSIT', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT ck_stock_transfer_times CHECK (
        (status NOT IN ('IN_TRANSIT', 'RECEIVED') OR shipped_at IS NOT NULL)
        AND ((status = 'RECEIVED') = (received_at IS NOT NULL))
        AND ((status = 'CANCELLED') = (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL))
        AND (received_at IS NULL OR received_at >= shipped_at))
);
CREATE INDEX ix_stock_transfer_open ON stock_transfer (organization_id, status) WHERE status IN ('DRAFT', 'IN_TRANSIT');
COMMENT ON TABLE stock_transfer IS 'Phiếu điều chuyển giữa hai kho; hàng đi đường không thuộc tồn kho nào.';


-- -----------------------------------------------------------------------------
-- stock_transfer_item — Dòng điều chuyển
--
-- Vai trò: sản phẩm, số lượng gửi và số lượng nhận của một phiếu điều chuyển.
--   Danh mục ban đầu chỉ có stock_transfer; bảng dòng được thêm vì một phiếu
--   thường có nhiều sản phẩm. out_movement_id / in_movement_id trỏ tới hai dòng sổ kho.
-- -----------------------------------------------------------------------------
CREATE TABLE stock_transfer_item (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id    BIGINT         NOT NULL REFERENCES organization (id),
    stock_transfer_id  BIGINT         NOT NULL,
    line_no            SMALLINT       NOT NULL,
    product_id         BIGINT         NOT NULL,
    batch_id           BIGINT,
    quantity           NUMERIC(14, 3) NOT NULL,
    received_qty       NUMERIC(14, 3),
    out_movement_id    BIGINT,
    in_movement_id     BIGINT,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by         BIGINT,
    version            INT            NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_transfer_item_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_stock_transfer_item_line UNIQUE (stock_transfer_id, line_no),
    CONSTRAINT fk_stock_transfer_item_transfer FOREIGN KEY (organization_id, stock_transfer_id)
        REFERENCES stock_transfer (organization_id, id),
    CONSTRAINT fk_stock_transfer_item_product FOREIGN KEY (organization_id, product_id)
        REFERENCES product (organization_id, id),
    CONSTRAINT fk_stock_transfer_item_batch FOREIGN KEY (organization_id, product_id, batch_id)
        REFERENCES product_batch (organization_id, product_id, id),
    CONSTRAINT fk_stock_transfer_item_out FOREIGN KEY (organization_id, out_movement_id)
        REFERENCES inventory_movement (organization_id, id),
    CONSTRAINT fk_stock_transfer_item_in FOREIGN KEY (organization_id, in_movement_id)
        REFERENCES inventory_movement (organization_id, id),
    CONSTRAINT ck_stock_transfer_item_qty CHECK (
        quantity > 0 AND (received_qty IS NULL OR (received_qty >= 0 AND received_qty <= quantity)))
);
COMMENT ON TABLE stock_transfer_item IS 'Dòng điều chuyển: số gửi, số nhận, hai dòng sổ kho tương ứng.';
COMMENT ON COLUMN stock_transfer_item.received_qty IS 'Số nhận thực tế; NULL khi chưa nhận. Không vượt số gửi.';

-- ############################################################################
-- ## V7__sales.sql
-- ############################################################################

-- =============================================================================
-- V7__sales.sql — Bán hàng
-- Bảng: promotion, sales_order, order_status_history, sales_order_item,
--       sales_return, sales_return_item, payment
-- Phụ thuộc: V1 (organization, org_unit), V3 (employee), V5 (product,
--            product_category, payment_method, customer), V6 (hàm forbid_update_delete)
--
-- Ba trạng thái tách riêng (tài liệu quy tắc, mục 2):
--   order_status        DRAFT → CONFIRMED → COMPLETED, hoặc → CANCELLED
--   fulfillment_status  UNFULFILLED → PARTIALLY_FULFILLED → FULFILLED
--   payment_status      cột sinh, tự tính từ các cột tiền; không ai ghi tay
--
-- Mã lỗi riêng do trigger trả về:
--   OPS01  sửa/xoá bảng chỉ ghi thêm (order_status_history, sales_return_item)
--   OPS02  chuyển trạng thái đơn không hợp lệ
--   OPS03  sửa dòng hàng, hoặc sửa cột tiền/kho của đơn, khi đơn đã qua DRAFT
--   OPS04  sửa thông tin tiền của một dòng payment, hoặc xoá payment
--   OPS06  sửa hoặc xoá phiếu trả hàng
--
-- Luồng nhập lịch sử có kiểm soát (BR-ORD-16): đơn vào thẳng COMPLETED/CANCELLED và
-- thêm dòng vào đơn đã chốt CHỈ được khi đơn có import_job_id VÀ phiên đang chạy
-- bằng role opspilot_job (job import/đồng bộ, kết nối riêng). Điều kiện dựa trên
-- role đăng nhập, không dựa trên biến phiên, nên phiên của role ứng dụng không tự
-- mở được, kể cả khi bị chèn SQL.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- promotion — Chương trình khuyến mãi
--
-- Vai trò: định nghĩa khuyến mãi (SALES-10, P1): giảm theo % hoặc số tiền, áp cho
--   cả đơn, một sản phẩm hoặc một nhóm sản phẩm, trong khoảng thời gian, toàn
--   doanh nghiệp hoặc chỉ cây con của một đơn vị.
-- Khi áp vào đơn (lúc còn DRAFT), số tiền giảm được ghi cứng vào dòng/đơn và
--   promotion_id được lưu để phân tích. Sửa khuyến mãi về sau không đổi đơn cũ.
-- Cho mô hình học: promotion_id trên dòng hàng là đặc trưng "có khuyến mãi" khi
--   dự báo nhu cầu.
-- Cộng dồn nhiều khuyến mãi trên một đơn: MỞ. Quy tắc chi tiết: BẢN NHÁP.
-- -----------------------------------------------------------------------------
CREATE TABLE promotion (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id      BIGINT         NOT NULL REFERENCES organization (id),
    code                 VARCHAR(30)    NOT NULL,
    name                 VARCHAR(200)   NOT NULL,
    description          VARCHAR(1000),
    discount_type        VARCHAR(20)    NOT NULL,
    discount_value       NUMERIC(18, 2) NOT NULL,
    max_discount_amount  NUMERIC(18, 2),
    scope                VARCHAR(20)    NOT NULL,
    product_id           BIGINT,
    category_id          BIGINT,
    min_order_amount     NUMERIC(18, 2),
    org_unit_id          BIGINT,
    starts_at            TIMESTAMPTZ    NOT NULL,
    ends_at              TIMESTAMPTZ,
    usage_limit          INT,
    status               VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by           BIGINT,
    version              INT            NOT NULL DEFAULT 0,
    deleted_at           TIMESTAMPTZ,
    CONSTRAINT uq_promotion_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_promotion_product FOREIGN KEY (organization_id, product_id)
        REFERENCES product (organization_id, id),
    CONSTRAINT fk_promotion_category FOREIGN KEY (organization_id, category_id)
        REFERENCES product_category (organization_id, id),
    CONSTRAINT fk_promotion_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT ck_promotion_discount CHECK (
        discount_type IN ('PERCENT', 'FIXED_AMOUNT')
        AND discount_value > 0
        AND (discount_type <> 'PERCENT' OR discount_value <= 100)
        AND (max_discount_amount IS NULL OR (discount_type = 'PERCENT' AND max_discount_amount > 0))),
    CONSTRAINT ck_promotion_scope CHECK (
           (scope = 'ORDER'    AND product_id IS NULL     AND category_id IS NULL)
        OR (scope = 'PRODUCT'  AND product_id IS NOT NULL AND category_id IS NULL)
        OR (scope = 'CATEGORY' AND product_id IS NULL     AND category_id IS NOT NULL)),
    CONSTRAINT ck_promotion_period CHECK (ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_promotion_amounts CHECK (
        (min_order_amount IS NULL OR min_order_amount >= 0) AND (usage_limit IS NULL OR usage_limit > 0)),
    CONSTRAINT ck_promotion_status CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'ENDED'))
);
CREATE UNIQUE INDEX uq_promotion_code ON promotion (organization_id, lower(code)) WHERE deleted_at IS NULL;
CREATE INDEX ix_promotion_active ON promotion (organization_id, starts_at, ends_at) WHERE status = 'ACTIVE';
COMMENT ON TABLE promotion IS 'Khuyến mãi theo % hoặc số tiền, cho đơn/sản phẩm/nhóm. Số giảm được ghi cứng vào đơn khi áp.';
COMMENT ON COLUMN promotion.scope IS 'ORDER: cả đơn; PRODUCT: một sản phẩm; CATEGORY: một nhóm sản phẩm.';
COMMENT ON COLUMN promotion.org_unit_id IS 'NULL: toàn doanh nghiệp; có giá trị: chỉ cây con của đơn vị này.';


-- -----------------------------------------------------------------------------
-- sales_order — Đơn bán hàng
--
-- Vai trò: một giao dịch bán (SALES-03→09). Giữ ba trạng thái, các số tiền tổng
--   và các mốc thời gian của vòng đời đơn.
-- Các cột tiền:
--   subtotal_amount  tổng thành tiền các dòng trước giảm giá
--   discount_amount  tổng giảm giá (giảm theo dòng + giảm cấp đơn đã phân bổ)
--   total_amount     giá trị đơn = subtotal − discount + tax
--   returned_amount  tổng giá trị hàng khách đã trả lại
--   paid_amount      tổng tiền đã thu (payment PAYMENT chưa huỷ)
--   refunded_amount  tổng tiền đã hoàn (payment REFUND chưa huỷ)
--   Service cập nhật các số tổng trong cùng giao dịch, sau khi khoá dòng đơn
--   (SELECT ... FOR UPDATE, BR-CON-01). Job đối soát đêm kiểm tra lại.
-- Đơn luôn bắt đầu từ DRAFT, trừ đơn đi qua luồng nhập lịch sử có kiểm soát (xem
--   đầu file) được vào thẳng COMPLETED/CANCELLED kèm mốc thời gian gốc.
-- Từ CONFIRMED trở đi, không đổi cột tiền, kho, khuyến mãi, kênh, mã đơn (OPS03).
--   Vẫn được đổi: trạng thái theo vòng đời, các mốc thời gian, paid/refunded/
--   returned_amount, customer_id, salesperson_employee_id, note, metadata.
-- customer_id: không bắt buộc với bán lẻ tại quầy; nên có với đơn đặt trước,
--   bán chịu và đơn giao hàng.
-- -----------------------------------------------------------------------------
CREATE TABLE sales_order (
    id                       BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id          BIGINT         NOT NULL REFERENCES organization (id),
    code                     VARCHAR(30)    NOT NULL,
    org_unit_id              BIGINT         NOT NULL,
    warehouse_unit_id        BIGINT,
    salesperson_employee_id  BIGINT,
    customer_id              BIGINT,
    promotion_id             BIGINT,
    channel                  VARCHAR(20)    NOT NULL DEFAULT 'IN_STORE',
    order_status             VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    fulfillment_status       VARCHAR(20)    NOT NULL DEFAULT 'UNFULFILLED',
    currency                 CHAR(3)        NOT NULL DEFAULT 'VND',
    subtotal_amount          NUMERIC(18, 2) NOT NULL DEFAULT 0,
    discount_amount          NUMERIC(18, 2) NOT NULL DEFAULT 0,
    tax_amount               NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_amount             NUMERIC(18, 2) NOT NULL DEFAULT 0,
    returned_amount          NUMERIC(18, 2) NOT NULL DEFAULT 0,
    paid_amount              NUMERIC(18, 2) NOT NULL DEFAULT 0,
    refunded_amount          NUMERIC(18, 2) NOT NULL DEFAULT 0,
    -- Phải thu ròng = 0 nếu huỷ, ngược lại total − returned.
    -- Đã thu ròng   = paid − refunded.
    payment_status           VARCHAR(20)    GENERATED ALWAYS AS (
        CASE
            WHEN paid_amount - refunded_amount >
                 CASE WHEN order_status = 'CANCELLED' THEN 0 ELSE total_amount - returned_amount END
                THEN 'REFUND_DUE'
            WHEN (CASE WHEN order_status = 'CANCELLED' THEN 0 ELSE total_amount - returned_amount END) = 0
                THEN CASE WHEN paid_amount > 0 THEN 'REFUNDED' ELSE 'NOT_REQUIRED' END
            WHEN paid_amount - refunded_amount = 0
                THEN 'UNPAID'
            WHEN paid_amount - refunded_amount < total_amount - returned_amount
                THEN 'PARTIALLY_PAID'
            ELSE 'PAID'
        END
    ) STORED,
    order_at                 TIMESTAMPTZ    NOT NULL DEFAULT now(),
    confirmed_at             TIMESTAMPTZ,
    fulfilled_at             TIMESTAMPTZ,
    completed_at             TIMESTAMPTZ,
    cancelled_at             TIMESTAMPTZ,
    cancel_reason            VARCHAR(500),
    note                     VARCHAR(1000),
    source                   VARCHAR(20)    NOT NULL DEFAULT 'MANUAL',
    import_job_id            BIGINT,
    metadata                 JSONB          NOT NULL DEFAULT '{}'::jsonb,
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by               BIGINT,
    updated_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by               BIGINT,
    version                  INT            NOT NULL DEFAULT 0,
    CONSTRAINT uq_sales_order_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_sales_order_code   UNIQUE (organization_id, code),
    CONSTRAINT fk_sales_order_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_sales_order_warehouse FOREIGN KEY (organization_id, warehouse_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_sales_order_salesperson FOREIGN KEY (organization_id, salesperson_employee_id)
        REFERENCES employee (organization_id, id),
    CONSTRAINT fk_sales_order_customer FOREIGN KEY (organization_id, customer_id)
        REFERENCES customer (organization_id, id),
    CONSTRAINT fk_sales_order_promotion FOREIGN KEY (organization_id, promotion_id)
        REFERENCES promotion (organization_id, id),
    CONSTRAINT ck_sales_order_status CHECK (order_status IN ('DRAFT', 'CONFIRMED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_sales_order_fulfillment CHECK (
        fulfillment_status IN ('UNFULFILLED', 'PARTIALLY_FULFILLED', 'FULFILLED')),
    CONSTRAINT ck_sales_order_channel CHECK (channel IN ('IN_STORE', 'PHONE', 'ONLINE', 'MARKETPLACE', 'OTHER')),
    CONSTRAINT ck_sales_order_source  CHECK (source IN ('MANUAL', 'IMPORT', 'API')),
    -- Tiền
    CONSTRAINT ck_sales_order_amounts CHECK (
        subtotal_amount >= 0 AND discount_amount >= 0 AND tax_amount >= 0
        AND discount_amount <= subtotal_amount
        AND total_amount = subtotal_amount - discount_amount + tax_amount),
    CONSTRAINT ck_sales_order_returned CHECK (returned_amount >= 0 AND returned_amount <= total_amount),
    CONSTRAINT ck_sales_order_paid CHECK (
        paid_amount >= 0 AND refunded_amount >= 0 AND refunded_amount <= paid_amount),
    -- Ràng buộc giữa các trạng thái (mục 2)
    CONSTRAINT ck_sales_order_draft CHECK (
        order_status <> 'DRAFT'
        OR (fulfillment_status = 'UNFULFILLED' AND paid_amount = 0 AND confirmed_at IS NULL)),
    CONSTRAINT ck_sales_order_confirmed CHECK (
        order_status NOT IN ('CONFIRMED', 'COMPLETED') OR confirmed_at IS NOT NULL),
    CONSTRAINT ck_sales_order_completed CHECK (
        (order_status = 'COMPLETED') = (completed_at IS NOT NULL)
        AND (order_status <> 'COMPLETED' OR fulfillment_status = 'FULFILLED')),
    CONSTRAINT ck_sales_order_cancelled CHECK (
        (order_status = 'CANCELLED') = (cancelled_at IS NOT NULL)
        AND (order_status <> 'CANCELLED' OR (fulfillment_status = 'UNFULFILLED' AND cancel_reason IS NOT NULL))),
    CONSTRAINT ck_sales_order_fulfilled_at CHECK ((fulfillment_status = 'FULFILLED') = (fulfilled_at IS NOT NULL)),
    CONSTRAINT ck_sales_order_return_completed CHECK (returned_amount = 0 OR order_status = 'COMPLETED')
);
CREATE INDEX ix_sales_order_unit_time ON sales_order (organization_id, org_unit_id, order_at DESC);
CREATE INDEX ix_sales_order_open ON sales_order (organization_id, order_status)
    WHERE order_status IN ('DRAFT', 'CONFIRMED');
CREATE INDEX ix_sales_order_completed ON sales_order (organization_id, completed_at)
    WHERE completed_at IS NOT NULL;
CREATE INDEX ix_sales_order_updated ON sales_order (updated_at);
CREATE INDEX ix_sales_order_customer ON sales_order (organization_id, customer_id, order_at DESC)
    WHERE customer_id IS NOT NULL;
COMMENT ON TABLE sales_order IS 'Đơn bán hàng: ba trạng thái tách riêng, số tiền tổng và mốc thời gian vòng đời.';
COMMENT ON COLUMN sales_order.code IS 'Mã đơn, vd SO-2026-000123; cấp từ document_sequence (BR-ORD-12).';
COMMENT ON COLUMN sales_order.org_unit_id IS 'Đơn vị bán (chi nhánh).';
COMMENT ON COLUMN sales_order.warehouse_unit_id IS 'Kho giữ và xuất hàng; bắt buộc khi xác nhận nếu đơn có hàng quản lý tồn (service kiểm tra).';
COMMENT ON COLUMN sales_order.salesperson_employee_id IS 'Nhân viên được ghi nhận doanh số; có thể khác người tạo đơn.';
COMMENT ON COLUMN sales_order.customer_id IS 'Khách hàng; NULL với khách lẻ tại quầy.';
COMMENT ON COLUMN sales_order.promotion_id IS 'Khuyến mãi cấp đơn đã áp (số giảm đã nằm trong discount_amount).';
COMMENT ON COLUMN sales_order.order_status IS 'DRAFT, CONFIRMED, COMPLETED, CANCELLED. Trigger chặn chuyển sai (OPS02).';
COMMENT ON COLUMN sales_order.fulfillment_status IS 'UNFULFILLED, PARTIALLY_FULFILLED, FULFILLED. Service đổi khi xuất kho.';
COMMENT ON COLUMN sales_order.total_amount IS 'Giá trị đơn = subtotal − discount + tax. Không phải doanh thu (mục 7).';
COMMENT ON COLUMN sales_order.returned_amount IS 'Tổng giá trị hàng trả lại; chỉ khác 0 khi đơn COMPLETED.';
COMMENT ON COLUMN sales_order.paid_amount IS 'Tổng payment PAYMENT trạng thái RECORDED.';
COMMENT ON COLUMN sales_order.refunded_amount IS 'Tổng payment REFUND trạng thái RECORDED; không vượt paid_amount.';
COMMENT ON COLUMN sales_order.payment_status IS 'Cột sinh từ các cột tiền. JPA: @Generated, insertable = false, updatable = false.';
COMMENT ON COLUMN sales_order.order_at IS 'Lúc khách đặt (giờ nghiệp vụ).';
COMMENT ON COLUMN sales_order.completed_at IS 'Lúc giao xong; doanh thu ghi nhận theo mốc này (BR-REV-02).';
COMMENT ON COLUMN sales_order.source IS 'MANUAL, IMPORT hoặc API.';
COMMENT ON COLUMN sales_order.import_job_id IS 'Lần import tạo ra đơn. Khoá ngoại thêm ở migration đợt 4.';


-- -----------------------------------------------------------------------------
-- order_status_history — Lịch sử trạng thái đơn
--
-- Vai trò: mỗi lần order_status đổi là một dòng (BR-ORD-11): từ đâu sang đâu, ai
--   đổi, lúc nào, lý do. Trigger trên sales_order tự ghi, service không ghi tay.
--   changed_by lấy từ sales_order.updated_by (lúc tạo: created_by), nên service
--   phải luôn điền hai cột đó.
-- Hai mốc thời gian: changed_at (giờ nghiệp vụ, vd confirmed_at) và recorded_at
--   (lúc ghi vào DB). Chỉ ghi thêm.
-- -----------------------------------------------------------------------------
CREATE TABLE order_status_history (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organization (id),
    sales_order_id   BIGINT       NOT NULL,
    from_status      VARCHAR(20),
    to_status        VARCHAR(20)  NOT NULL,
    changed_at       TIMESTAMPTZ  NOT NULL,
    changed_by       BIGINT,
    reason           VARCHAR(500),
    recorded_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (organization_id, sales_order_id)
        REFERENCES sales_order (organization_id, id)
);
CREATE INDEX ix_order_status_history_order ON order_status_history (organization_id, sales_order_id, changed_at);

CREATE TRIGGER trg_order_status_history_append_only
    BEFORE UPDATE OR DELETE ON order_status_history
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();

COMMENT ON TABLE order_status_history IS 'Lịch sử order_status; trigger tự ghi, chỉ ghi thêm.';
COMMENT ON COLUMN order_status_history.from_status IS 'NULL ở dòng đầu tiên (lúc tạo đơn).';
COMMENT ON COLUMN order_status_history.changed_at IS 'Giờ nghiệp vụ của lần chuyển (confirmed_at, completed_at, cancelled_at...).';
COMMENT ON COLUMN order_status_history.reason IS 'Lý do; hiện chỉ có khi huỷ (lấy từ cancel_reason).';


-- TRUE khi phiên chạy bằng role opspilot_job (hoặc thành viên của nó).
-- Trả FALSE nếu role chưa được tạo, thay vì lỗi.
CREATE OR REPLACE FUNCTION app_is_job_role() RETURNS BOOLEAN
LANGUAGE sql STABLE AS $$
    SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opspilot_job')
                THEN pg_has_role(current_user, 'opspilot_job', 'MEMBER')
                ELSE FALSE END
$$;
COMMENT ON FUNCTION app_is_job_role() IS 'Phiên có chạy bằng role opspilot_job không (dùng cho luồng nhập lịch sử).';


-- Chặn chuyển trạng thái sai (BR-ORD-03→08).
CREATE OR REPLACE FUNCTION sales_order_guard_status() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.order_status <> 'DRAFT' AND NOT (NEW.import_job_id IS NOT NULL AND app_is_job_role()) THEN
            RAISE EXCEPTION 'Đơn mới phải bắt đầu ở DRAFT, nhận %. Chỉ luồng nhập lịch sử (import_job, role opspilot_job) được tạo đơn đã chốt', NEW.order_status
                USING ERRCODE = 'OPS02';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.order_status IS DISTINCT FROM OLD.order_status AND NOT (
           (OLD.order_status = 'DRAFT'     AND NEW.order_status IN ('CONFIRMED', 'CANCELLED'))
        OR (OLD.order_status = 'CONFIRMED' AND NEW.order_status IN ('COMPLETED', 'CANCELLED'))
    ) THEN
        RAISE EXCEPTION 'Không được chuyển đơn % từ % sang %', OLD.id, OLD.order_status, NEW.order_status
            USING ERRCODE = 'OPS02';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sales_order_guard_status
    BEFORE INSERT OR UPDATE OF order_status ON sales_order
    FOR EACH ROW EXECUTE FUNCTION sales_order_guard_status();


-- Khoá cột tiền, kho, khuyến mãi của đơn từ CONFIRMED trở đi; mã đơn không bao giờ đổi.
CREATE OR REPLACE FUNCTION sales_order_guard_locked() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.code IS DISTINCT FROM OLD.code
       OR NEW.organization_id IS DISTINCT FROM OLD.organization_id THEN
        RAISE EXCEPTION 'Không được đổi mã đơn hoặc doanh nghiệp của đơn %', OLD.id USING ERRCODE = 'OPS03';
    END IF;

    IF OLD.order_status <> 'DRAFT' AND (
           NEW.org_unit_id       IS DISTINCT FROM OLD.org_unit_id
        OR NEW.warehouse_unit_id IS DISTINCT FROM OLD.warehouse_unit_id
        OR NEW.channel           IS DISTINCT FROM OLD.channel
        OR NEW.currency          IS DISTINCT FROM OLD.currency
        OR NEW.subtotal_amount   IS DISTINCT FROM OLD.subtotal_amount
        OR NEW.discount_amount   IS DISTINCT FROM OLD.discount_amount
        OR NEW.tax_amount        IS DISTINCT FROM OLD.tax_amount
        OR NEW.total_amount      IS DISTINCT FROM OLD.total_amount
        OR NEW.promotion_id      IS DISTINCT FROM OLD.promotion_id
        OR NEW.order_at          IS DISTINCT FROM OLD.order_at
        OR NEW.source            IS DISTINCT FROM OLD.source
        OR NEW.import_job_id     IS DISTINCT FROM OLD.import_job_id) THEN
        RAISE EXCEPTION 'Đơn % đang ở %: không được sửa tiền, kho, khuyến mãi, kênh, nguồn', OLD.id, OLD.order_status
            USING ERRCODE = 'OPS03';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sales_order_guard_locked
    BEFORE UPDATE ON sales_order
    FOR EACH ROW EXECUTE FUNCTION sales_order_guard_locked();


-- Ghi lịch sử mỗi khi order_status đổi (BR-ORD-11).
CREATE OR REPLACE FUNCTION sales_order_log_status() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.order_status IS NOT DISTINCT FROM OLD.order_status THEN
        RETURN NULL;
    END IF;

    INSERT INTO order_status_history
        (organization_id, sales_order_id, from_status, to_status, changed_at, changed_by, reason)
    VALUES (
        NEW.organization_id,
        NEW.id,
        CASE WHEN TG_OP = 'UPDATE' THEN OLD.order_status END,
        NEW.order_status,
        COALESCE(CASE NEW.order_status
                     WHEN 'DRAFT'     THEN NEW.created_at
                     WHEN 'CONFIRMED' THEN NEW.confirmed_at
                     WHEN 'COMPLETED' THEN NEW.completed_at
                     WHEN 'CANCELLED' THEN NEW.cancelled_at
                 END, now()),
        CASE WHEN TG_OP = 'INSERT' THEN NEW.created_by ELSE NEW.updated_by END,
        CASE WHEN NEW.order_status = 'CANCELLED' THEN NEW.cancel_reason END
    );
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_sales_order_log_status
    AFTER INSERT OR UPDATE OF order_status ON sales_order
    FOR EACH ROW EXECUTE FUNCTION sales_order_log_status();


-- -----------------------------------------------------------------------------
-- sales_order_item — Dòng hàng trong đơn
--
-- Vai trò: sản phẩm, số lượng, giá và giảm giá TẠI THỜI ĐIỂM BÁN (SALES-04, 05).
--   Chụp lại product_name, unit_name, track_inventory để đơn cũ không đổi khi danh mục đổi.
-- Tiền mỗi dòng:
--   line_amount              = round(quantity × unit_price, 2)        (cột sinh)
--   line_discount_amount     giảm giá riêng của dòng
--   order_discount_allocated phần giảm giá cấp đơn chia cho dòng này, tính lúc
--                            xác nhận, khi đơn còn DRAFT (BR-ORD-04)
--   net_amount               = line_amount − hai khoản giảm             (cột sinh)
--   tax_amount               thuế của dòng, tính trên net_amount (BR-TAX-01);
--                            sales_order.tax_amount = tổng tax_amount các dòng
--   Tiền hoàn khi trả hàng tính từ net_amount (BR-RET-04).
-- Số lượng theo vòng đời: 0 ≤ returned_qty ≤ fulfilled_qty ≤ quantity.
--   DB chặn trả quá số đã giao (BR-RET-02).
-- Từ CONFIRMED trở đi, không đổi sản phẩm, số lượng, giá, giảm giá; không thêm,
--   không xoá dòng (BR-ORD-09, trigger, lỗi OPS03). Vẫn được cập nhật
--   fulfilled_qty, returned_qty, unit_cost, note.
-- -----------------------------------------------------------------------------
CREATE TABLE sales_order_item (
    id                        BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id           BIGINT         NOT NULL REFERENCES organization (id),
    sales_order_id            BIGINT         NOT NULL,
    line_no                   SMALLINT       NOT NULL,
    product_id                BIGINT         NOT NULL,
    product_name              VARCHAR(255)   NOT NULL,
    unit_name                 VARCHAR(50)    NOT NULL,
    track_inventory           BOOLEAN        NOT NULL,
    quantity                  NUMERIC(14, 3) NOT NULL,
    unit_price                NUMERIC(18, 2) NOT NULL,
    line_amount               NUMERIC(18, 2) GENERATED ALWAYS AS (round(quantity * unit_price, 2)) STORED,
    line_discount_amount      NUMERIC(18, 2) NOT NULL DEFAULT 0,
    order_discount_allocated  NUMERIC(18, 2) NOT NULL DEFAULT 0,
    promotion_id              BIGINT,
    net_amount                NUMERIC(18, 2) GENERATED ALWAYS AS (
        round(quantity * unit_price, 2) - line_discount_amount - order_discount_allocated) STORED,
    tax_rate                  NUMERIC(5, 2)  NOT NULL DEFAULT 0,
    tax_amount                NUMERIC(18, 2) NOT NULL DEFAULT 0,
    unit_cost                 NUMERIC(18, 4),
    fulfilled_qty             NUMERIC(14, 3) NOT NULL DEFAULT 0,
    returned_qty              NUMERIC(14, 3) NOT NULL DEFAULT 0,
    note                      VARCHAR(500),
    created_at                TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by                BIGINT,
    updated_at                TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by                BIGINT,
    version                   INT            NOT NULL DEFAULT 0,
    CONSTRAINT uq_sales_order_item_org_id   UNIQUE (organization_id, id),
    CONSTRAINT uq_sales_order_item_order_id UNIQUE (organization_id, sales_order_id, id),
    CONSTRAINT uq_sales_order_item_line     UNIQUE (sales_order_id, line_no),
    CONSTRAINT fk_sales_order_item_order FOREIGN KEY (organization_id, sales_order_id)
        REFERENCES sales_order (organization_id, id),
    CONSTRAINT fk_sales_order_item_product FOREIGN KEY (organization_id, product_id)
        REFERENCES product (organization_id, id),
    CONSTRAINT fk_sales_order_item_promotion FOREIGN KEY (organization_id, promotion_id)
        REFERENCES promotion (organization_id, id),
    CONSTRAINT ck_sales_order_item_qty   CHECK (quantity > 0),
    CONSTRAINT ck_sales_order_item_price CHECK (unit_price >= 0),
    CONSTRAINT ck_sales_order_item_discount CHECK (
        line_discount_amount >= 0 AND order_discount_allocated >= 0 AND net_amount >= 0),
    CONSTRAINT ck_sales_order_item_progress CHECK (
        returned_qty >= 0 AND returned_qty <= fulfilled_qty AND fulfilled_qty <= quantity),
    CONSTRAINT ck_sales_order_item_cost CHECK (unit_cost IS NULL OR unit_cost >= 0),
    CONSTRAINT ck_sales_order_item_tax  CHECK (tax_rate BETWEEN 0 AND 100 AND tax_amount >= 0)
);
CREATE INDEX ix_sales_order_item_product ON sales_order_item (organization_id, product_id);
COMMENT ON TABLE sales_order_item IS 'Dòng hàng: giá, số lượng, giảm giá tại thời điểm bán; tiến độ giao và trả.';
COMMENT ON COLUMN sales_order_item.line_no IS 'Số thứ tự dòng trong đơn, bắt đầu từ 1.';
COMMENT ON COLUMN sales_order_item.product_name IS 'Tên sản phẩm lúc bán (chụp lại cho chứng từ).';
COMMENT ON COLUMN sales_order_item.unit_name IS 'Tên đơn vị tính cơ sở lúc bán (chụp lại cho chứng từ).';
COMMENT ON COLUMN sales_order_item.promotion_id IS 'Khuyến mãi áp cho dòng (số giảm nằm trong line_discount_amount).';
COMMENT ON COLUMN sales_order_item.track_inventory IS 'Chụp từ product lúc tạo dòng; quyết định có giữ và xuất kho không.';
COMMENT ON COLUMN sales_order_item.line_amount IS 'round(quantity × unit_price, 2). Cột sinh.';
COMMENT ON COLUMN sales_order_item.order_discount_allocated IS 'Phần giảm giá cấp đơn chia cho dòng, tính lúc xác nhận (BR-ORD-04).';
COMMENT ON COLUMN sales_order_item.net_amount IS 'Tiền thực bán của dòng sau mọi giảm giá, trước thuế. Cột sinh.';
COMMENT ON COLUMN sales_order_item.tax_rate IS 'Thuế suất (%) của dòng; 0 khi giá đã gồm thuế (BR-TAX-01).';
COMMENT ON COLUMN sales_order_item.tax_amount IS 'Thuế của dòng = làm tròn(net_amount × tax_rate / 100); tổng các dòng = sales_order.tax_amount.';
COMMENT ON COLUMN sales_order_item.unit_cost IS 'Giá vốn đơn vị lúc xuất; để trống cho tới khi chốt BD-09.';
COMMENT ON COLUMN sales_order_item.fulfilled_qty IS 'Đã xuất/đã thực hiện. Không vượt quantity.';
COMMENT ON COLUMN sales_order_item.returned_qty IS 'Đã trả lại. Không vượt fulfilled_qty (BR-RET-02).';


-- Khoá các cột thương mại của dòng hàng khi đơn đã qua DRAFT (BR-ORD-09).
-- Thêm dòng vào đơn đã chốt chỉ qua luồng nhập lịch sử có kiểm soát (BR-ORD-16).
CREATE OR REPLACE FUNCTION sales_order_item_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_status  VARCHAR(20);
    v_import  BIGINT;
BEGIN
    SELECT order_status, import_job_id INTO v_status, v_import
      FROM sales_order
     WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.sales_order_id ELSE NEW.sales_order_id END;

    IF v_status = 'DRAFT' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;

    IF TG_OP = 'INSERT' AND v_import IS NOT NULL AND app_is_job_role() THEN
        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE'
       AND NEW.sales_order_id           = OLD.sales_order_id
       AND NEW.line_no                  = OLD.line_no
       AND NEW.product_id               = OLD.product_id
       AND NEW.track_inventory          = OLD.track_inventory
       AND NEW.quantity                 = OLD.quantity
       AND NEW.unit_price               = OLD.unit_price
       AND NEW.line_discount_amount     = OLD.line_discount_amount
       AND NEW.order_discount_allocated = OLD.order_discount_allocated
       AND NEW.product_name             = OLD.product_name
       AND NEW.unit_name                = OLD.unit_name
       AND NEW.tax_rate                 = OLD.tax_rate
       AND NEW.tax_amount               = OLD.tax_amount
       AND NEW.promotion_id IS NOT DISTINCT FROM OLD.promotion_id THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Đơn % đang ở %: không được thêm, xoá hoặc sửa giá, số lượng, giảm giá của dòng hàng',
        CASE WHEN TG_OP = 'DELETE' THEN OLD.sales_order_id ELSE NEW.sales_order_id END, v_status
        USING ERRCODE = 'OPS03';
END;
$$;

CREATE TRIGGER trg_sales_order_item_guard
    BEFORE INSERT OR UPDATE OR DELETE ON sales_order_item
    FOR EACH ROW EXECUTE FUNCTION sales_order_item_guard();


-- -----------------------------------------------------------------------------
-- sales_return — Phiếu trả hàng
--
-- Vai trò: một lần khách trả lại hàng của một đơn đã COMPLETED (BR-RET-01). Một
--   đơn có thể trả nhiều lần. Phiếu là cuối cùng ở bản đầu: không xoá, chỉ sửa
--   được ghi chú (BR-RET-07, trigger, lỗi OPS06).
-- Trả hàng không sửa đơn gốc ngoài returned_qty/returned_amount và không đổi ngày
--   doanh thu gốc; doanh thu giảm vào ngày returned_at (BR-REV-03).
-- Tiền hoàn là payment REFUND gắn sales_return_id, tạo riêng (BR-RET-06).
-- -----------------------------------------------------------------------------
CREATE TABLE sales_return (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id      BIGINT         NOT NULL REFERENCES organization (id),
    code                 VARCHAR(30)    NOT NULL,
    sales_order_id       BIGINT         NOT NULL,
    org_unit_id          BIGINT         NOT NULL,
    warehouse_unit_id    BIGINT,
    returned_at          TIMESTAMPTZ    NOT NULL,
    reason_code          VARCHAR(30)    NOT NULL,
    note                 VARCHAR(1000),
    total_refund_amount  NUMERIC(18, 2) NOT NULL DEFAULT 0,
    source               VARCHAR(20)    NOT NULL DEFAULT 'MANUAL',
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by           BIGINT,
    version              INT            NOT NULL DEFAULT 0,
    CONSTRAINT uq_sales_return_org_id   UNIQUE (organization_id, id),
    CONSTRAINT uq_sales_return_order_id UNIQUE (organization_id, id, sales_order_id),
    CONSTRAINT uq_sales_return_code     UNIQUE (organization_id, code),
    CONSTRAINT fk_sales_return_order FOREIGN KEY (organization_id, sales_order_id)
        REFERENCES sales_order (organization_id, id),
    CONSTRAINT fk_sales_return_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_sales_return_warehouse FOREIGN KEY (organization_id, warehouse_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT ck_sales_return_reason CHECK (reason_code IN (
        'DEFECTIVE', 'WRONG_ITEM', 'NOT_AS_DESCRIBED', 'CHANGED_MIND', 'OTHER')),
    CONSTRAINT ck_sales_return_amount CHECK (total_refund_amount >= 0),
    CONSTRAINT ck_sales_return_source CHECK (source IN ('MANUAL', 'IMPORT', 'API'))
);
CREATE INDEX ix_sales_return_order ON sales_return (organization_id, sales_order_id);
CREATE INDEX ix_sales_return_time  ON sales_return (organization_id, returned_at);
COMMENT ON TABLE sales_return IS 'Phiếu trả hàng của một đơn COMPLETED. Cuối cùng, không sửa không huỷ ở bản đầu.';
COMMENT ON COLUMN sales_return.code IS 'Mã phiếu, vd SR-2026-000045; cấp từ document_sequence.';
COMMENT ON COLUMN sales_return.warehouse_unit_id IS 'Kho nhận lại hàng; mặc định là kho đã xuất. NULL nếu không dòng nào nhập lại kho.';
COMMENT ON COLUMN sales_return.returned_at IS 'Lúc nhận lại hàng (giờ nghiệp vụ); doanh thu giảm vào ngày này.';
COMMENT ON COLUMN sales_return.total_refund_amount IS 'Tổng (refund_amount + tax_refund_amount) các dòng = số cộng vào sales_order.returned_amount.';


-- Phiếu trả là chứng từ cuối cùng (BR-RET-07): không xoá; chỉ được sửa ghi chú.
CREATE OR REPLACE FUNCTION sales_return_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Không được xoá phiếu trả %', OLD.id USING ERRCODE = 'OPS06';
    END IF;
    IF (NEW.organization_id, NEW.code, NEW.sales_order_id, NEW.org_unit_id, NEW.warehouse_unit_id,
        NEW.returned_at, NEW.reason_code, NEW.total_refund_amount, NEW.source, NEW.created_at, NEW.created_by)
       IS DISTINCT FROM
       (OLD.organization_id, OLD.code, OLD.sales_order_id, OLD.org_unit_id, OLD.warehouse_unit_id,
        OLD.returned_at, OLD.reason_code, OLD.total_refund_amount, OLD.source, OLD.created_at, OLD.created_by) THEN
        RAISE EXCEPTION 'Phiếu trả % là chứng từ cuối cùng: chỉ được sửa ghi chú', OLD.id USING ERRCODE = 'OPS06';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sales_return_guard
    BEFORE UPDATE OR DELETE ON sales_return
    FOR EACH ROW EXECUTE FUNCTION sales_return_guard();


-- -----------------------------------------------------------------------------
-- sales_return_item — Dòng trả hàng
--
-- Vai trò: trả bao nhiêu của dòng hàng nào, tính bao nhiêu tiền, có nhập lại kho
--   không (BR-RET-04, 05).
-- Khoá ngoại 3 cột bảo đảm dòng trả thuộc ĐÚNG đơn của phiếu trả (BR-RET-03):
--   (organization_id, sales_return_id, sales_order_id) → sales_return
--   (organization_id, sales_order_id, sales_order_item_id) → sales_order_item
-- Chỉ ghi thêm.
-- -----------------------------------------------------------------------------
CREATE TABLE sales_return_item (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id      BIGINT         NOT NULL REFERENCES organization (id),
    sales_return_id      BIGINT         NOT NULL,
    sales_order_id       BIGINT         NOT NULL,
    sales_order_item_id  BIGINT         NOT NULL,
    quantity             NUMERIC(14, 3) NOT NULL,
    refund_amount        NUMERIC(18, 2) NOT NULL,
    tax_refund_amount    NUMERIC(18, 2) NOT NULL DEFAULT 0,
    restock              BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by           BIGINT,
    CONSTRAINT uq_sales_return_item_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_sales_return_item_line   UNIQUE (sales_return_id, sales_order_item_id),
    CONSTRAINT fk_sales_return_item_return FOREIGN KEY (organization_id, sales_return_id, sales_order_id)
        REFERENCES sales_return (organization_id, id, sales_order_id),
    CONSTRAINT fk_sales_return_item_order_item FOREIGN KEY (organization_id, sales_order_id, sales_order_item_id)
        REFERENCES sales_order_item (organization_id, sales_order_id, id),
    CONSTRAINT ck_sales_return_item_qty    CHECK (quantity > 0),
    CONSTRAINT ck_sales_return_item_amount CHECK (refund_amount >= 0 AND tax_refund_amount >= 0)
);
CREATE INDEX ix_sales_return_item_order_item ON sales_return_item (organization_id, sales_order_item_id);

CREATE TRIGGER trg_sales_return_item_append_only
    BEFORE UPDATE OR DELETE ON sales_return_item
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();

COMMENT ON TABLE sales_return_item IS 'Dòng trả hàng; luôn thuộc đúng đơn của phiếu (khoá ngoại 3 cột). Chỉ ghi thêm.';
COMMENT ON COLUMN sales_return_item.refund_amount IS 'Phần trước thuế: net_amount × số trả / số mua, làm tròn đồng; lần trả cuối của dòng lấy phần còn lại (BR-RET-04).';
COMMENT ON COLUMN sales_return_item.tax_refund_amount IS 'Thuế hoàn lại: tax_amount của dòng × số trả / số mua, cùng cách làm tròn (BR-TAX-02).';
COMMENT ON COLUMN sales_return_item.restock IS 'TRUE: nhập lại kho (SALE_RETURN); FALSE: hàng hỏng, không nhập lại.';


-- -----------------------------------------------------------------------------
-- payment — Thu tiền và hoàn tiền
--
-- Vai trò: mỗi lần thu hoặc hoàn là một dòng (SALES-07, 11); một đơn có nhiều lần
--   (cọc, trả nốt, hoàn sau khi trả hàng). amount luôn dương; kind cho biết chiều.
-- Không sửa thông tin tiền, không xoá (trigger, OPS04). Nhập sai thì VOID kèm lý
--   do rồi nhập lại (BR-PAY-01). Dòng VOIDED không tính vào paid/refunded.
-- Hoàn tiền do trả hàng gắn sales_return_id; khoá ngoại 3 cột bảo đảm phiếu trả
--   thuộc đúng đơn.
-- paid_at: lúc tiền thật sự được thu/hoàn; created_at: lúc ghi.
-- -----------------------------------------------------------------------------
CREATE TABLE payment (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id    BIGINT         NOT NULL REFERENCES organization (id),
    sales_order_id     BIGINT         NOT NULL,
    sales_return_id    BIGINT,
    kind               VARCHAR(10)    NOT NULL,
    payment_method_id  BIGINT         NOT NULL,
    amount             NUMERIC(18, 2) NOT NULL,
    status             VARCHAR(10)    NOT NULL DEFAULT 'RECORDED',
    paid_at            TIMESTAMPTZ    NOT NULL,
    reference          VARCHAR(100),
    note               VARCHAR(500),
    voided_at          TIMESTAMPTZ,
    voided_by          BIGINT,
    void_reason        VARCHAR(500),
    source             VARCHAR(20)    NOT NULL DEFAULT 'MANUAL',
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by         BIGINT,
    version            INT            NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_payment_order FOREIGN KEY (organization_id, sales_order_id)
        REFERENCES sales_order (organization_id, id),
    CONSTRAINT fk_payment_return FOREIGN KEY (organization_id, sales_return_id, sales_order_id)
        REFERENCES sales_return (organization_id, id, sales_order_id),
    CONSTRAINT fk_payment_method FOREIGN KEY (organization_id, payment_method_id)
        REFERENCES payment_method (organization_id, id),
    CONSTRAINT ck_payment_kind   CHECK (kind IN ('PAYMENT', 'REFUND')),
    CONSTRAINT ck_payment_status CHECK (status IN ('RECORDED', 'VOIDED')),
    CONSTRAINT ck_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_return_only_refund CHECK (kind = 'REFUND' OR sales_return_id IS NULL),
    CONSTRAINT ck_payment_void CHECK (
        (status = 'VOIDED') = (voided_at IS NOT NULL)
        AND (status <> 'VOIDED' OR void_reason IS NOT NULL)),
    CONSTRAINT ck_payment_source CHECK (source IN ('MANUAL', 'IMPORT', 'API'))
);
CREATE INDEX ix_payment_order   ON payment (organization_id, sales_order_id);
CREATE INDEX ix_payment_paid_at ON payment (organization_id, paid_at);
CREATE INDEX ix_payment_created ON payment USING brin (created_at);
COMMENT ON TABLE payment IS 'Thu tiền (PAYMENT) và hoàn tiền (REFUND). Không sửa tiền, không xoá; sai thì VOID.';
COMMENT ON COLUMN payment.kind IS 'PAYMENT: thu của khách; REFUND: trả lại khách.';
COMMENT ON COLUMN payment.amount IS 'Luôn dương.';
COMMENT ON COLUMN payment.status IS 'RECORDED hoặc VOIDED (huỷ do nhập sai). Chỉ đổi một chiều RECORDED → VOIDED.';
COMMENT ON COLUMN payment.paid_at IS 'Lúc tiền thật sự được thu/hoàn (giờ nghiệp vụ). Không ở tương lai (service kiểm tra).';
COMMENT ON COLUMN payment.reference IS 'Mã giao dịch ngân hàng, số biên lai...';


-- Payment là sổ tiền: không đổi thông tin tiền, chỉ được VOID một chiều, không xoá.
CREATE OR REPLACE FUNCTION payment_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Không được xoá payment %; hãy VOID kèm lý do', OLD.id
            USING ERRCODE = 'OPS04';
    END IF;

    IF NEW.sales_order_id    IS DISTINCT FROM OLD.sales_order_id
       OR NEW.sales_return_id   IS DISTINCT FROM OLD.sales_return_id
       OR NEW.kind              IS DISTINCT FROM OLD.kind
       OR NEW.payment_method_id IS DISTINCT FROM OLD.payment_method_id
       OR NEW.amount            IS DISTINCT FROM OLD.amount
       OR NEW.paid_at           IS DISTINCT FROM OLD.paid_at
       OR (OLD.status = 'VOIDED' AND NEW.status <> 'VOIDED') THEN
        RAISE EXCEPTION 'Không được sửa thông tin tiền của payment %; hãy VOID rồi ghi dòng mới', OLD.id
            USING ERRCODE = 'OPS04';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_guard
    BEFORE UPDATE OR DELETE ON payment
    FOR EACH ROW EXECUTE FUNCTION payment_guard();

-- ############################################################################
-- ## V8__platform_support.sql
-- ############################################################################

-- =============================================================================
-- V8__platform_support.sql — Hạ tầng dùng chung
-- Bảng: document_sequence, idempotency_record, organization_feature, stored_file,
--       notification (chia partition theo tháng)
-- Phụ thuộc: V1 (organization, org_unit), V2 (app_user, feature),
--            V4 (hàm ensure_monthly_partitions)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- document_sequence — Bộ đếm số chứng từ
--
-- Vai trò: cấp số liên tục, không trùng cho mã đơn, mã phiếu trả... theo doanh
--   nghiệp, loại chứng từ và kỳ (BR-ORD-12, BR-CON-08). Ví dụ SO-2026-000123.
-- Cách dùng (một câu lệnh, nguyên tử, chạy TRONG giao dịch tạo chứng từ):
--   INSERT INTO document_sequence (organization_id, doc_type, period_key, last_value)
--   VALUES (:org, 'SALES_ORDER', '2026', 1)
--   ON CONFLICT (organization_id, doc_type, period_key)
--   DO UPDATE SET last_value = document_sequence.last_value + 1, updated_at = now()
--   RETURNING last_value;
-- Đánh đổi: giao dịch rollback thì số cũng quay lại, nên không bị nhảy số; đổi
--   lại, các giao dịch tạo cùng loại chứng từ trong một doanh nghiệp xếp hàng
--   qua dòng này cho tới khi commit. Với quy mô chi nhánh bán lẻ là chấp nhận được.
-- -----------------------------------------------------------------------------
CREATE TABLE document_sequence (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organization (id),
    doc_type         VARCHAR(30)  NOT NULL,
    period_key       VARCHAR(10)  NOT NULL,
    last_value       BIGINT       NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_sequence_key UNIQUE (organization_id, doc_type, period_key),
    CONSTRAINT ck_document_sequence_value    CHECK (last_value > 0),
    CONSTRAINT ck_document_sequence_doc_type CHECK (doc_type ~ '^[A-Z_]{2,30}$')
);
COMMENT ON TABLE document_sequence IS 'Bộ đếm số chứng từ theo doanh nghiệp × loại × kỳ; cấp bằng một câu upsert trong giao dịch.';
COMMENT ON COLUMN document_sequence.doc_type IS 'Loại chứng từ: SALES_ORDER, SALES_RETURN, PAYMENT...';
COMMENT ON COLUMN document_sequence.period_key IS 'Kỳ đánh số, vd 2026 (đánh lại từ 1 mỗi năm).';
COMMENT ON COLUMN document_sequence.last_value IS 'Số đã cấp gần nhất.';


-- -----------------------------------------------------------------------------
-- idempotency_record — Chống ghi trùng khi client gửi lại request
--
-- Vai trò: client gửi header Idempotency-Key (UUID, mỗi thao tác một khoá, giữ
--   nguyên khi gửi lại). Lần đầu: thực hiện và lưu kết quả. Lần sau cùng khoá,
--   cùng nội dung: trả lại đúng kết quả cũ, không tạo gì mới (mục 8).
-- Cách dùng, tất cả trong CÙNG giao dịch với thao tác nghiệp vụ:
--   1. INSERT ... ON CONFLICT DO NOTHING RETURNING id   (response_status = NULL)
--      - Có id: lần đầu, chạy nghiệp vụ, cuối cùng UPDATE response_*.
--      - Không có id: khoá đã dùng. Đọc bản ghi; khác request_hash hoặc khác
--        path thì 422, giống thì trả lại response đã lưu.
--   2. Request trùng đến cùng lúc: câu INSERT của request sau chờ ở unique index
--      tới khi request trước commit, rồi rơi vào nhánh "khoá đã dùng".
--   Giao dịch lỗi thì bản ghi cũng mất, nên lần gửi lại được chạy lại từ đầu.
-- Phạm vi khoá: doanh nghiệp × người dùng × khoá (user_id NULL cho tích hợp hệ
--   thống, NULLS NOT DISTINCT để vẫn chống trùng).
-- Dữ liệu kỹ thuật: job db-scheduler xoá cứng bản ghi đã hết hạn.
-- -----------------------------------------------------------------------------
CREATE TABLE idempotency_record (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organization (id),
    user_id          BIGINT,
    idempotency_key  VARCHAR(100) NOT NULL,
    request_method   VARCHAR(10)  NOT NULL,
    request_path     VARCHAR(500) NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    response_status  SMALLINT,
    response_body    JSONB,
    resource_type    VARCHAR(50),
    resource_id      BIGINT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_idempotency_record_user FOREIGN KEY (organization_id, user_id)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT uq_idempotency_record_key UNIQUE NULLS NOT DISTINCT (organization_id, user_id, idempotency_key),
    CONSTRAINT ck_idempotency_record_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_idempotency_record_hash   CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_record_status CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599)
);
CREATE INDEX ix_idempotency_record_expires ON idempotency_record (expires_at);
COMMENT ON TABLE idempotency_record IS 'Kết quả đã trả cho mỗi Idempotency-Key; ghi trong cùng giao dịch với thao tác nghiệp vụ.';
COMMENT ON COLUMN idempotency_record.user_id IS 'Người gửi; NULL cho tích hợp hệ thống.';
COMMENT ON COLUMN idempotency_record.request_hash IS 'SHA-256 (hex) của method + path + body đã chuẩn hoá; khác hash cùng khoá → 422.';
COMMENT ON COLUMN idempotency_record.response_status IS 'HTTP status đã trả; NULL trong lúc giao dịch đầu tiên đang chạy.';
COMMENT ON COLUMN idempotency_record.response_body IS 'Body đã trả, để trả lại y nguyên.';
COMMENT ON COLUMN idempotency_record.resource_type IS 'Loại đối tượng được tạo, vd sales_order.';
COMMENT ON COLUMN idempotency_record.expires_at IS 'Hết hạn, mặc định created_at + 24 giờ (ĐỀ XUẤT).';


-- -----------------------------------------------------------------------------
-- organization_feature — Doanh nghiệp bật những phân hệ nào
--
-- Vai trò: bật/tắt phân hệ tuỳ chọn cho từng doanh nghiệp (MOD-02→07): menu,
--   API và quyền của phân hệ bị tắt đều bị chặn. Phân hệ lõi (feature.is_core)
--   luôn bật; service không cho tắt.
-- org_unit_id: NULL là cấu hình cho cả doanh nghiệp; có giá trị là cấu hình riêng
--   cho một đơn vị (MOD-07, P2). settings: cấu hình riêng của phân hệ (MOD-05).
-- Tắt phân hệ: service kiểm tra phụ thuộc trước (MOD-06).
-- -----------------------------------------------------------------------------
CREATE TABLE organization_feature (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organization (id),
    feature_id       BIGINT       NOT NULL REFERENCES feature (id),
    org_unit_id      BIGINT,
    is_enabled       BOOLEAN      NOT NULL,
    settings         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    enabled_at       TIMESTAMPTZ,
    disabled_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_organization_feature_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_organization_feature_scope UNIQUE NULLS NOT DISTINCT (organization_id, feature_id, org_unit_id),
    CONSTRAINT fk_organization_feature_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT ck_organization_feature_times CHECK (
        (NOT is_enabled OR enabled_at IS NOT NULL) AND (is_enabled OR disabled_at IS NOT NULL))
);
COMMENT ON TABLE organization_feature IS 'Phân hệ được bật/tắt cho doanh nghiệp hoặc một đơn vị.';
COMMENT ON COLUMN organization_feature.org_unit_id IS 'NULL: cả doanh nghiệp; có giá trị: riêng một đơn vị (MOD-07).';
COMMENT ON COLUMN organization_feature.settings IS 'Cấu hình riêng của phân hệ cho doanh nghiệp (JSON).';


-- -----------------------------------------------------------------------------
-- stored_file — File lưu trên AWS S3
--
-- Vai trò: thông tin của mọi file (file Excel import, file lỗi, file export, đính
--   kèm công việc, hoá đơn chi phí, tài liệu cho AI, file mô hình...). Nội dung file
--   nằm trên S3; bảng này giữ bucket, object_key và thông tin để kiểm soát quyền.
-- Luồng tải lên: service tạo dòng PENDING và cấp presigned URL; client tải thẳng
--   lên S3; xác nhận xong chuyển UPLOADED. File tạm có expires_at để job dọn.
-- Gắn với đối tượng bằng entity_type/entity_id, không khoá ngoại, để bảng này không
--   phụ thuộc vào module nào. Bảng nghiệp vụ cần chắc chắn thì tự giữ khoá ngoại
--   tới stored_file (vd import_job.source_file_id).
-- -----------------------------------------------------------------------------
CREATE TABLE stored_file (
    id                BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id   BIGINT        NOT NULL REFERENCES organization (id),
    storage_provider  VARCHAR(10)   NOT NULL DEFAULT 'S3',
    bucket            VARCHAR(100)  NOT NULL,
    object_key        VARCHAR(500)  NOT NULL,
    original_name     VARCHAR(255)  NOT NULL,
    content_type      VARCHAR(100)  NOT NULL,
    size_bytes        BIGINT,
    checksum_sha256   CHAR(64),
    purpose           VARCHAR(30)   NOT NULL,
    entity_type       VARCHAR(50),
    entity_id         BIGINT,
    upload_status     VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    uploaded_at       TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ,
    deleted_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by        BIGINT,
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by        BIGINT,
    version           INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_stored_file_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_stored_file_object UNIQUE (bucket, object_key),
    CONSTRAINT ck_stored_file_provider CHECK (storage_provider IN ('S3')),
    CONSTRAINT ck_stored_file_purpose CHECK (purpose IN (
        'IMPORT_SOURCE', 'IMPORT_ERROR_REPORT', 'EXPORT', 'TASK_ATTACHMENT', 'EXPENSE_RECEIPT',
        'AVATAR', 'DOCUMENT', 'AI_KNOWLEDGE', 'ML_DATASET', 'ML_MODEL', 'OTHER')),
    CONSTRAINT ck_stored_file_status CHECK (upload_status IN ('PENDING', 'UPLOADED', 'FAILED', 'DELETED')),
    CONSTRAINT ck_stored_file_uploaded CHECK (
        upload_status <> 'UPLOADED' OR (uploaded_at IS NOT NULL AND size_bytes IS NOT NULL)),
    CONSTRAINT ck_stored_file_size CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT ck_stored_file_entity CHECK ((entity_type IS NULL) = (entity_id IS NULL))
);
CREATE INDEX ix_stored_file_entity ON stored_file (organization_id, entity_type, entity_id)
    WHERE entity_type IS NOT NULL;
CREATE INDEX ix_stored_file_expires ON stored_file (expires_at) WHERE expires_at IS NOT NULL AND deleted_at IS NULL;
COMMENT ON TABLE stored_file IS 'Thông tin file trên S3; nội dung file không nằm trong DB.';
COMMENT ON COLUMN stored_file.object_key IS 'Đường dẫn trong bucket, vd org/12/import/2026/11/uuid.xlsx.';
COMMENT ON COLUMN stored_file.purpose IS 'File dùng để làm gì; quyết định quyền xem và thời gian lưu.';
COMMENT ON COLUMN stored_file.entity_type IS 'Loại đối tượng gắn file, vd task, expense; không khoá ngoại.';
COMMENT ON COLUMN stored_file.upload_status IS 'PENDING: đã cấp URL tải lên; UPLOADED: đã có trên S3; FAILED; DELETED.';
COMMENT ON COLUMN stored_file.expires_at IS 'File tạm (vd file export) hết hạn thì job xoá trên S3 và đánh dấu DELETED.';


-- -----------------------------------------------------------------------------
-- notification — Thông báo trong ứng dụng
--
-- Vai trò: "Bạn có việc mới", "Có yêu cầu chờ duyệt", "Tồn kho thấp"... gửi tới
--   một người dùng (NOTI-01→09). SSE chỉ báo "có thông báo mới"; client đọc lại
--   từ bảng này.
-- Chia partition theo tháng trên created_at vì số dòng lớn và chỉ cần giữ một thời
--   gian (đề xuất 6 tháng): dọn bằng cách DROP partition cũ. Không bảng nào tham
--   chiếu tới bảng này. Khoá chính (id, created_at) theo yêu cầu của partition;
--   id GENERATED ALWAYS chỉ chặn việc vô tình tự chèn id: role có quyền INSERT vẫn
--   ghi đè được bằng OVERRIDING SYSTEM VALUE, và DB không có unique riêng cho id.
--   Vì vậy định danh một dòng luôn là cặp (id, created_at).
-- Truy cập: KHÔNG tạo JPA entity; dùng JdbcClient. API trả về cả id và created_at;
--   đánh dấu đã đọc theo (id, created_at, recipient_user_id).
-- Cấu hình loại thông báo theo người dùng (NOTI-07): MỞ, tạm lưu ở cấu hình chung.
-- -----------------------------------------------------------------------------
CREATE TABLE notification (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY,
    organization_id    BIGINT        NOT NULL REFERENCES organization (id),
    recipient_user_id  BIGINT        NOT NULL,
    notification_type  VARCHAR(50)   NOT NULL,
    title              VARCHAR(200)  NOT NULL,
    body               VARCHAR(1000),
    entity_type        VARCHAR(50),
    entity_id          BIGINT,
    link_path          VARCHAR(500),
    channel            VARCHAR(10)   NOT NULL DEFAULT 'IN_APP',
    read_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_notification PRIMARY KEY (id, created_at),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (organization_id, recipient_user_id)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT ck_notification_channel CHECK (channel IN ('IN_APP', 'EMAIL')),
    CONSTRAINT ck_notification_type CHECK (notification_type ~ '^[A-Z][A-Z_]{2,49}$'),
    CONSTRAINT ck_notification_entity CHECK ((entity_type IS NULL) = (entity_id IS NULL))
) PARTITION BY RANGE (created_at);

CREATE TABLE notification_default PARTITION OF notification DEFAULT;
SELECT ensure_monthly_partitions('notification', 3);

CREATE INDEX ix_notification_recipient ON notification (organization_id, recipient_user_id, created_at DESC);
CREATE INDEX ix_notification_unread ON notification (recipient_user_id, created_at DESC) WHERE read_at IS NULL;
COMMENT ON TABLE notification IS 'Thông báo trong ứng dụng. Chia partition theo tháng; giữ khoảng 6 tháng.';
COMMENT ON COLUMN notification.notification_type IS 'Mã loại, vd TASK_ASSIGNED, TASK_OVERDUE, APPROVAL_REQUESTED, LOW_STOCK, AI_ALERT.';
COMMENT ON COLUMN notification.link_path IS 'Đường dẫn trong ứng dụng để mở đối tượng, vd /tasks/123.';
COMMENT ON COLUMN notification.read_at IS 'NULL: chưa đọc (NOTI-03).';

-- ############################################################################
-- ## V9__task.sql
-- ############################################################################

-- =============================================================================
-- V9__task.sql — Công việc
-- Bảng: task_recurrence, task, task_status_history, task_comment, task_checklist_item
-- Phụ thuộc: V1 (organization, org_unit), V2 (app_user), V3 (employee),
--            V6 (hàm forbid_update_delete)
--
-- BẢN NHÁP: quy tắc nghiệp vụ của Task chưa chốt như bán hàng. Ràng buộc dưới đây
-- là đề xuất; sửa thoải mái trước lần chạy đầu tiên. Quy trình xác nhận hoàn
-- thành (BD-06) còn MỞ: trạng thái IN_REVIEW để sẵn, doanh nghiệp có thể không dùng.
--
-- Mã lỗi riêng: OPS05 chuyển trạng thái công việc không hợp lệ.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- task_recurrence — Mẫu công việc lặp lại
--
-- Vai trò: "Kiểm tra nhiệt độ tủ mát mỗi sáng", "Kiểm kê cuối tuần" (TASK-09, P1).
--   Job db-scheduler đọc next_run_at, sinh task mới rồi tính next_run_at kế tiếp.
-- Lặp: DAILY (mỗi interval_count ngày), WEEKLY (các thứ trong days_of_week, 1 = thứ
--   Hai ... 7 = Chủ nhật), MONTHLY (ngày day_of_month; tháng thiếu ngày thì lấy ngày
--   cuối tháng). Giờ tính theo timezone của mẫu.
-- Chống sinh trùng: task có UNIQUE (recurrence_id, scheduled_for).
-- -----------------------------------------------------------------------------
CREATE TABLE task_recurrence (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id       BIGINT        NOT NULL REFERENCES organization (id),
    title                 VARCHAR(255)  NOT NULL,
    description           TEXT,
    org_unit_id           BIGINT        NOT NULL,
    assignee_employee_id  BIGINT,
    priority              VARCHAR(10)   NOT NULL DEFAULT 'MEDIUM',
    frequency             VARCHAR(10)   NOT NULL,
    interval_count        SMALLINT      NOT NULL DEFAULT 1,
    days_of_week          SMALLINT[],
    day_of_month          SMALLINT,
    time_of_day           TIME          NOT NULL DEFAULT '08:00',
    due_after_minutes     INT,
    timezone              VARCHAR(50)   NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    starts_on             DATE          NOT NULL,
    ends_on               DATE,
    next_run_at           TIMESTAMPTZ,
    last_run_at           TIMESTAMPTZ,
    is_active             BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            BIGINT,
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by            BIGINT,
    version               INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_task_recurrence_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_task_recurrence_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_task_recurrence_assignee FOREIGN KEY (organization_id, assignee_employee_id)
        REFERENCES employee (organization_id, id),
    CONSTRAINT ck_task_recurrence_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    CONSTRAINT ck_task_recurrence_frequency CHECK (
           (frequency = 'DAILY'   AND days_of_week IS NULL AND day_of_month IS NULL)
        OR (frequency = 'WEEKLY'  AND days_of_week IS NOT NULL AND cardinality(days_of_week) > 0
                                  AND days_of_week <@ ARRAY[1, 2, 3, 4, 5, 6, 7]::SMALLINT[]
                                  AND day_of_month IS NULL)
        OR (frequency = 'MONTHLY' AND day_of_month BETWEEN 1 AND 31 AND days_of_week IS NULL)),
    CONSTRAINT ck_task_recurrence_interval CHECK (interval_count BETWEEN 1 AND 365),
    CONSTRAINT ck_task_recurrence_due CHECK (due_after_minutes IS NULL OR due_after_minutes > 0),
    CONSTRAINT ck_task_recurrence_period CHECK (ends_on IS NULL OR ends_on >= starts_on)
);
CREATE INDEX ix_task_recurrence_due ON task_recurrence (next_run_at) WHERE is_active;
COMMENT ON TABLE task_recurrence IS 'Mẫu công việc lặp lại; job sinh task theo next_run_at.';
COMMENT ON COLUMN task_recurrence.days_of_week IS 'WEEKLY: các thứ trong tuần, 1 = thứ Hai ... 7 = Chủ nhật.';
COMMENT ON COLUMN task_recurrence.day_of_month IS 'MONTHLY: ngày trong tháng; tháng thiếu ngày thì lấy ngày cuối tháng.';
COMMENT ON COLUMN task_recurrence.due_after_minutes IS 'Hạn của task sinh ra = lúc sinh + số phút này; NULL là không có hạn.';


-- -----------------------------------------------------------------------------
-- task — Công việc
--
-- Vai trò: việc được giao (TASK-01→08, TASK-12 sự cố): ai làm, thuộc đơn vị nào,
--   hạn, ưu tiên, trạng thái, tiến độ.
-- Vòng đời (trigger chặn chuyển sai, lỗi OPS05; tự ghi task_status_history):
--   TODO → IN_PROGRESS → IN_REVIEW → DONE
--   TODO/IN_PROGRESS ↔ BLOCKED; IN_REVIEW → IN_PROGRESS (trả lại)
--   DONE → IN_PROGRESS (mở lại); mọi trạng thái chưa xong → CANCELLED
-- related_entity_type/id: việc sinh ra từ một đối tượng khác (cảnh báo tồn kho,
--   cảnh báo bất thường, đề xuất của AI...), không khoá ngoại.
-- Đính kèm: stored_file với entity_type = 'task'.
-- Cho mô hình học: due_at, completed_at và lịch sử trạng thái cho thời gian xử lý,
--   tỷ lệ quá hạn (AI-15).
-- -----------------------------------------------------------------------------
CREATE TABLE task (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id       BIGINT        NOT NULL REFERENCES organization (id),
    code                  VARCHAR(30)   NOT NULL,
    task_type             VARCHAR(20)   NOT NULL DEFAULT 'TASK',
    title                 VARCHAR(255)  NOT NULL,
    description           TEXT,
    org_unit_id           BIGINT        NOT NULL,
    assignee_employee_id  BIGINT,
    priority              VARCHAR(10)   NOT NULL DEFAULT 'MEDIUM',
    status                VARCHAR(20)   NOT NULL DEFAULT 'TODO',
    progress_percent      SMALLINT      NOT NULL DEFAULT 0,
    due_at                TIMESTAMPTZ,
    started_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    cancelled_at          TIMESTAMPTZ,
    status_reason         VARCHAR(500),
    recurrence_id         BIGINT,
    scheduled_for         TIMESTAMPTZ,
    related_entity_type   VARCHAR(50),
    related_entity_id     BIGINT,
    source                VARCHAR(20)   NOT NULL DEFAULT 'MANUAL',
    metadata              JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            BIGINT,
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by            BIGINT,
    version               INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_task_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_task_code   UNIQUE (organization_id, code),
    CONSTRAINT uq_task_occurrence UNIQUE (recurrence_id, scheduled_for),
    CONSTRAINT fk_task_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_task_assignee FOREIGN KEY (organization_id, assignee_employee_id)
        REFERENCES employee (organization_id, id),
    CONSTRAINT fk_task_recurrence FOREIGN KEY (organization_id, recurrence_id)
        REFERENCES task_recurrence (organization_id, id),
    CONSTRAINT ck_task_type     CHECK (task_type IN ('TASK', 'INCIDENT')),
    CONSTRAINT ck_task_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    CONSTRAINT ck_task_status   CHECK (status IN ('TODO', 'IN_PROGRESS', 'BLOCKED', 'IN_REVIEW', 'DONE', 'CANCELLED')),
    CONSTRAINT ck_task_source   CHECK (source IN ('MANUAL', 'RECURRENCE', 'AI', 'SYSTEM', 'IMPORT')),
    CONSTRAINT ck_task_progress CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_task_done CHECK (
        (status = 'DONE') = (completed_at IS NOT NULL) AND (status <> 'DONE' OR progress_percent = 100)),
    CONSTRAINT ck_task_cancelled CHECK (
        (status = 'CANCELLED') = (cancelled_at IS NOT NULL)
        AND (status <> 'CANCELLED' OR status_reason IS NOT NULL)),
    CONSTRAINT ck_task_started CHECK (status IN ('TODO', 'CANCELLED') OR started_at IS NOT NULL),
    CONSTRAINT ck_task_recurrence_pair CHECK ((recurrence_id IS NULL) = (scheduled_for IS NULL)),
    CONSTRAINT ck_task_related CHECK ((related_entity_type IS NULL) = (related_entity_id IS NULL))
);
CREATE INDEX ix_task_assignee_open ON task (organization_id, assignee_employee_id, due_at)
    WHERE status NOT IN ('DONE', 'CANCELLED');
CREATE INDEX ix_task_unit_status ON task (organization_id, org_unit_id, status);
CREATE INDEX ix_task_overdue ON task (due_at) WHERE status NOT IN ('DONE', 'CANCELLED') AND due_at IS NOT NULL;
CREATE INDEX ix_task_related ON task (organization_id, related_entity_type, related_entity_id)
    WHERE related_entity_type IS NOT NULL;
CREATE INDEX ix_task_updated ON task (updated_at);
COMMENT ON TABLE task IS 'Công việc và sự cố vận hành. Trigger chặn chuyển trạng thái sai (OPS05) và tự ghi lịch sử.';
COMMENT ON COLUMN task.code IS 'Mã công việc, vd TK-2026-000123; cấp từ document_sequence.';
COMMENT ON COLUMN task.task_type IS 'TASK: công việc; INCIDENT: sự cố vận hành (TASK-12).';
COMMENT ON COLUMN task.assignee_employee_id IS 'Người phụ trách; NULL khi chưa giao.';
COMMENT ON COLUMN task.status IS 'TODO, IN_PROGRESS, BLOCKED, IN_REVIEW, DONE, CANCELLED.';
COMMENT ON COLUMN task.status_reason IS 'Lý do của lần chuyển gần nhất (bắt buộc khi huỷ); trigger chép vào lịch sử.';
COMMENT ON COLUMN task.scheduled_for IS 'Lần lặp mà task này được sinh cho (cùng recurrence_id không trùng).';
COMMENT ON COLUMN task.related_entity_type IS 'Đối tượng sinh ra việc này, vd anomaly_alert, ai_action_request.';


-- -----------------------------------------------------------------------------
-- task_status_history — Lịch sử trạng thái công việc
--
-- Vai trò: mỗi lần task.status đổi là một dòng; trigger tự ghi, người đổi lấy từ
--   task.updated_by (lúc tạo: created_by). Chỉ ghi thêm.
-- -----------------------------------------------------------------------------
CREATE TABLE task_status_history (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organization (id),
    task_id          BIGINT       NOT NULL,
    from_status      VARCHAR(20),
    to_status        VARCHAR(20)  NOT NULL,
    changed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    changed_by       BIGINT,
    reason           VARCHAR(500),
    CONSTRAINT fk_task_status_history_task FOREIGN KEY (organization_id, task_id)
        REFERENCES task (organization_id, id)
);
CREATE INDEX ix_task_status_history_task ON task_status_history (organization_id, task_id, changed_at);

CREATE TRIGGER trg_task_status_history_append_only
    BEFORE UPDATE OR DELETE ON task_status_history
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();

COMMENT ON TABLE task_status_history IS 'Lịch sử trạng thái công việc; trigger tự ghi, chỉ ghi thêm.';


CREATE OR REPLACE FUNCTION task_guard_status() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'TODO'        AND NEW.status IN ('IN_PROGRESS', 'BLOCKED', 'DONE', 'CANCELLED'))
        OR (OLD.status = 'IN_PROGRESS' AND NEW.status IN ('TODO', 'BLOCKED', 'IN_REVIEW', 'DONE', 'CANCELLED'))
        OR (OLD.status = 'BLOCKED'     AND NEW.status IN ('TODO', 'IN_PROGRESS', 'CANCELLED'))
        OR (OLD.status = 'IN_REVIEW'   AND NEW.status IN ('IN_PROGRESS', 'DONE', 'CANCELLED'))
        OR (OLD.status = 'DONE'        AND NEW.status = 'IN_PROGRESS')
    ) THEN
        RAISE EXCEPTION 'Không được chuyển công việc % từ % sang %', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'OPS05';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_task_guard_status
    BEFORE UPDATE OF status ON task
    FOR EACH ROW EXECUTE FUNCTION task_guard_status();

CREATE OR REPLACE FUNCTION task_log_status() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NULL;
    END IF;
    INSERT INTO task_status_history (organization_id, task_id, from_status, to_status, changed_by, reason)
    VALUES (NEW.organization_id, NEW.id,
            CASE WHEN TG_OP = 'UPDATE' THEN OLD.status END,
            NEW.status,
            CASE WHEN TG_OP = 'INSERT' THEN NEW.created_by ELSE NEW.updated_by END,
            CASE WHEN TG_OP = 'UPDATE' THEN NEW.status_reason END);
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_task_log_status
    AFTER INSERT OR UPDATE OF status ON task
    FOR EACH ROW EXECUTE FUNCTION task_log_status();


-- -----------------------------------------------------------------------------
-- task_comment — Bình luận trong công việc
--
-- Vai trò: trao đổi trong một công việc (TASK-07). Người viết sửa được (edited_at);
--   xoá là xoá mềm, giao diện hiện "bình luận đã bị xoá".
-- -----------------------------------------------------------------------------
CREATE TABLE task_comment (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organization (id),
    task_id          BIGINT       NOT NULL,
    author_user_id   BIGINT       NOT NULL,
    body             TEXT         NOT NULL,
    edited_at        TIMESTAMPTZ,
    deleted_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          INT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_task_comment_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_task_comment_task FOREIGN KEY (organization_id, task_id)
        REFERENCES task (organization_id, id),
    CONSTRAINT fk_task_comment_author FOREIGN KEY (organization_id, author_user_id)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT ck_task_comment_body CHECK (char_length(body) BETWEEN 1 AND 5000)
);
CREATE INDEX ix_task_comment_task ON task_comment (organization_id, task_id, created_at);
COMMENT ON TABLE task_comment IS 'Bình luận trong công việc; xoá mềm.';


-- -----------------------------------------------------------------------------
-- task_checklist_item — Việc con trong một công việc
--
-- Vai trò: danh sách kiểm tra (TASK-10, P1), vd "Lau tủ", "Kiểm tra nhiệt độ".
-- -----------------------------------------------------------------------------
CREATE TABLE task_checklist_item (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT        NOT NULL REFERENCES organization (id),
    task_id          BIGINT        NOT NULL,
    sort_order       SMALLINT      NOT NULL DEFAULT 0,
    content          VARCHAR(500)  NOT NULL,
    is_done          BOOLEAN       NOT NULL DEFAULT FALSE,
    done_at          TIMESTAMPTZ,
    done_by          BIGINT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_task_checklist_item_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_task_checklist_item_task FOREIGN KEY (organization_id, task_id)
        REFERENCES task (organization_id, id),
    CONSTRAINT ck_task_checklist_item_done CHECK (is_done = (done_at IS NOT NULL))
);
CREATE INDEX ix_task_checklist_item_task ON task_checklist_item (organization_id, task_id, sort_order);
COMMENT ON TABLE task_checklist_item IS 'Việc con (checklist) của một công việc.';

-- ############################################################################
-- ## V10__data_report.sql
-- ############################################################################

-- =============================================================================
-- V10__data_report.sql — Nhập xuất dữ liệu và báo cáo
-- Bảng: import_column_mapping, import_job, import_row_error, export_job,
--       saved_view, metric_definition, report_schedule
-- Thêm khoá ngoại import_job_id cho sales_order, inventory_movement (tạo trước V10)
-- Phụ thuộc: V1, V2 (app_user), V7 (sales_order), V6 (inventory_movement),
--            V8 (stored_file)
--
-- Import từng phần hay toàn bộ (BD-07) còn MỞ: import_job.mode giữ cả hai.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- import_column_mapping — Cách ánh xạ cột đã lưu
--
-- Vai trò: lưu "cột 'Tên SP' trong file = trường name" để lần sau dùng lại
--   (DATA-26, P1). Cũng là dữ liệu mẫu để AI gợi ý ánh xạ cột (DATA-38).
-- -----------------------------------------------------------------------------
CREATE TABLE import_column_mapping (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT        NOT NULL REFERENCES organization (id),
    data_type        VARCHAR(30)   NOT NULL,
    name             VARCHAR(100)  NOT NULL,
    mapping          JSONB         NOT NULL,
    is_default       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT           NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT uq_import_column_mapping_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_import_column_mapping_type_id UNIQUE (organization_id, data_type, id),
    CONSTRAINT ck_import_column_mapping_json CHECK (jsonb_typeof(mapping) = 'object')
);
CREATE UNIQUE INDEX uq_import_column_mapping_name ON import_column_mapping (organization_id, data_type, lower(name))
    WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_import_column_mapping_default ON import_column_mapping (organization_id, data_type)
    WHERE is_default AND deleted_at IS NULL;
COMMENT ON TABLE import_column_mapping IS 'Ánh xạ cột file → trường OpsPilot đã lưu để dùng lại.';
COMMENT ON COLUMN import_column_mapping.mapping IS 'JSON {"tên cột trong file": "trường OpsPilot"}.';


-- -----------------------------------------------------------------------------
-- import_job — Một lần nhập file Excel/CSV
--
-- Vai trò: theo dõi một lần import từ lúc tải file đến khi ghi xong (DATA-01→14,
--   DATA-27). Mọi dòng nghiệp vụ được import đều ghi source = IMPORT và
--   import_job_id, nên luôn biết dữ liệu nào đến từ file nào.
-- Vòng đời: UPLOADED → VALIDATING → VALIDATED (xem trước, chờ xác nhận)
--   → IMPORTING → COMPLETED; lỗi thì FAILED; người dùng huỷ thì CANCELLED.
-- mode: ALL_OR_NOTHING (có lỗi là không ghi dòng nào) hoặc PARTIAL (ghi dòng hợp lệ).
-- Khoá ngoại 3 cột bảo đảm ánh xạ cột dùng đúng loại dữ liệu của lần import.
-- Đơn hàng nhập lịch sử (vào thẳng COMPLETED/CANCELLED) bắt buộc đi qua import_job
--   và chạy bằng role opspilot_job (xem V7).
-- -----------------------------------------------------------------------------
CREATE TABLE import_job (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id       BIGINT        NOT NULL REFERENCES organization (id),
    data_type             VARCHAR(30)   NOT NULL,
    scope_unit_id         BIGINT,
    source_file_id        BIGINT        NOT NULL,
    error_report_file_id  BIGINT,
    column_mapping_id     BIGINT,
    mode                  VARCHAR(20)   NOT NULL DEFAULT 'ALL_OR_NOTHING',
    status                VARCHAR(20)   NOT NULL DEFAULT 'UPLOADED',
    total_rows            INT           NOT NULL DEFAULT 0,
    valid_rows            INT           NOT NULL DEFAULT 0,
    error_rows            INT           NOT NULL DEFAULT 0,
    imported_rows         INT           NOT NULL DEFAULT 0,
    started_at            TIMESTAMPTZ,
    validated_at          TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    error_message         VARCHAR(1000),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            BIGINT,
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by            BIGINT,
    version               INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_import_job_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_import_job_scope FOREIGN KEY (organization_id, scope_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_import_job_source_file FOREIGN KEY (organization_id, source_file_id)
        REFERENCES stored_file (organization_id, id),
    CONSTRAINT fk_import_job_error_file FOREIGN KEY (organization_id, error_report_file_id)
        REFERENCES stored_file (organization_id, id),
    CONSTRAINT fk_import_job_mapping FOREIGN KEY (organization_id, data_type, column_mapping_id)
        REFERENCES import_column_mapping (organization_id, data_type, id),
    CONSTRAINT ck_import_job_data_type CHECK (data_type IN (
        'PRODUCT', 'CUSTOMER', 'SUPPLIER', 'EMPLOYEE', 'SALES_ORDER', 'INVENTORY_OPENING',
        'INVENTORY_MOVEMENT', 'EXPENSE', 'ORG_UNIT')),
    CONSTRAINT ck_import_job_mode   CHECK (mode IN ('ALL_OR_NOTHING', 'PARTIAL')),
    CONSTRAINT ck_import_job_status CHECK (status IN (
        'UPLOADED', 'VALIDATING', 'VALIDATED', 'IMPORTING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_import_job_counts CHECK (
        total_rows >= 0 AND valid_rows >= 0 AND error_rows >= 0 AND imported_rows >= 0
        AND valid_rows + error_rows <= total_rows AND imported_rows <= valid_rows),
    CONSTRAINT ck_import_job_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);
CREATE INDEX ix_import_job_org_time ON import_job (organization_id, created_at DESC);
COMMENT ON TABLE import_job IS 'Một lần import file; dòng nghiệp vụ được import trỏ về đây qua import_job_id.';
COMMENT ON COLUMN import_job.data_type IS 'Loại dữ liệu nhập (DATA-04).';
COMMENT ON COLUMN import_job.scope_unit_id IS 'Phạm vi đơn vị được nhập (DATA-05).';
COMMENT ON COLUMN import_job.mode IS 'ALL_OR_NOTHING hoặc PARTIAL (BD-07 còn MỞ).';


-- -----------------------------------------------------------------------------
-- import_row_error — Lỗi theo dòng, theo cột của file import
--
-- Vai trò: "Dòng 15, cột Giá: không phải số" (DATA-11); từ đây sinh file lỗi cho
--   người dùng tải (DATA-27). raw_value có thể chứa dữ liệu cá nhân: job dọn các
--   dòng lỗi cũ hơn 90 ngày (ĐỀ XUẤT).
-- -----------------------------------------------------------------------------
CREATE TABLE import_row_error (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT        NOT NULL REFERENCES organization (id),
    import_job_id    BIGINT        NOT NULL,
    row_number       INT           NOT NULL,
    column_name      VARCHAR(100),
    error_code       VARCHAR(50)   NOT NULL,
    message          VARCHAR(500)  NOT NULL,
    raw_value        VARCHAR(500),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_import_row_error_job FOREIGN KEY (organization_id, import_job_id)
        REFERENCES import_job (organization_id, id),
    CONSTRAINT ck_import_row_error_row CHECK (row_number > 0)
);
CREATE INDEX ix_import_row_error_job ON import_row_error (organization_id, import_job_id, row_number);
COMMENT ON TABLE import_row_error IS 'Lỗi theo dòng/cột của một lần import. Dọn sau 90 ngày.';
COMMENT ON COLUMN import_row_error.error_code IS 'Mã lỗi, vd REQUIRED, INVALID_NUMBER, DUPLICATE, NOT_FOUND.';


-- -----------------------------------------------------------------------------
-- export_job — Một lần xuất dữ liệu
--
-- Vai trò: xuất CSV/Excel/PDF theo bộ lọc và phạm vi quyền (DATA-28→33, REPORT-10).
--   Chạy nền, file kết quả nằm trên S3 và hết hạn sau một thời gian. Xuất dữ liệu
--   quan trọng còn ghi audit_log (DATA-33).
-- -----------------------------------------------------------------------------
CREATE TABLE export_job (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT        NOT NULL REFERENCES organization (id),
    export_type      VARCHAR(50)   NOT NULL,
    format           VARCHAR(10)   NOT NULL,
    filter           JSONB         NOT NULL DEFAULT '{}'::jsonb,
    scope_unit_id    BIGINT,
    status           VARCHAR(20)   NOT NULL DEFAULT 'QUEUED',
    row_count        INT,
    file_id          BIGINT,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ,
    error_message    VARCHAR(1000),
    report_schedule_id BIGINT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_export_job_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_export_job_scope FOREIGN KEY (organization_id, scope_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_export_job_file FOREIGN KEY (organization_id, file_id)
        REFERENCES stored_file (organization_id, id),
    CONSTRAINT ck_export_job_format CHECK (format IN ('CSV', 'XLSX', 'PDF')),
    CONSTRAINT ck_export_job_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_export_job_completed CHECK (
        status NOT IN ('COMPLETED', 'EXPIRED') OR (completed_at IS NOT NULL AND file_id IS NOT NULL)),
    CONSTRAINT ck_export_job_rows CHECK (row_count IS NULL OR row_count >= 0)
);
CREATE INDEX ix_export_job_org_time ON export_job (organization_id, created_at DESC);
COMMENT ON TABLE export_job IS 'Một lần xuất dữ liệu; file kết quả trên S3, có hạn tải.';
COMMENT ON COLUMN export_job.export_type IS 'Loại dữ liệu/báo cáo, vd sales.order.list, report.revenue.monthly.';
COMMENT ON COLUMN export_job.report_schedule_id IS 'Lịch báo cáo đã sinh ra lần xuất này (nếu có).';


-- -----------------------------------------------------------------------------
-- saved_view — Bộ lọc và cách xem bảng đã lưu
--
-- Vai trò: người dùng lưu "Đơn chưa giao của chi nhánh A, sắp theo ngày" để mở lại
--   (DATA-25, P1). Có thể chia sẻ cho người khác trong một đơn vị.
-- -----------------------------------------------------------------------------
CREATE TABLE saved_view (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id       BIGINT        NOT NULL REFERENCES organization (id),
    owner_user_id         BIGINT        NOT NULL,
    view_key              VARCHAR(100)  NOT NULL,
    name                  VARCHAR(100)  NOT NULL,
    config                JSONB         NOT NULL,
    is_shared             BOOLEAN       NOT NULL DEFAULT FALSE,
    shared_scope_unit_id  BIGINT,
    is_default            BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version               INT           NOT NULL DEFAULT 0,
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT uq_saved_view_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_saved_view_owner FOREIGN KEY (organization_id, owner_user_id)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT fk_saved_view_scope FOREIGN KEY (organization_id, shared_scope_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT ck_saved_view_config CHECK (jsonb_typeof(config) = 'object'),
    CONSTRAINT ck_saved_view_share  CHECK (is_shared OR shared_scope_unit_id IS NULL)
);
CREATE UNIQUE INDEX uq_saved_view_name ON saved_view (owner_user_id, view_key, lower(name)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_saved_view_default ON saved_view (owner_user_id, view_key) WHERE is_default AND deleted_at IS NULL;
COMMENT ON TABLE saved_view IS 'Bộ lọc, cột, sắp xếp đã lưu của một màn hình bảng.';
COMMENT ON COLUMN saved_view.view_key IS 'Màn hình nào, vd sales.order.list.';
COMMENT ON COLUMN saved_view.config IS 'JSON gồm filters, sort, columns.';


-- -----------------------------------------------------------------------------
-- metric_definition — Định nghĩa chỉ số
--
-- Vai trò: một nơi duy nhất định nghĩa "Doanh thu thuần", "AOV", "Tỷ lệ quá hạn":
--   công thức, nguồn dữ liệu, đơn vị, giới hạn (mục 6.9 tài liệu Nghiệp vụ,
--   BR-REV-04). Dashboard, báo cáo, kho dữ liệu và trợ lý AI đều dùng chung.
-- organization_id NULL: chỉ số hệ thống; có giá trị: chỉ số riêng của doanh nghiệp.
-- Đổi định nghĩa thì tăng version_no, không sửa bản cũ, để báo cáo cũ giải thích được.
-- -----------------------------------------------------------------------------
CREATE TABLE metric_definition (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT        REFERENCES organization (id),
    code             VARCHAR(100)  NOT NULL,
    version_no       SMALLINT      NOT NULL DEFAULT 1,
    name             VARCHAR(200)  NOT NULL,
    description      TEXT          NOT NULL,
    formula          TEXT          NOT NULL,
    source_tables    TEXT[]        NOT NULL,
    unit             VARCHAR(20)   NOT NULL,
    aggregation      VARCHAR(20)   NOT NULL,
    time_basis       VARCHAR(100),
    limitations      TEXT,
    feature_id       BIGINT        REFERENCES feature (id),
    is_active        BOOLEAN       NOT NULL DEFAULT TRUE,
    valid_from       DATE          NOT NULL DEFAULT CURRENT_DATE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_metric_definition_version UNIQUE NULLS NOT DISTINCT (organization_id, code, version_no),
    CONSTRAINT ck_metric_definition_code CHECK (code ~ '^[a-z]+(\.[a-z_]+)+$'),
    CONSTRAINT ck_metric_definition_unit CHECK (unit IN ('VND', 'PERCENT', 'COUNT', 'QUANTITY', 'MINUTES', 'DAYS', 'RATIO')),
    CONSTRAINT ck_metric_definition_aggregation CHECK (aggregation IN ('SUM', 'AVG', 'COUNT', 'RATIO', 'MIN', 'MAX', 'LAST')),
    CONSTRAINT ck_metric_definition_version CHECK (version_no > 0)
);
CREATE UNIQUE INDEX uq_metric_definition_active ON metric_definition (COALESCE(organization_id, 0), code) WHERE is_active;
COMMENT ON TABLE metric_definition IS 'Định nghĩa chỉ số dùng chung cho dashboard, báo cáo, kho dữ liệu và AI.';
COMMENT ON COLUMN metric_definition.organization_id IS 'NULL: chỉ số hệ thống; có giá trị: chỉ số riêng của doanh nghiệp.';
COMMENT ON COLUMN metric_definition.code IS 'Mã chỉ số, vd sales.net_revenue, task.overdue_rate.';
COMMENT ON COLUMN metric_definition.formula IS 'Công thức viết cho người đọc, vd "Doanh thu gộp − hàng bị trả lại".';
COMMENT ON COLUMN metric_definition.time_basis IS 'Tính theo mốc nào, vd completed_at theo múi giờ doanh nghiệp.';
COMMENT ON COLUMN metric_definition.limitations IS 'Giới hạn khi diễn giải; AI phải nêu khi trả lời.';


-- -----------------------------------------------------------------------------
-- report_schedule — Lịch gửi báo cáo định kỳ
--
-- Vai trò: "Gửi doanh số tuần cho phòng Marketing mỗi sáng thứ Hai" (REPORT-09, P1).
--   Job đọc next_run_at, tạo export_job, gửi cho người nhận rồi tính lần chạy sau.
-- recipients: danh sách người nhận dạng JSON, vd
--   [{"type":"USER","id":12}, {"type":"EMAIL","value":"marketing@abc.vn"}].
-- Gửi ra email ngoài doanh nghiệp: chỉ khi có quyền và không chứa dữ liệu cá nhân.
-- -----------------------------------------------------------------------------
CREATE TABLE report_schedule (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id      BIGINT        NOT NULL REFERENCES organization (id),
    name                 VARCHAR(200)  NOT NULL,
    report_code          VARCHAR(100)  NOT NULL,
    format               VARCHAR(10)   NOT NULL DEFAULT 'XLSX',
    filter               JSONB         NOT NULL DEFAULT '{}'::jsonb,
    scope_unit_id        BIGINT,
    cron_expression      VARCHAR(100)  NOT NULL,
    timezone             VARCHAR(50)   NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    recipients           JSONB         NOT NULL,
    next_run_at          TIMESTAMPTZ,
    last_run_at          TIMESTAMPTZ,
    last_export_job_id   BIGINT,
    is_active            BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by           BIGINT,
    version              INT           NOT NULL DEFAULT 0,
    deleted_at           TIMESTAMPTZ,
    CONSTRAINT uq_report_schedule_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_report_schedule_scope FOREIGN KEY (organization_id, scope_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_report_schedule_last_export FOREIGN KEY (organization_id, last_export_job_id)
        REFERENCES export_job (organization_id, id),
    CONSTRAINT ck_report_schedule_format CHECK (format IN ('CSV', 'XLSX', 'PDF')),
    CONSTRAINT ck_report_schedule_recipients CHECK (
        jsonb_typeof(recipients) = 'array' AND jsonb_array_length(recipients) > 0)
);
CREATE INDEX ix_report_schedule_due ON report_schedule (next_run_at) WHERE is_active AND deleted_at IS NULL;
COMMENT ON TABLE report_schedule IS 'Lịch gửi báo cáo định kỳ; mỗi lần chạy tạo một export_job.';
COMMENT ON COLUMN report_schedule.cron_expression IS 'Biểu thức cron theo timezone của lịch, vd 0 7 * * MON.';
COMMENT ON COLUMN report_schedule.recipients IS 'Mảng JSON người nhận (USER theo id, hoặc EMAIL).';

ALTER TABLE export_job
    ADD CONSTRAINT fk_export_job_schedule FOREIGN KEY (organization_id, report_schedule_id)
        REFERENCES report_schedule (organization_id, id);


-- Khoá ngoại import_job_id cho các bảng đã tạo trước V10
ALTER TABLE sales_order
    ADD CONSTRAINT fk_sales_order_import_job FOREIGN KEY (organization_id, import_job_id)
        REFERENCES import_job (organization_id, id);
ALTER TABLE inventory_movement
    ADD CONSTRAINT fk_inventory_movement_import_job FOREIGN KEY (organization_id, import_job_id)
        REFERENCES import_job (organization_id, id);

-- ############################################################################
-- ## V11__operations.sql
-- ############################################################################

-- =============================================================================
-- V11__operations.sql — Ca làm, chi phí, phê duyệt, dịch vụ
-- Bảng: shift_template, shift_assignment, shift_handover, attendance_record,
--       expense_category, approval_request, approval_step, expense, budget,
--       appointment, service_order
-- Phụ thuộc: V1, V2 (app_user, role, btree_gist), V3 (employee), V5 (product,
--            customer, supplier, payment_method), V7 (sales_order),
--            V8 (stored_file), V10 (import_job)
--
-- BẢN NHÁP: các module này chưa có tài liệu quy tắc nghiệp vụ. Trạng thái và
-- ràng buộc là đề xuất; sửa thoải mái trước lần chạy đầu tiên.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- shift_template — Định nghĩa ca làm
--
-- Vai trò: ca sáng 7h–11h, ca chiều, ca đêm... (SHIFT-01). Ca qua nửa đêm khi
--   end_time ≤ start_time (vd 22:00–06:00). org_unit_id NULL là ca dùng chung cả
--   doanh nghiệp.
-- -----------------------------------------------------------------------------
CREATE TABLE shift_template (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT        NOT NULL REFERENCES organization (id),
    org_unit_id      BIGINT,
    code             VARCHAR(30)   NOT NULL,
    name             VARCHAR(100)  NOT NULL,
    start_time       TIME          NOT NULL,
    end_time         TIME          NOT NULL,
    crosses_midnight BOOLEAN       GENERATED ALWAYS AS (end_time <= start_time) STORED,
    break_minutes    SMALLINT      NOT NULL DEFAULT 0,
    status           VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT           NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT uq_shift_template_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_shift_template_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT ck_shift_template_times  CHECK (start_time <> end_time),
    CONSTRAINT ck_shift_template_break  CHECK (break_minutes BETWEEN 0 AND 600),
    CONSTRAINT ck_shift_template_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE UNIQUE INDEX uq_shift_template_code ON shift_template (organization_id, lower(code)) WHERE deleted_at IS NULL;
COMMENT ON TABLE shift_template IS 'Định nghĩa ca làm; ca qua nửa đêm khi end_time ≤ start_time.';
COMMENT ON COLUMN shift_template.crosses_midnight IS 'Cột sinh: ca kết thúc sang ngày hôm sau.';


-- -----------------------------------------------------------------------------
-- shift_assignment — Lịch làm: ai làm ca nào, ngày nào, ở đâu
--
-- Vai trò: lịch làm việc (SHIFT-02→07). planned_start/planned_end là thời điểm
--   cụ thể (đã tính ca qua nửa đêm). Database chặn một nhân viên bị xếp hai ca
--   chồng giờ (SHIFT-11) bằng ràng buộc loại trừ, trừ ca đã huỷ.
-- Đổi ca (SHIFT-06, SHIFT-10): huỷ dòng cũ, tạo dòng mới có replaces_assignment_id.
-- Cho mô hình học: số người trực theo ca, cho dự báo nhân sự và đề xuất lịch (SHIFT-12).
-- -----------------------------------------------------------------------------
CREATE TABLE shift_assignment (
    id                      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id         BIGINT        NOT NULL REFERENCES organization (id),
    org_unit_id             BIGINT        NOT NULL,
    employee_id             BIGINT        NOT NULL,
    shift_template_id       BIGINT,
    work_date               DATE          NOT NULL,
    planned_start           TIMESTAMPTZ   NOT NULL,
    planned_end             TIMESTAMPTZ   NOT NULL,
    position                VARCHAR(30),
    status                  VARCHAR(20)   NOT NULL DEFAULT 'PLANNED',
    replaces_assignment_id  BIGINT,
    cancel_reason           VARCHAR(500),
    note                    VARCHAR(500),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by              BIGINT,
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by              BIGINT,
    version                 INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_shift_assignment_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_shift_assignment_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_shift_assignment_employee FOREIGN KEY (organization_id, employee_id)
        REFERENCES employee (organization_id, id),
    CONSTRAINT fk_shift_assignment_template FOREIGN KEY (organization_id, shift_template_id)
        REFERENCES shift_template (organization_id, id),
    CONSTRAINT fk_shift_assignment_replaces FOREIGN KEY (organization_id, replaces_assignment_id)
        REFERENCES shift_assignment (organization_id, id),
    CONSTRAINT ck_shift_assignment_times  CHECK (planned_end > planned_start),
    CONSTRAINT ck_shift_assignment_status CHECK (status IN (
        'PLANNED', 'CONFIRMED', 'IN_PROGRESS', 'COMPLETED', 'ABSENT', 'CANCELLED')),
    CONSTRAINT ck_shift_assignment_cancel CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL),
    CONSTRAINT ex_shift_assignment_overlap EXCLUDE USING gist (
        employee_id WITH =,
        tstzrange(planned_start, planned_end, '[)') WITH &&
    ) WHERE (status <> 'CANCELLED')
);
CREATE INDEX ix_shift_assignment_unit_date ON shift_assignment (organization_id, org_unit_id, work_date);
CREATE INDEX ix_shift_assignment_employee_date ON shift_assignment (organization_id, employee_id, work_date);
COMMENT ON TABLE shift_assignment IS 'Lịch làm; DB chặn một người bị xếp hai ca chồng giờ (trừ ca đã huỷ).';
COMMENT ON COLUMN shift_assignment.work_date IS 'Ngày của ca theo lịch (ca đêm tính cho ngày bắt đầu).';
COMMENT ON COLUMN shift_assignment.position IS 'Vị trí trong ca, vd CASHIER, LEADER.';


-- -----------------------------------------------------------------------------
-- shift_handover — Bàn giao ca
--
-- Vai trò: biên bản bàn giao giữa hai ca (SHIFT-08) và đối soát tiền mặt đầu/cuối
--   ca cho đơn vị có thu ngân (SHIFT-13). expected_cash do hệ thống tính từ payment
--   tiền mặt trong ca; counted_cash do người bàn giao đếm; cash_difference là cột sinh.
-- -----------------------------------------------------------------------------
CREATE TABLE shift_handover (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id       BIGINT         NOT NULL REFERENCES organization (id),
    org_unit_id           BIGINT         NOT NULL,
    from_assignment_id    BIGINT         NOT NULL,
    to_assignment_id      BIGINT,
    handed_over_at        TIMESTAMPTZ    NOT NULL,
    expected_cash         NUMERIC(18, 2),
    counted_cash          NUMERIC(18, 2),
    cash_difference       NUMERIC(18, 2) GENERATED ALWAYS AS (counted_cash - expected_cash) STORED,
    notes                 TEXT,
    status                VARCHAR(20)    NOT NULL DEFAULT 'SUBMITTED',
    acknowledged_by       BIGINT,
    acknowledged_at       TIMESTAMPTZ,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by            BIGINT,
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by            BIGINT,
    version               INT            NOT NULL DEFAULT 0,
    CONSTRAINT uq_shift_handover_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_shift_handover_from UNIQUE (from_assignment_id),
    CONSTRAINT fk_shift_handover_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_shift_handover_from FOREIGN KEY (organization_id, from_assignment_id)
        REFERENCES shift_assignment (organization_id, id),
    CONSTRAINT fk_shift_handover_to FOREIGN KEY (organization_id, to_assignment_id)
        REFERENCES shift_assignment (organization_id, id),
    CONSTRAINT ck_shift_handover_status CHECK (status IN ('SUBMITTED', 'ACKNOWLEDGED', 'DISPUTED')),
    CONSTRAINT ck_shift_handover_ack CHECK (
        (status = 'SUBMITTED') = (acknowledged_at IS NULL)
        AND (acknowledged_at IS NULL OR acknowledged_by IS NOT NULL)),
    CONSTRAINT ck_shift_handover_cash CHECK (
        (expected_cash IS NULL OR expected_cash >= 0) AND (counted_cash IS NULL OR counted_cash >= 0))
);
COMMENT ON TABLE shift_handover IS 'Bàn giao ca và đối soát tiền mặt; chênh lệch là cột sinh.';
COMMENT ON COLUMN shift_handover.expected_cash IS 'Tiền mặt hệ thống tính được trong ca.';
COMMENT ON COLUMN shift_handover.counted_cash IS 'Tiền mặt đếm thực tế khi bàn giao.';


-- -----------------------------------------------------------------------------
-- attendance_record — Chấm công
--
-- Vai trò: giờ vào/ra thực tế (SHIFT-09, P2), so với lịch để biết đi muộn, về sớm.
--   Sửa giờ chấm công phải có người sửa và lý do. Không lưu vị trí GPS (dữ liệu cá nhân).
-- worked_minutes: cột sinh từ giờ vào/ra.
-- -----------------------------------------------------------------------------
CREATE TABLE attendance_record (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id      BIGINT        NOT NULL REFERENCES organization (id),
    employee_id          BIGINT        NOT NULL,
    org_unit_id          BIGINT        NOT NULL,
    shift_assignment_id  BIGINT,
    check_in_at          TIMESTAMPTZ   NOT NULL,
    check_out_at         TIMESTAMPTZ,
    worked_minutes       INT           GENERATED ALWAYS AS (
        CASE WHEN check_out_at IS NULL THEN NULL
             ELSE (EXTRACT(EPOCH FROM (check_out_at - check_in_at)) / 60)::INT END) STORED,
    source               VARCHAR(10)   NOT NULL DEFAULT 'MANUAL',
    status               VARCHAR(20)   NOT NULL DEFAULT 'NORMAL',
    adjusted_by          BIGINT,
    adjust_reason        VARCHAR(500),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by           BIGINT,
    version              INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_attendance_record_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_attendance_record_employee FOREIGN KEY (organization_id, employee_id)
        REFERENCES employee (organization_id, id),
    CONSTRAINT fk_attendance_record_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_attendance_record_shift FOREIGN KEY (organization_id, shift_assignment_id)
        REFERENCES shift_assignment (organization_id, id),
    CONSTRAINT ck_attendance_record_times  CHECK (check_out_at IS NULL OR check_out_at > check_in_at),
    CONSTRAINT ck_attendance_record_source CHECK (source IN ('MANUAL', 'DEVICE', 'MOBILE', 'IMPORT')),
    CONSTRAINT ck_attendance_record_status CHECK (status IN (
        'NORMAL', 'LATE', 'EARLY_LEAVE', 'LATE_AND_EARLY', 'MISSING_CHECKOUT', 'ADJUSTED')),
    CONSTRAINT ck_attendance_record_adjust CHECK (
        status <> 'ADJUSTED' OR (adjusted_by IS NOT NULL AND adjust_reason IS NOT NULL))
);
CREATE INDEX ix_attendance_record_employee ON attendance_record (organization_id, employee_id, check_in_at DESC);
CREATE INDEX ix_attendance_record_unit ON attendance_record (organization_id, org_unit_id, check_in_at DESC);
COMMENT ON TABLE attendance_record IS 'Giờ vào/ra thực tế; sửa phải có người sửa và lý do.';
COMMENT ON COLUMN attendance_record.worked_minutes IS 'Số phút làm, cột sinh; NULL khi chưa chấm ra.';


-- -----------------------------------------------------------------------------
-- expense_category — Loại chi phí
--
-- Vai trò: điện nước, mặt bằng, vận chuyển, lương thời vụ... (EXP-02), lồng nhau được.
-- -----------------------------------------------------------------------------
CREATE TABLE expense_category (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT        NOT NULL REFERENCES organization (id),
    parent_id        BIGINT,
    code             VARCHAR(50)   NOT NULL,
    name             VARCHAR(200)  NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT           NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT uq_expense_category_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_expense_category_parent FOREIGN KEY (organization_id, parent_id)
        REFERENCES expense_category (organization_id, id),
    CONSTRAINT ck_expense_category_not_self CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_expense_category_status   CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE UNIQUE INDEX uq_expense_category_code ON expense_category (organization_id, lower(code)) WHERE deleted_at IS NULL;
COMMENT ON TABLE expense_category IS 'Loại chi phí, lồng nhau được. Xoá mềm.';


-- -----------------------------------------------------------------------------
-- approval_request — Yêu cầu phê duyệt
--
-- Vai trò: một việc cần người có thẩm quyền duyệt (APPROVAL-01→05): khoản chi, đề
--   xuất của AI, đổi ca, điều chỉnh kho, huỷ đơn... Đối tượng được duyệt trỏ bằng
--   entity_type/entity_id (không khoá ngoại); bảng nghiệp vụ tự giữ khoá ngoại tới
--   yêu cầu (vd expense.approval_request_id).
-- Mọi yêu cầu có ít nhất một bước ở approval_step (duyệt một cấp = một bước), để
--   duyệt nhiều cấp (APPROVAL-06) không cần đổi cấu trúc.
-- -----------------------------------------------------------------------------
CREATE TABLE approval_request (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id    BIGINT         NOT NULL REFERENCES organization (id),
    code               VARCHAR(30)    NOT NULL,
    request_type       VARCHAR(30)    NOT NULL,
    entity_type        VARCHAR(50)    NOT NULL,
    entity_id          BIGINT         NOT NULL,
    org_unit_id        BIGINT         NOT NULL,
    requested_by       BIGINT         NOT NULL,
    title              VARCHAR(255)   NOT NULL,
    summary            TEXT,
    amount             NUMERIC(18, 2),
    status             VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    current_step_no    SMALLINT       NOT NULL DEFAULT 1,
    due_at             TIMESTAMPTZ,
    decided_at         TIMESTAMPTZ,
    decided_by         BIGINT,
    decision_reason    VARCHAR(1000),
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by         BIGINT,
    version            INT            NOT NULL DEFAULT 0,
    CONSTRAINT uq_approval_request_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_approval_request_code   UNIQUE (organization_id, code),
    CONSTRAINT fk_approval_request_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_approval_request_requester FOREIGN KEY (organization_id, requested_by)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT fk_approval_request_decider FOREIGN KEY (organization_id, decided_by)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT ck_approval_request_type CHECK (request_type IN (
        'EXPENSE', 'AI_ACTION', 'SHIFT_SWAP', 'INVENTORY_ADJUSTMENT', 'ORDER_CANCEL', 'REFUND', 'OTHER')),
    CONSTRAINT ck_approval_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_approval_request_decided CHECK (
        (status IN ('APPROVED', 'REJECTED')) = (decided_at IS NOT NULL AND decided_by IS NOT NULL)
        AND (status <> 'REJECTED' OR decision_reason IS NOT NULL)),
    CONSTRAINT ck_approval_request_step CHECK (current_step_no > 0),
    CONSTRAINT ck_approval_request_amount CHECK (amount IS NULL OR amount >= 0)
);
CREATE INDEX ix_approval_request_pending ON approval_request (organization_id, org_unit_id, created_at)
    WHERE status = 'PENDING';
CREATE INDEX ix_approval_request_entity ON approval_request (organization_id, entity_type, entity_id);
COMMENT ON TABLE approval_request IS 'Yêu cầu phê duyệt cho một đối tượng nghiệp vụ; luôn có ít nhất một bước.';
COMMENT ON COLUMN approval_request.entity_type IS 'Đối tượng được duyệt, vd expense, ai_action_request.';
COMMENT ON COLUMN approval_request.current_step_no IS 'Bước đang chờ duyệt.';
COMMENT ON COLUMN approval_request.decision_reason IS 'Lý do quyết định; bắt buộc khi từ chối (APPROVAL-04).';


-- -----------------------------------------------------------------------------
-- approval_step — Từng bước duyệt
--
-- Vai trò: ai duyệt ở bước nào (APPROVAL-02, 06). Người duyệt là một người cụ thể
--   hoặc bất kỳ ai có vai trò approver_role_id trong phạm vi đơn vị của yêu cầu.
--   Bước sau chỉ mở khi bước trước được duyệt (WAITING → PENDING).
-- -----------------------------------------------------------------------------
CREATE TABLE approval_step (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id      BIGINT         NOT NULL REFERENCES organization (id),
    approval_request_id  BIGINT         NOT NULL,
    step_no              SMALLINT       NOT NULL,
    approver_user_id     BIGINT,
    approver_role_id     BIGINT,
    status               VARCHAR(20)    NOT NULL DEFAULT 'WAITING',
    decided_at           TIMESTAMPTZ,
    decided_by           BIGINT,
    comment              VARCHAR(1000),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version              INT            NOT NULL DEFAULT 0,
    CONSTRAINT uq_approval_step_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_approval_step_no UNIQUE (approval_request_id, step_no),
    CONSTRAINT fk_approval_step_request FOREIGN KEY (organization_id, approval_request_id)
        REFERENCES approval_request (organization_id, id),
    CONSTRAINT fk_approval_step_user FOREIGN KEY (organization_id, approver_user_id)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT fk_approval_step_role FOREIGN KEY (organization_id, approver_role_id)
        REFERENCES role (organization_id, id),
    CONSTRAINT fk_approval_step_decider FOREIGN KEY (organization_id, decided_by)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT ck_approval_step_approver CHECK (approver_user_id IS NOT NULL OR approver_role_id IS NOT NULL),
    CONSTRAINT ck_approval_step_no CHECK (step_no > 0),
    CONSTRAINT ck_approval_step_status CHECK (status IN ('WAITING', 'PENDING', 'APPROVED', 'REJECTED', 'SKIPPED')),
    CONSTRAINT ck_approval_step_decided CHECK (
        (status IN ('APPROVED', 'REJECTED')) = (decided_at IS NOT NULL AND decided_by IS NOT NULL))
);
CREATE INDEX ix_approval_step_pending_user ON approval_step (organization_id, approver_user_id)
    WHERE status = 'PENDING';
CREATE INDEX ix_approval_step_pending_role ON approval_step (organization_id, approver_role_id)
    WHERE status = 'PENDING';
COMMENT ON TABLE approval_step IS 'Bước duyệt: người duyệt cụ thể hoặc theo vai trò; mở lần lượt.';


-- -----------------------------------------------------------------------------
-- expense — Khoản chi
--
-- Vai trò: một khoản chi của đơn vị (EXP-01→07): số tiền, loại, ngày chi, người
--   chi, chứng từ, trạng thái duyệt.
-- Vòng đời: DRAFT → SUBMITTED → APPROVED → PAID; SUBMITTED → REJECTED;
--   DRAFT/SUBMITTED → CANCELLED. Báo cáo chi phí tính khoản APPROVED và PAID.
-- Cho mô hình học: phát hiện chi phí bất thường (AI-13) theo đơn vị × loại × tháng.
-- -----------------------------------------------------------------------------
CREATE TABLE expense (
    id                     BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id        BIGINT         NOT NULL REFERENCES organization (id),
    code                   VARCHAR(30)    NOT NULL,
    org_unit_id            BIGINT         NOT NULL,
    expense_category_id    BIGINT         NOT NULL,
    amount                 NUMERIC(18, 2) NOT NULL,
    currency               CHAR(3)        NOT NULL DEFAULT 'VND',
    expense_date           DATE           NOT NULL,
    description            VARCHAR(1000)  NOT NULL,
    payee_name             VARCHAR(200),
    supplier_id            BIGINT,
    spent_by_employee_id   BIGINT,
    payment_method_id      BIGINT,
    receipt_file_id        BIGINT,
    status                 VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    approval_request_id    BIGINT,
    submitted_at           TIMESTAMPTZ,
    approved_at            TIMESTAMPTZ,
    paid_at                TIMESTAMPTZ,
    rejected_reason        VARCHAR(1000),
    cancelled_at           TIMESTAMPTZ,
    source                 VARCHAR(20)    NOT NULL DEFAULT 'MANUAL',
    import_job_id          BIGINT,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by             BIGINT,
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by             BIGINT,
    version                INT            NOT NULL DEFAULT 0,
    CONSTRAINT uq_expense_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_expense_code   UNIQUE (organization_id, code),
    CONSTRAINT fk_expense_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_expense_category FOREIGN KEY (organization_id, expense_category_id)
        REFERENCES expense_category (organization_id, id),
    CONSTRAINT fk_expense_supplier FOREIGN KEY (organization_id, supplier_id)
        REFERENCES supplier (organization_id, id),
    CONSTRAINT fk_expense_spent_by FOREIGN KEY (organization_id, spent_by_employee_id)
        REFERENCES employee (organization_id, id),
    CONSTRAINT fk_expense_payment_method FOREIGN KEY (organization_id, payment_method_id)
        REFERENCES payment_method (organization_id, id),
    CONSTRAINT fk_expense_receipt FOREIGN KEY (organization_id, receipt_file_id)
        REFERENCES stored_file (organization_id, id),
    CONSTRAINT fk_expense_approval FOREIGN KEY (organization_id, approval_request_id)
        REFERENCES approval_request (organization_id, id),
    CONSTRAINT fk_expense_import_job FOREIGN KEY (organization_id, import_job_id)
        REFERENCES import_job (organization_id, id),
    CONSTRAINT ck_expense_amount CHECK (amount > 0),
    CONSTRAINT ck_expense_status CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'PAID', 'CANCELLED')),
    CONSTRAINT ck_expense_source CHECK (source IN ('MANUAL', 'IMPORT', 'API')),
    CONSTRAINT ck_expense_times CHECK (
        (status = 'DRAFT' OR status = 'CANCELLED' OR submitted_at IS NOT NULL OR source <> 'MANUAL')
        AND (status NOT IN ('APPROVED', 'PAID') OR approved_at IS NOT NULL)
        AND ((status = 'PAID') = (paid_at IS NOT NULL))
        AND ((status = 'REJECTED') = (rejected_reason IS NOT NULL))
        AND ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)))
);
CREATE INDEX ix_expense_unit_date ON expense (organization_id, org_unit_id, expense_date);
CREATE INDEX ix_expense_category_date ON expense (organization_id, expense_category_id, expense_date);
CREATE INDEX ix_expense_updated ON expense (updated_at);
COMMENT ON TABLE expense IS 'Khoản chi của đơn vị, có vòng đời duyệt. Báo cáo tính APPROVED và PAID.';
COMMENT ON COLUMN expense.expense_date IS 'Ngày phát sinh chi (giờ nghiệp vụ).';
COMMENT ON COLUMN expense.payee_name IS 'Người/đơn vị nhận tiền khi không phải nhà cung cấp có trong danh mục.';
COMMENT ON COLUMN expense.spent_by_employee_id IS 'Người thực hiện khoản chi (EXP-04).';


-- -----------------------------------------------------------------------------
-- budget — Ngân sách
--
-- Vai trò: hạn mức chi theo đơn vị, loại chi phí và kỳ (EXP-08, P2); cảnh báo khi
--   chi vượt alert_threshold_percent (EXP-09). expense_category_id NULL là ngân
--   sách tổng của đơn vị.
-- -----------------------------------------------------------------------------
CREATE TABLE budget (
    id                       BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id          BIGINT         NOT NULL REFERENCES organization (id),
    org_unit_id              BIGINT         NOT NULL,
    expense_category_id      BIGINT,
    period_type              VARCHAR(10)    NOT NULL,
    period_start             DATE           NOT NULL,
    period_end               DATE           NOT NULL,
    amount                   NUMERIC(18, 2) NOT NULL,
    alert_threshold_percent  SMALLINT       NOT NULL DEFAULT 80,
    note                     VARCHAR(500),
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by               BIGINT,
    updated_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by               BIGINT,
    version                  INT            NOT NULL DEFAULT 0,
    CONSTRAINT uq_budget_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_budget_period UNIQUE NULLS NOT DISTINCT (organization_id, org_unit_id, expense_category_id, period_start, period_type),
    CONSTRAINT fk_budget_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_budget_category FOREIGN KEY (organization_id, expense_category_id)
        REFERENCES expense_category (organization_id, id),
    CONSTRAINT ck_budget_period_type CHECK (period_type IN ('MONTH', 'QUARTER', 'YEAR')),
    CONSTRAINT ck_budget_period CHECK (period_end > period_start),
    CONSTRAINT ck_budget_amount CHECK (amount > 0),
    CONSTRAINT ck_budget_threshold CHECK (alert_threshold_percent BETWEEN 1 AND 100)
);
COMMENT ON TABLE budget IS 'Ngân sách theo đơn vị × loại chi phí × kỳ; NULL loại là ngân sách tổng.';


-- -----------------------------------------------------------------------------
-- appointment — Lịch hẹn dịch vụ
--
-- Vai trò: khách đặt lịch (SERVICE-07, P2): dịch vụ gì, ai phục vụ, lúc nào.
--   Database chặn một nhân viên có hai lịch hẹn chồng giờ khi lịch còn hiệu lực.
-- Cho mô hình học: tỷ lệ khách không đến (NO_SHOW), dự báo nhu cầu dịch vụ (AI-08).
-- -----------------------------------------------------------------------------
CREATE TABLE appointment (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT        NOT NULL REFERENCES organization (id),
    code             VARCHAR(30)   NOT NULL,
    org_unit_id      BIGINT        NOT NULL,
    customer_id      BIGINT,
    product_id       BIGINT        NOT NULL,
    employee_id      BIGINT,
    starts_at        TIMESTAMPTZ   NOT NULL,
    ends_at          TIMESTAMPTZ   NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'BOOKED',
    channel          VARCHAR(20)   NOT NULL DEFAULT 'IN_STORE',
    cancel_reason    VARCHAR(500),
    note             VARCHAR(1000),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_appointment_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_appointment_code   UNIQUE (organization_id, code),
    CONSTRAINT fk_appointment_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_appointment_customer FOREIGN KEY (organization_id, customer_id)
        REFERENCES customer (organization_id, id),
    CONSTRAINT fk_appointment_product FOREIGN KEY (organization_id, product_id)
        REFERENCES product (organization_id, id),
    CONSTRAINT fk_appointment_employee FOREIGN KEY (organization_id, employee_id)
        REFERENCES employee (organization_id, id),
    CONSTRAINT ck_appointment_times   CHECK (ends_at > starts_at),
    CONSTRAINT ck_appointment_status  CHECK (status IN (
        'BOOKED', 'CONFIRMED', 'ARRIVED', 'COMPLETED', 'NO_SHOW', 'CANCELLED')),
    CONSTRAINT ck_appointment_channel CHECK (channel IN ('IN_STORE', 'PHONE', 'ONLINE', 'OTHER')),
    CONSTRAINT ck_appointment_cancel  CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL),
    CONSTRAINT ex_appointment_employee_overlap EXCLUDE USING gist (
        employee_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (employee_id IS NOT NULL AND status IN ('BOOKED', 'CONFIRMED', 'ARRIVED'))
);
CREATE INDEX ix_appointment_unit_time ON appointment (organization_id, org_unit_id, starts_at);
COMMENT ON TABLE appointment IS 'Lịch hẹn dịch vụ; DB chặn nhân viên bị đặt hai lịch chồng giờ.';
COMMENT ON COLUMN appointment.product_id IS 'Dịch vụ được đặt (product loại SERVICE; service kiểm tra loại).';


-- -----------------------------------------------------------------------------
-- service_order — Đơn dịch vụ
--
-- Vai trò: một lần thực hiện dịch vụ (SERVICE-02→06): dịch vụ gì, cho khách nào, ai
--   làm, trạng thái, kết quả. Tiền thu qua sales_order (dòng hàng là dịch vụ), nên
--   doanh thu dịch vụ dùng chung quy tắc ở mục 7 của tài liệu quy tắc.
-- Vòng đời: NEW → ASSIGNED → IN_PROGRESS → COMPLETED; chưa xong thì → CANCELLED.
-- duration_minutes: cột sinh, thời lượng thực tế (SERVICE-08).
-- -----------------------------------------------------------------------------
CREATE TABLE service_order (
    id                     BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id        BIGINT        NOT NULL REFERENCES organization (id),
    code                   VARCHAR(30)   NOT NULL,
    org_unit_id            BIGINT        NOT NULL,
    product_id             BIGINT        NOT NULL,
    customer_id            BIGINT,
    appointment_id         BIGINT,
    sales_order_id         BIGINT,
    assigned_employee_id   BIGINT,
    status                 VARCHAR(20)   NOT NULL DEFAULT 'NEW',
    scheduled_start        TIMESTAMPTZ,
    started_at             TIMESTAMPTZ,
    completed_at           TIMESTAMPTZ,
    cancelled_at           TIMESTAMPTZ,
    duration_minutes       INT           GENERATED ALWAYS AS (
        CASE WHEN started_at IS NULL OR completed_at IS NULL THEN NULL
             ELSE (EXTRACT(EPOCH FROM (completed_at - started_at)) / 60)::INT END) STORED,
    result_note            TEXT,
    cancel_reason          VARCHAR(500),
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by             BIGINT,
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by             BIGINT,
    version                INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_service_order_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_service_order_code   UNIQUE (organization_id, code),
    CONSTRAINT fk_service_order_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_service_order_product FOREIGN KEY (organization_id, product_id)
        REFERENCES product (organization_id, id),
    CONSTRAINT fk_service_order_customer FOREIGN KEY (organization_id, customer_id)
        REFERENCES customer (organization_id, id),
    CONSTRAINT fk_service_order_appointment FOREIGN KEY (organization_id, appointment_id)
        REFERENCES appointment (organization_id, id),
    CONSTRAINT fk_service_order_sales_order FOREIGN KEY (organization_id, sales_order_id)
        REFERENCES sales_order (organization_id, id),
    CONSTRAINT fk_service_order_employee FOREIGN KEY (organization_id, assigned_employee_id)
        REFERENCES employee (organization_id, id),
    CONSTRAINT ck_service_order_status CHECK (status IN ('NEW', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_service_order_times CHECK (
        (status NOT IN ('ASSIGNED', 'IN_PROGRESS', 'COMPLETED') OR assigned_employee_id IS NOT NULL)
        AND (status NOT IN ('IN_PROGRESS', 'COMPLETED') OR started_at IS NOT NULL)
        AND ((status = 'COMPLETED') = (completed_at IS NOT NULL))
        AND ((status = 'CANCELLED') = (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL))
        AND (completed_at IS NULL OR completed_at >= started_at))
);
CREATE INDEX ix_service_order_unit_status ON service_order (organization_id, org_unit_id, status);
CREATE INDEX ix_service_order_employee ON service_order (organization_id, assigned_employee_id, scheduled_start)
    WHERE status IN ('ASSIGNED', 'IN_PROGRESS');
COMMENT ON TABLE service_order IS 'Một lần thực hiện dịch vụ; tiền thu qua sales_order.';
COMMENT ON COLUMN service_order.duration_minutes IS 'Thời lượng thực tế, cột sinh (SERVICE-08).';
COMMENT ON COLUMN service_order.result_note IS 'Kết quả hoàn thành (SERVICE-05).';

-- ############################################################################
-- ## V12__saas_integration.sql
-- ############################################################################

-- =============================================================================
-- V12__saas_integration.sql — Nền tảng SaaS và tích hợp
-- Bảng: platform_user, subscription_plan, organization_subscription,
--       integration_connection, sync_job
-- Phụ thuộc: V1, V10 (import_job)
--
-- BẢN NHÁP (M7). platform_user và subscription_plan là bảng cấp nền tảng: không có
-- organization_id, chỉ quản trị viên OpsPilot (phía bạn) truy cập.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- platform_user — Tài khoản quản trị nền tảng
--
-- Vai trò: người vận hành OpsPilot (bạn và đội hỗ trợ): tạo tenant, xem gói, hỗ
--   trợ khách (SAAS-04, 09, 14). Tách hẳn khỏi app_user để tài khoản của doanh
--   nghiệp không bao giờ có quyền nền tảng. Bắt buộc MFA ở production (ĐỀ XUẤT).
-- -----------------------------------------------------------------------------
CREATE TABLE platform_user (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    email            VARCHAR(255)  NOT NULL,
    password_hash    VARCHAR(255)  NOT NULL,
    display_name     VARCHAR(200)  NOT NULL,
    platform_role    VARCHAR(20)   NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    mfa_enabled      BOOLEAN       NOT NULL DEFAULT FALSE,
    token_version    INT           NOT NULL DEFAULT 0,
    last_login_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by       BIGINT,
    version          INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_platform_user_role   CHECK (platform_role IN ('SUPER_ADMIN', 'SUPPORT', 'BILLING')),
    CONSTRAINT ck_platform_user_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
);
CREATE UNIQUE INDEX uq_platform_user_email ON platform_user (lower(email));
COMMENT ON TABLE platform_user IS 'Quản trị viên nền tảng OpsPilot; tách khỏi app_user của doanh nghiệp.';
COMMENT ON COLUMN platform_user.platform_role IS 'SUPER_ADMIN, SUPPORT (hỗ trợ khách, chỉ đọc), BILLING (gói và thanh toán).';


-- -----------------------------------------------------------------------------
-- subscription_plan — Gói dịch vụ
--
-- Vai trò: các gói bán cho doanh nghiệp (SAAS-11, 12): giá, giới hạn sử dụng, phân
--   hệ được dùng. limits ví dụ {"max_users": 50, "max_units": 10,
--   "ai_requests_per_month": 2000}. included_features là mã trong bảng feature.
-- -----------------------------------------------------------------------------
CREATE TABLE subscription_plan (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code               VARCHAR(30)    NOT NULL,
    name               VARCHAR(100)   NOT NULL,
    description        VARCHAR(1000),
    price_monthly      NUMERIC(18, 2) NOT NULL,
    price_yearly       NUMERIC(18, 2),
    currency           CHAR(3)        NOT NULL DEFAULT 'VND',
    limits             JSONB          NOT NULL DEFAULT '{}'::jsonb,
    included_features  TEXT[]         NOT NULL DEFAULT '{}',
    trial_days         SMALLINT       NOT NULL DEFAULT 0,
    is_public          BOOLEAN        NOT NULL DEFAULT TRUE,
    status             VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by         BIGINT,
    version            INT            NOT NULL DEFAULT 0,
    CONSTRAINT ck_subscription_plan_price  CHECK (price_monthly >= 0 AND (price_yearly IS NULL OR price_yearly >= 0)),
    CONSTRAINT ck_subscription_plan_limits CHECK (jsonb_typeof(limits) = 'object'),
    CONSTRAINT ck_subscription_plan_trial  CHECK (trial_days BETWEEN 0 AND 365),
    CONSTRAINT ck_subscription_plan_status CHECK (status IN ('ACTIVE', 'RETIRED'))
);
CREATE UNIQUE INDEX uq_subscription_plan_code ON subscription_plan (lower(code));
COMMENT ON TABLE subscription_plan IS 'Gói dịch vụ của nền tảng: giá, giới hạn, phân hệ đi kèm.';
COMMENT ON COLUMN subscription_plan.limits IS 'Giới hạn sử dụng dạng JSON, vd max_users, max_units, ai_requests_per_month.';
COMMENT ON COLUMN subscription_plan.included_features IS 'Mã feature được dùng trong gói.';


-- -----------------------------------------------------------------------------
-- organization_subscription — Doanh nghiệp dùng gói nào
--
-- Vai trò: lịch sử gói của từng doanh nghiệp (SAAS-13). Mỗi doanh nghiệp có tối đa
--   một gói đang hiệu lực (TRIAL, ACTIVE, PAST_DUE); đổi gói thì kết thúc dòng cũ,
--   tạo dòng mới. limits_override: giới hạn riêng đã thoả thuận.
-- -----------------------------------------------------------------------------
CREATE TABLE organization_subscription (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id       BIGINT        NOT NULL REFERENCES organization (id),
    plan_id               BIGINT        NOT NULL REFERENCES subscription_plan (id),
    status                VARCHAR(20)   NOT NULL,
    billing_cycle         VARCHAR(10)   NOT NULL DEFAULT 'MONTHLY',
    started_at            TIMESTAMPTZ   NOT NULL,
    current_period_start  TIMESTAMPTZ   NOT NULL,
    current_period_end    TIMESTAMPTZ   NOT NULL,
    trial_ends_at         TIMESTAMPTZ,
    ended_at              TIMESTAMPTZ,
    cancel_reason         VARCHAR(500),
    limits_override       JSONB         NOT NULL DEFAULT '{}'::jsonb,
    external_ref          VARCHAR(100),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            BIGINT,
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by            BIGINT,
    version               INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_organization_subscription_status CHECK (status IN (
        'TRIAL', 'ACTIVE', 'PAST_DUE', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_organization_subscription_cycle CHECK (billing_cycle IN ('MONTHLY', 'YEARLY')),
    CONSTRAINT ck_organization_subscription_period CHECK (current_period_end > current_period_start),
    CONSTRAINT ck_organization_subscription_trial CHECK (status <> 'TRIAL' OR trial_ends_at IS NOT NULL),
    CONSTRAINT ck_organization_subscription_ended CHECK (
        (status IN ('CANCELLED', 'EXPIRED')) = (ended_at IS NOT NULL)),
    CONSTRAINT ck_organization_subscription_limits CHECK (jsonb_typeof(limits_override) = 'object')
);
CREATE UNIQUE INDEX uq_organization_subscription_current ON organization_subscription (organization_id)
    WHERE status IN ('TRIAL', 'ACTIVE', 'PAST_DUE');
CREATE INDEX ix_organization_subscription_period ON organization_subscription (current_period_end)
    WHERE status IN ('TRIAL', 'ACTIVE', 'PAST_DUE');
COMMENT ON TABLE organization_subscription IS 'Lịch sử gói của doanh nghiệp; tối đa một gói đang hiệu lực.';
COMMENT ON COLUMN organization_subscription.external_ref IS 'Mã bên cổng thanh toán (nếu có).';


-- -----------------------------------------------------------------------------
-- integration_connection — Kết nối tới hệ thống ngoài
--
-- Vai trò: Google Sheets, phần mềm POS, kế toán, API riêng (DATA-34→36, EXP-11).
-- KHÔNG lưu mật khẩu, token hay API key trong database: credentials_secret_ref chỉ
--   là tên khoá trong kho bí mật (AWS Secrets Manager hoặc tương đương). Service
--   đọc bí mật lúc chạy; log và audit không bao giờ in giá trị bí mật.
-- -----------------------------------------------------------------------------
CREATE TABLE integration_connection (
    id                      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id         BIGINT        NOT NULL REFERENCES organization (id),
    provider                VARCHAR(30)   NOT NULL,
    name                    VARCHAR(100)  NOT NULL,
    auth_type               VARCHAR(20)   NOT NULL,
    credentials_secret_ref  VARCHAR(200),
    config                  JSONB         NOT NULL DEFAULT '{}'::jsonb,
    scope_unit_id           BIGINT,
    schedule_cron           VARCHAR(100),
    status                  VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    last_success_at         TIMESTAMPTZ,
    last_error_at           TIMESTAMPTZ,
    last_error              VARCHAR(1000),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by              BIGINT,
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by              BIGINT,
    version                 INT           NOT NULL DEFAULT 0,
    deleted_at              TIMESTAMPTZ,
    CONSTRAINT uq_integration_connection_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_integration_connection_scope FOREIGN KEY (organization_id, scope_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT ck_integration_connection_provider CHECK (provider ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    CONSTRAINT ck_integration_connection_auth CHECK (auth_type IN ('OAUTH2', 'API_KEY', 'BASIC', 'NONE')),
    CONSTRAINT ck_integration_connection_secret CHECK (auth_type = 'NONE' OR credentials_secret_ref IS NOT NULL),
    CONSTRAINT ck_integration_connection_status CHECK (status IN ('ACTIVE', 'PAUSED', 'ERROR', 'REVOKED')),
    CONSTRAINT ck_integration_connection_config CHECK (jsonb_typeof(config) = 'object')
);
CREATE UNIQUE INDEX uq_integration_connection_name ON integration_connection (organization_id, lower(name))
    WHERE deleted_at IS NULL;
COMMENT ON TABLE integration_connection IS 'Kết nối hệ thống ngoài; bí mật nằm trong kho bí mật, DB chỉ giữ tên khoá.';
COMMENT ON COLUMN integration_connection.provider IS 'Mã nhà cung cấp, vd GOOGLE_SHEETS, MISA, KIOTVIET, CUSTOM_API.';
COMMENT ON COLUMN integration_connection.credentials_secret_ref IS 'Tên khoá trong kho bí mật; KHÔNG phải giá trị bí mật.';


-- -----------------------------------------------------------------------------
-- sync_job — Một lần đồng bộ với hệ thống ngoài
--
-- Vai trò: lịch sử đồng bộ (DATA-37): chiều nào, bao nhiêu dòng, đồng bộ tới mốc
--   nào (watermark) để lần sau chỉ lấy phần mới. Dữ liệu đồng bộ vào ghi
--   source = API; nếu đi qua luồng import thì gắn import_job_id.
-- -----------------------------------------------------------------------------
CREATE TABLE sync_job (
    id                         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id            BIGINT        NOT NULL REFERENCES organization (id),
    integration_connection_id  BIGINT        NOT NULL,
    data_type                  VARCHAR(30)   NOT NULL,
    direction                  VARCHAR(5)    NOT NULL,
    trigger_type               VARCHAR(10)   NOT NULL,
    status                     VARCHAR(20)   NOT NULL DEFAULT 'QUEUED',
    started_at                 TIMESTAMPTZ,
    finished_at                TIMESTAMPTZ,
    rows_read                  INT           NOT NULL DEFAULT 0,
    rows_written               INT           NOT NULL DEFAULT 0,
    rows_failed                INT           NOT NULL DEFAULT 0,
    watermark_from             VARCHAR(100),
    watermark_to               VARCHAR(100),
    import_job_id              BIGINT,
    error_message              VARCHAR(2000),
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by                 BIGINT,
    CONSTRAINT uq_sync_job_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_sync_job_connection FOREIGN KEY (organization_id, integration_connection_id)
        REFERENCES integration_connection (organization_id, id),
    CONSTRAINT fk_sync_job_import FOREIGN KEY (organization_id, import_job_id)
        REFERENCES import_job (organization_id, id),
    CONSTRAINT ck_sync_job_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_sync_job_trigger   CHECK (trigger_type IN ('SCHEDULE', 'MANUAL', 'WEBHOOK')),
    CONSTRAINT ck_sync_job_status    CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_sync_job_rows      CHECK (rows_read >= 0 AND rows_written >= 0 AND rows_failed >= 0),
    CONSTRAINT ck_sync_job_finished  CHECK (
        (status IN ('COMPLETED', 'PARTIAL', 'FAILED')) = (finished_at IS NOT NULL))
);
CREATE INDEX ix_sync_job_connection ON sync_job (organization_id, integration_connection_id, created_at DESC);
COMMENT ON TABLE sync_job IS 'Một lần đồng bộ; watermark để lần sau chỉ lấy dữ liệu mới.';
COMMENT ON COLUMN sync_job.watermark_to IS 'Mốc đã đồng bộ tới (thời gian hoặc con trỏ của bên ngoài).';

-- ############################################################################
-- ## V13__ai_ml.sql
-- ############################################################################

-- =============================================================================
-- V13__ai_ml.sql — Trợ lý AI, dự báo, cảnh báo bất thường, quản lý mô hình học
-- Bảng: ml_dataset, ml_model, ai_conversation, ai_message, ai_tool_call,
--       ai_action_request, forecast_run, forecast_value (chia partition theo
--       tháng), anomaly_alert, ai_eval_case
-- doc_chunk (RAG, cần extension pgvector) KHÔNG nằm ở đây: file riêng
--   deferred/doc_chunk_pgvector.sql, chỉ chạy khi bắt đầu làm AI tra cứu tài liệu
--   và database đã có pgvector. Nhờ vậy V1–V14 chạy trên image postgres:18 chuẩn.
-- Phụ thuộc: V1, V2 (app_user), V4 (ensure_monthly_partitions), V5 (product),
--            V6 (forbid_update_delete), V9 (task), V11 (approval_request)
--
-- BẢN NHÁP (M7). Nguyên tắc cố định:
--   * AI chỉ gọi công cụ qua API có kiểm tra quyền, không viết SQL (ai_tool_call).
--   * Thao tác thay đổi dữ liệu do AI đề xuất phải có người duyệt (ai_action_request).
--   * Bộ dữ liệu huấn luyện không chứa dữ liệu cá nhân (ml_dataset.contains_pii).
--   * Mô hình chạy qua Ollama/Qwen hoặc nhà cung cấp cloud cấu hình được; không mặc
--     định Claude API.
-- ml_dataset, ml_model, metric_definition, ai_eval_case cho phép organization_id
-- NULL (dùng chung cả nền tảng); các bảng này do pipeline huấn luyện ghi, không mở
-- qua API của doanh nghiệp, nên khoá ngoại tới chúng là khoá ngoại một cột.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- ml_dataset — Sổ đăng ký bộ dữ liệu huấn luyện
--
-- Vai trò: mỗi lần xuất dữ liệu từ kho (schema dwh) ra file Parquet trên S3 để
--   huấn luyện là một dòng: lấy bằng truy vấn nào, khoảng thời gian nào, bao nhiêu
--   dòng, file ở đâu. Nhờ vậy luôn tái tạo được mô hình đã học từ dữ liệu nào.
-- organization_id NULL: bộ dữ liệu gộp nhiều doanh nghiệp; chỉ được tạo khi hợp
--   đồng cho phép (MỞ).
-- contains_pii luôn FALSE: database từ chối đăng ký bộ dữ liệu có dữ liệu cá nhân.
-- -----------------------------------------------------------------------------
CREATE TABLE ml_dataset (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT        REFERENCES organization (id),
    name             VARCHAR(200)  NOT NULL,
    purpose          VARCHAR(30)   NOT NULL,
    source_query     TEXT          NOT NULL,
    period_start     DATE          NOT NULL,
    period_end       DATE          NOT NULL,
    row_count        BIGINT        NOT NULL,
    column_schema    JSONB         NOT NULL,
    storage_uri      VARCHAR(500)  NOT NULL,
    file_format      VARCHAR(10)   NOT NULL DEFAULT 'PARQUET',
    checksum_sha256  CHAR(64),
    contains_pii     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    CONSTRAINT ck_ml_dataset_purpose CHECK (purpose IN (
        'DEMAND_FORECAST', 'REVENUE_FORECAST', 'STAFFING_FORECAST', 'ANOMALY_DETECTION',
        'COLUMN_MAPPING', 'AI_EVAL', 'OTHER')),
    CONSTRAINT ck_ml_dataset_period CHECK (period_end >= period_start),
    CONSTRAINT ck_ml_dataset_rows   CHECK (row_count >= 0),
    CONSTRAINT ck_ml_dataset_format CHECK (file_format IN ('PARQUET', 'CSV')),
    CONSTRAINT ck_ml_dataset_no_pii CHECK (NOT contains_pii),
    CONSTRAINT ck_ml_dataset_uri    CHECK (storage_uri ~ '^s3://')
);
CREATE INDEX ix_ml_dataset_org ON ml_dataset (organization_id, purpose, created_at DESC);
COMMENT ON TABLE ml_dataset IS 'Sổ đăng ký bộ dữ liệu huấn luyện xuất từ kho dữ liệu; không chứa dữ liệu cá nhân.';
COMMENT ON COLUMN ml_dataset.source_query IS 'Truy vấn hoặc tên model dbt đã tạo ra bộ dữ liệu.';
COMMENT ON COLUMN ml_dataset.column_schema IS 'Danh sách cột và kiểu dữ liệu (JSON).';
COMMENT ON COLUMN ml_dataset.storage_uri IS 'Vị trí file, vd s3://opspilot-datasets/demand/2026-11-14/.';


-- -----------------------------------------------------------------------------
-- ml_model — Phiên bản mô hình
--
-- Vai trò: mỗi lần huấn luyện là một phiên bản: thuật toán, tham số, chỉ số đánh giá,
--   file mô hình trên S3, học từ bộ dữ liệu nào. So sánh phiên bản, quay lại bản cũ
--   khi bản mới kém. Mỗi loại mô hình có tối đa một bản ACTIVE cho mỗi phạm vi.
-- -----------------------------------------------------------------------------
CREATE TABLE ml_model (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT        REFERENCES organization (id),
    name             VARCHAR(100)  NOT NULL,
    model_type       VARCHAR(30)   NOT NULL,
    version_label    VARCHAR(50)   NOT NULL,
    algorithm        VARCHAR(100)  NOT NULL,
    dataset_id       BIGINT        REFERENCES ml_dataset (id),
    params           JSONB         NOT NULL DEFAULT '{}'::jsonb,
    metrics          JSONB         NOT NULL DEFAULT '{}'::jsonb,
    artifact_uri     VARCHAR(500),
    status           VARCHAR(20)   NOT NULL DEFAULT 'TRAINING',
    trained_at       TIMESTAMPTZ,
    activated_at     TIMESTAMPTZ,
    retired_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    CONSTRAINT uq_ml_model_version UNIQUE NULLS NOT DISTINCT (organization_id, name, version_label),
    CONSTRAINT ck_ml_model_type   CHECK (model_type IN (
        'DEMAND_FORECAST', 'REVENUE_FORECAST', 'STAFFING_FORECAST', 'ANOMALY_DETECTION', 'COLUMN_MAPPING')),
    CONSTRAINT ck_ml_model_status CHECK (status IN ('TRAINING', 'CANDIDATE', 'ACTIVE', 'RETIRED', 'FAILED')),
    CONSTRAINT ck_ml_model_active CHECK (
        status NOT IN ('CANDIDATE', 'ACTIVE', 'RETIRED') OR (trained_at IS NOT NULL AND artifact_uri IS NOT NULL)),
    CONSTRAINT ck_ml_model_activated CHECK (status <> 'ACTIVE' OR activated_at IS NOT NULL)
);
CREATE UNIQUE INDEX uq_ml_model_one_active ON ml_model (COALESCE(organization_id, 0), model_type)
    WHERE status = 'ACTIVE';
COMMENT ON TABLE ml_model IS 'Phiên bản mô hình đã huấn luyện; tối đa một bản ACTIVE cho mỗi loại × phạm vi.';
COMMENT ON COLUMN ml_model.metrics IS 'Chỉ số đánh giá, vd {"mape": 0.18, "wape": 0.12}.';
COMMENT ON COLUMN ml_model.artifact_uri IS 'File mô hình trên S3.';


-- -----------------------------------------------------------------------------
-- ai_conversation — Cuộc trò chuyện với trợ lý AI
--
-- Vai trò: nhóm các tin nhắn của một phiên hỏi đáp (AI-01→06, AI-17). Ghi nhà cung
--   cấp và mô hình đã dùng để so sánh chất lượng giữa các mô hình.
-- -----------------------------------------------------------------------------
CREATE TABLE ai_conversation (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT        NOT NULL REFERENCES organization (id),
    user_id          BIGINT        NOT NULL,
    title            VARCHAR(255),
    channel          VARCHAR(10)   NOT NULL DEFAULT 'WEB',
    model_provider   VARCHAR(30)   NOT NULL,
    model_name       VARCHAR(100)  NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    last_message_at  TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          INT           NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT uq_ai_conversation_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_ai_conversation_user FOREIGN KEY (organization_id, user_id)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT ck_ai_conversation_channel CHECK (channel IN ('WEB', 'MOBILE')),
    CONSTRAINT ck_ai_conversation_status  CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);
CREATE INDEX ix_ai_conversation_user ON ai_conversation (organization_id, user_id, last_message_at DESC);
COMMENT ON TABLE ai_conversation IS 'Một phiên hỏi đáp với trợ lý AI.';
COMMENT ON COLUMN ai_conversation.model_provider IS 'Nhà cung cấp mô hình, vd OLLAMA, DEEPSEEK.';


-- -----------------------------------------------------------------------------
-- ai_message — Tin nhắn trong cuộc trò chuyện
--
-- Vai trò: từng lượt hỏi và trả lời, kèm số token, thời gian phản hồi và đánh giá
--   tốt/không tốt của người dùng. Câu hỏi thật là nguồn cho bộ kiểm thử AI
--   (ai_eval_case) sau khi đã lọc dữ liệu cá nhân.
-- -----------------------------------------------------------------------------
CREATE TABLE ai_message (
    id                BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id   BIGINT        NOT NULL REFERENCES organization (id),
    conversation_id   BIGINT        NOT NULL,
    seq               INT           NOT NULL,
    role              VARCHAR(10)   NOT NULL,
    content           TEXT          NOT NULL,
    tokens_in         INT,
    tokens_out        INT,
    latency_ms        INT,
    feedback          VARCHAR(10),
    feedback_note     VARCHAR(1000),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_message_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_ai_message_conversation_id UNIQUE (organization_id, conversation_id, id),
    CONSTRAINT uq_ai_message_seq UNIQUE (conversation_id, seq),
    CONSTRAINT fk_ai_message_conversation FOREIGN KEY (organization_id, conversation_id)
        REFERENCES ai_conversation (organization_id, id),
    CONSTRAINT ck_ai_message_role     CHECK (role IN ('USER', 'ASSISTANT', 'TOOL', 'SYSTEM')),
    CONSTRAINT ck_ai_message_feedback CHECK (feedback IS NULL OR (feedback IN ('UP', 'DOWN') AND role = 'ASSISTANT')),
    CONSTRAINT ck_ai_message_numbers  CHECK (
        (tokens_in IS NULL OR tokens_in >= 0) AND (tokens_out IS NULL OR tokens_out >= 0)
        AND (latency_ms IS NULL OR latency_ms >= 0))
);
CREATE INDEX ix_ai_message_created ON ai_message USING brin (created_at);
COMMENT ON TABLE ai_message IS 'Tin nhắn của cuộc trò chuyện AI, kèm token, độ trễ và đánh giá của người dùng.';
COMMENT ON COLUMN ai_message.seq IS 'Thứ tự trong cuộc trò chuyện, bắt đầu từ 1.';
COMMENT ON COLUMN ai_message.feedback IS 'UP/DOWN do người dùng đánh giá câu trả lời.';


-- -----------------------------------------------------------------------------
-- ai_tool_call — AI đã gọi công cụ nào
--
-- Vai trò: nhật ký mọi lần trợ lý gọi một công cụ nghiệp vụ (AI-18, AI-22, AI-23):
--   tên công cụ, tham số, quyền đã kiểm tra, kết quả. Công cụ là API có phân quyền,
--   AI không chạy SQL trực tiếp. Bị từ chối quyền cũng ghi (status = DENIED).
--   Khoá ngoại 3 cột bảo đảm tin nhắn thuộc đúng cuộc trò chuyện. Chỉ ghi thêm.
-- -----------------------------------------------------------------------------
CREATE TABLE ai_tool_call (
    id                BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id   BIGINT        NOT NULL REFERENCES organization (id),
    conversation_id   BIGINT        NOT NULL,
    message_id        BIGINT        NOT NULL,
    tool_name         VARCHAR(100)  NOT NULL,
    arguments         JSONB         NOT NULL DEFAULT '{}'::jsonb,
    permission_code   VARCHAR(100),
    status            VARCHAR(10)   NOT NULL,
    result_summary    JSONB,
    rows_returned     INT,
    duration_ms       INT,
    error_message     VARCHAR(1000),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_tool_call_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_ai_tool_call_conversation_id UNIQUE (organization_id, conversation_id, id),
    CONSTRAINT fk_ai_tool_call_conversation FOREIGN KEY (organization_id, conversation_id)
        REFERENCES ai_conversation (organization_id, id),
    CONSTRAINT fk_ai_tool_call_message FOREIGN KEY (organization_id, conversation_id, message_id)
        REFERENCES ai_message (organization_id, conversation_id, id),
    CONSTRAINT ck_ai_tool_call_status CHECK (status IN ('SUCCESS', 'DENIED', 'ERROR')),
    CONSTRAINT ck_ai_tool_call_numbers CHECK (
        (rows_returned IS NULL OR rows_returned >= 0) AND (duration_ms IS NULL OR duration_ms >= 0))
);
CREATE INDEX ix_ai_tool_call_conversation ON ai_tool_call (organization_id, conversation_id, created_at);
CREATE INDEX ix_ai_tool_call_tool ON ai_tool_call (organization_id, tool_name, created_at DESC);

CREATE TRIGGER trg_ai_tool_call_append_only
    BEFORE UPDATE OR DELETE ON ai_tool_call
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();

COMMENT ON TABLE ai_tool_call IS 'Nhật ký công cụ AI đã gọi, kể cả bị từ chối quyền. Chỉ ghi thêm.';
COMMENT ON COLUMN ai_tool_call.permission_code IS 'Quyền đã kiểm tra trước khi chạy công cụ, vd sales.order.view.';


-- -----------------------------------------------------------------------------
-- ai_action_request — Hành động AI đề xuất, chờ người duyệt
--
-- Vai trò: AI chuẩn bị đề xuất (tạo việc, điều chỉnh kho, đặt hàng bổ sung, gửi
--   báo cáo...) nhưng không tự thực hiện (AI-20, AI-21). Người có quyền duyệt
--   rồi service mới chạy. Database chặn trạng thái EXECUTED khi chưa có quyết định.
-- Lần gọi công cụ (tool_call_id) phải thuộc đúng cuộc trò chuyện (khoá ngoại 3 cột).
-- Cho mô hình học: tỷ lệ đề xuất được chấp nhận theo loại hành động.
-- -----------------------------------------------------------------------------
CREATE TABLE ai_action_request (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id       BIGINT        NOT NULL REFERENCES organization (id),
    conversation_id       BIGINT,
    tool_call_id          BIGINT,
    action_type           VARCHAR(50)   NOT NULL,
    payload               JSONB         NOT NULL,
    explanation           TEXT          NOT NULL,
    requested_for_user_id BIGINT        NOT NULL,
    status                VARCHAR(20)   NOT NULL DEFAULT 'PROPOSED',
    approval_request_id   BIGINT,
    decided_by            BIGINT,
    decided_at            TIMESTAMPTZ,
    executed_at           TIMESTAMPTZ,
    result                JSONB,
    error_message         VARCHAR(1000),
    expires_at            TIMESTAMPTZ   NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version               INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_ai_action_request_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_ai_action_request_conversation FOREIGN KEY (organization_id, conversation_id)
        REFERENCES ai_conversation (organization_id, id),
    CONSTRAINT fk_ai_action_request_tool_call FOREIGN KEY (organization_id, conversation_id, tool_call_id)
        REFERENCES ai_tool_call (organization_id, conversation_id, id),
    CONSTRAINT ck_ai_action_request_tool_call CHECK (tool_call_id IS NULL OR conversation_id IS NOT NULL),
    CONSTRAINT fk_ai_action_request_user FOREIGN KEY (organization_id, requested_for_user_id)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT fk_ai_action_request_decider FOREIGN KEY (organization_id, decided_by)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT fk_ai_action_request_approval FOREIGN KEY (organization_id, approval_request_id)
        REFERENCES approval_request (organization_id, id),
    CONSTRAINT ck_ai_action_request_type CHECK (action_type ~ '^[A-Z][A-Z_]{2,49}$'),
    CONSTRAINT ck_ai_action_request_status CHECK (status IN (
        'PROPOSED', 'APPROVED', 'REJECTED', 'EXECUTED', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_ai_action_request_decided CHECK (
        (status IN ('APPROVED', 'REJECTED', 'EXECUTED', 'FAILED')) = (decided_at IS NOT NULL AND decided_by IS NOT NULL)),
    CONSTRAINT ck_ai_action_request_executed CHECK ((status = 'EXECUTED') = (executed_at IS NOT NULL)),
    CONSTRAINT ck_ai_action_request_payload CHECK (jsonb_typeof(payload) = 'object')
);
CREATE INDEX ix_ai_action_request_pending ON ai_action_request (organization_id, requested_for_user_id)
    WHERE status = 'PROPOSED';
COMMENT ON TABLE ai_action_request IS 'Đề xuất hành động của AI; chỉ thực hiện sau khi người có quyền duyệt.';
COMMENT ON COLUMN ai_action_request.action_type IS 'Loại hành động, vd CREATE_TASK, ADJUST_STOCK, REORDER, SEND_REPORT.';
COMMENT ON COLUMN ai_action_request.payload IS 'Dữ liệu cụ thể của hành động, để người duyệt xem trước.';
COMMENT ON COLUMN ai_action_request.explanation IS 'Lý do và căn cứ AI đưa ra đề xuất.';


-- -----------------------------------------------------------------------------
-- forecast_run — Một lần chạy dự báo
--
-- Vai trò: dự báo nhu cầu sản phẩm, dịch vụ, doanh thu, nhân sự (AI-07→11): mô hình
--   nào, dữ liệu tới ngày nào, dự báo bao nhiêu ngày tới. So dự báo với thực tế để
--   đo độ chính xác.
-- -----------------------------------------------------------------------------
CREATE TABLE forecast_run (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT        NOT NULL REFERENCES organization (id),
    forecast_type    VARCHAR(30)   NOT NULL,
    ml_model_id      BIGINT        REFERENCES ml_model (id),
    data_until       DATE          NOT NULL,
    horizon_days     SMALLINT      NOT NULL,
    granularity      VARCHAR(10)   NOT NULL DEFAULT 'DAY',
    status           VARCHAR(20)   NOT NULL DEFAULT 'RUNNING',
    parameters       JSONB         NOT NULL DEFAULT '{}'::jsonb,
    metrics          JSONB,
    started_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    error_message    VARCHAR(1000),
    CONSTRAINT uq_forecast_run_org_id UNIQUE (organization_id, id),
    CONSTRAINT ck_forecast_run_type CHECK (forecast_type IN (
        'PRODUCT_DEMAND', 'SERVICE_DEMAND', 'REVENUE', 'STAFFING')),
    CONSTRAINT ck_forecast_run_horizon CHECK (horizon_days BETWEEN 1 AND 366),
    CONSTRAINT ck_forecast_run_granularity CHECK (granularity IN ('DAY', 'WEEK', 'MONTH')),
    CONSTRAINT ck_forecast_run_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_forecast_run_finished CHECK ((status = 'RUNNING') = (finished_at IS NULL))
);
CREATE INDEX ix_forecast_run_latest ON forecast_run (organization_id, forecast_type, data_until DESC)
    WHERE status = 'COMPLETED';
COMMENT ON TABLE forecast_run IS 'Một lần chạy dự báo: loại, mô hình, mốc dữ liệu, số ngày dự báo.';
COMMENT ON COLUMN forecast_run.data_until IS 'Dữ liệu dùng để dự báo tính tới hết ngày này.';


-- -----------------------------------------------------------------------------
-- forecast_value — Từng giá trị dự báo
--
-- Vai trò: đơn vị × sản phẩm (hoặc dịch vụ) × ngày → giá trị dự báo và khoảng tin
--   cậy. actual_value được điền sau khi có số thật để đo độ chính xác.
-- Số dòng rất lớn (đơn vị × sản phẩm × ngày × mỗi lần chạy), nên chia partition
--   theo tháng trên created_at; chỉ giữ các lần chạy gần đây (đề xuất 6 tháng)
--   bằng cách DROP partition cũ. Khoá chính (id, created_at) theo yêu cầu partition;
--   id GENERATED ALWAYS chỉ chặn việc vô tình tự chèn id: role có quyền INSERT vẫn
--   ghi đè được bằng OVERRIDING SYSTEM VALUE, và DB không có unique riêng cho id.
--   Vì vậy định danh một dòng luôn là cặp (id, created_at).
-- Truy cập: KHÔNG tạo JPA entity; pipeline dự báo ghi hàng loạt bằng JdbcClient/COPY,
--   đọc theo forecast_run_id.
-- -----------------------------------------------------------------------------
CREATE TABLE forecast_value (
    id               BIGINT GENERATED ALWAYS AS IDENTITY,
    organization_id  BIGINT          NOT NULL REFERENCES organization (id),
    forecast_run_id  BIGINT          NOT NULL,
    org_unit_id      BIGINT          NOT NULL,
    product_id       BIGINT,
    target_date      DATE            NOT NULL,
    predicted_value  NUMERIC(18, 4)  NOT NULL,
    lower_bound      NUMERIC(18, 4),
    upper_bound      NUMERIC(18, 4),
    actual_value     NUMERIC(18, 4),
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT pk_forecast_value PRIMARY KEY (id, created_at),
    CONSTRAINT fk_forecast_value_run FOREIGN KEY (organization_id, forecast_run_id)
        REFERENCES forecast_run (organization_id, id),
    CONSTRAINT fk_forecast_value_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_forecast_value_product FOREIGN KEY (organization_id, product_id)
        REFERENCES product (organization_id, id),
    CONSTRAINT ck_forecast_value_bounds CHECK (
        (lower_bound IS NULL OR lower_bound <= predicted_value)
        AND (upper_bound IS NULL OR upper_bound >= predicted_value))
) PARTITION BY RANGE (created_at);

CREATE TABLE forecast_value_default PARTITION OF forecast_value DEFAULT;
SELECT ensure_monthly_partitions('forecast_value', 3);

CREATE INDEX ix_forecast_value_run ON forecast_value (forecast_run_id, org_unit_id, product_id, target_date);
CREATE INDEX ix_forecast_value_lookup ON forecast_value (organization_id, org_unit_id, product_id, target_date);
COMMENT ON TABLE forecast_value IS 'Giá trị dự báo theo đơn vị × sản phẩm × ngày. Chia partition theo tháng.';
COMMENT ON COLUMN forecast_value.product_id IS 'NULL với dự báo không theo sản phẩm (doanh thu, nhân sự).';
COMMENT ON COLUMN forecast_value.actual_value IS 'Số thực tế điền sau, để đo sai số dự báo.';


-- -----------------------------------------------------------------------------
-- anomaly_alert — Cảnh báo bất thường
--
-- Vai trò: doanh thu giảm bất thường, chi phí tăng vọt, hao hụt kho, việc quá hạn
--   dồn dập (AI-12→16): giá trị quan sát, giá trị kỳ vọng, mức lệch, căn cứ.
-- feedback_label do người dùng xác nhận đúng/sai là NHÃN HUẤN LUYỆN quý nhất: mô
--   hình học lại từ những cảnh báo bị đánh dấu sai.
-- Xử lý cảnh báo bằng một công việc: task_id.
-- -----------------------------------------------------------------------------
CREATE TABLE anomaly_alert (
    id                BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id   BIGINT          NOT NULL REFERENCES organization (id),
    alert_type        VARCHAR(30)     NOT NULL,
    org_unit_id       BIGINT,
    entity_type       VARCHAR(50),
    entity_id         BIGINT,
    metric_code       VARCHAR(100),
    period_start      TIMESTAMPTZ     NOT NULL,
    period_end        TIMESTAMPTZ     NOT NULL,
    observed_value    NUMERIC(18, 4),
    expected_value    NUMERIC(18, 4),
    deviation_score   NUMERIC(10, 4),
    severity          VARCHAR(10)     NOT NULL,
    evidence          JSONB           NOT NULL DEFAULT '{}'::jsonb,
    ml_model_id       BIGINT          REFERENCES ml_model (id),
    status            VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    feedback_label    VARCHAR(20),
    feedback_by       BIGINT,
    feedback_at       TIMESTAMPTZ,
    feedback_note     VARCHAR(1000),
    task_id           BIGINT,
    detected_at       TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version           INT             NOT NULL DEFAULT 0,
    CONSTRAINT uq_anomaly_alert_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_anomaly_alert_unit FOREIGN KEY (organization_id, org_unit_id)
        REFERENCES org_unit (organization_id, id),
    CONSTRAINT fk_anomaly_alert_feedback_by FOREIGN KEY (organization_id, feedback_by)
        REFERENCES app_user (organization_id, id),
    CONSTRAINT fk_anomaly_alert_task FOREIGN KEY (organization_id, task_id)
        REFERENCES task (organization_id, id),
    CONSTRAINT ck_anomaly_alert_type CHECK (alert_type IN (
        'REVENUE_DROP', 'REVENUE_SPIKE', 'EXPENSE_SPIKE', 'INVENTORY_SHRINKAGE',
        'INVENTORY_ADJUSTMENT_SPIKE', 'TASK_OVERDUE_SPIKE', 'CANCELLATION_SPIKE', 'OTHER')),
    CONSTRAINT ck_anomaly_alert_period   CHECK (period_end > period_start),
    CONSTRAINT ck_anomaly_alert_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_anomaly_alert_status   CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'DISMISSED')),
    CONSTRAINT ck_anomaly_alert_feedback CHECK (
        (feedback_label IS NULL OR feedback_label IN ('TRUE_POSITIVE', 'FALSE_POSITIVE'))
        AND ((feedback_label IS NULL) = (feedback_at IS NULL))
        AND (feedback_at IS NULL OR feedback_by IS NOT NULL)),
    CONSTRAINT ck_anomaly_alert_entity CHECK ((entity_type IS NULL) = (entity_id IS NULL))
);
CREATE INDEX ix_anomaly_alert_open ON anomaly_alert (organization_id, org_unit_id, detected_at DESC)
    WHERE status IN ('OPEN', 'ACKNOWLEDGED');
CREATE INDEX ix_anomaly_alert_labelled ON anomaly_alert (organization_id, alert_type)
    WHERE feedback_label IS NOT NULL;
COMMENT ON TABLE anomaly_alert IS 'Cảnh báo bất thường kèm căn cứ; feedback_label là nhãn huấn luyện.';
COMMENT ON COLUMN anomaly_alert.evidence IS 'Căn cứ của cảnh báo (AI-16): số liệu so sánh, khoảng thời gian, truy vấn.';
COMMENT ON COLUMN anomaly_alert.feedback_label IS 'TRUE_POSITIVE hoặc FALSE_POSITIVE do người dùng xác nhận.';


-- -----------------------------------------------------------------------------
-- ai_eval_case — Bộ câu hỏi kiểm thử AI
--
-- Vai trò: câu hỏi mẫu kèm đáp án mong đợi và công cụ mong đợi (AI-24). Chạy lại
--   toàn bộ bộ này trước khi đổi mô hình hoặc đổi prompt; điểm thấp hơn bản đang
--   dùng thì không đổi. organization_id NULL: câu hỏi dùng chung.
-- -----------------------------------------------------------------------------
CREATE TABLE ai_eval_case (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id    BIGINT        REFERENCES organization (id),
    question           TEXT          NOT NULL,
    expected_answer    TEXT,
    expected_tools     JSONB         NOT NULL DEFAULT '[]'::jsonb,
    tags               TEXT[]        NOT NULL DEFAULT '{}',
    source             VARCHAR(20)   NOT NULL DEFAULT 'MANUAL',
    source_message_id  BIGINT,
    is_active          BOOLEAN       NOT NULL DEFAULT TRUE,
    last_score         NUMERIC(5, 4),
    last_run_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by         BIGINT,
    version            INT           NOT NULL DEFAULT 0,
    CONSTRAINT fk_ai_eval_case_message FOREIGN KEY (organization_id, source_message_id)
        REFERENCES ai_message (organization_id, id),
    CONSTRAINT ck_ai_eval_case_source CHECK (source IN ('MANUAL', 'FROM_CONVERSATION')),
    CONSTRAINT ck_ai_eval_case_tools  CHECK (jsonb_typeof(expected_tools) = 'array'),
    CONSTRAINT ck_ai_eval_case_score  CHECK (last_score IS NULL OR last_score BETWEEN 0 AND 1),
    CONSTRAINT ck_ai_eval_case_expectation CHECK (
        expected_answer IS NOT NULL OR jsonb_array_length(expected_tools) > 0)
);
COMMENT ON TABLE ai_eval_case IS 'Câu hỏi kiểm thử AI kèm đáp án/công cụ mong đợi; chạy trước khi đổi mô hình.';
COMMENT ON COLUMN ai_eval_case.expected_tools IS 'Mảng tên công cụ AI phải gọi, vd ["sales.revenue_by_day"].';

-- ############################################################################
-- ## V14__tenant_rls.sql
-- ############################################################################

-- =============================================================================
-- V14__tenant_rls.sql — Cách ly doanh nghiệp (Row-Level Security) và quyền theo role
-- Phụ thuộc: V1–V13; các role do db/roles_setup.sql tạo trước khi chạy migration:
--   opspilot_owner  chủ sở hữu bảng, chỉ Flyway dùng
--   opspilot_app    Spring Boot (API). Không superuser, không BYPASSRLS, không owner
--   opspilot_job    job hệ thống chạy cho mọi doanh nghiệp, import lịch sử. BYPASSRLS
--   opspilot_etl    ETL: BYPASSRLS + SELECT theo whitelist cột; không có quyền ghi
--
-- Nguyên tắc: quyền bỏ qua RLS gắn với ROLE ĐĂNG NHẬP (thuộc tính BYPASSRLS hoặc là
-- chủ sở hữu bảng), không gắn với biến phiên. Phiên SQL tự đặt được mọi biến dạng
-- opspilot.*, nên không biến phiên nào được dùng làm điều kiện cấp quyền.
--
-- Tenant context: ứng dụng đặt ở đầu mỗi giao dịch
--   SELECT set_config('opspilot.org_id', '<id doanh nghiệp từ JWT>', true);
-- Chưa đặt: không thấy/ghi được dòng của tenant; bảng shared (org_id NULL)
-- vẫn có thể đọc dữ liệu dùng chung, tùy quyền SELECT. Đây là ngoại lệ có chủ đích.
--
-- Phạm vi bảo vệ (đọc kỹ): RLS chặn được lỗi QUÊN điều kiện organization_id trong
-- code, kể cả JdbcClient. RLS KHÔNG phải hàng rào độc lập trước SQL injection: một
-- phiên đã bị chèn SQL vẫn tự đặt được opspilot.org_id sang doanh nghiệp khác.
-- Chống SQL injection bằng truy vấn tham số hoá (JPA, JdbcClient với tham số),
-- không ghép chuỗi SQL từ dữ liệu người dùng, và quyền tối thiểu của opspilot_app.
--
-- Không FORCE ROW LEVEL SECURITY: chủ sở hữu bảng (Flyway, hàm SECURITY DEFINER)
-- bỏ qua RLS theo cơ chế chuẩn của PostgreSQL, nên migration sửa dữ liệu, kiểm tra
-- khoá ngoại khi ATTACH partition và hàm bảo trì chạy được mà không cần biến bypass.
-- Đổi lại, ỨNG DỤNG TUYỆT ĐỐI KHÔNG KẾT NỐI BẰNG OWNER. Ứng dụng gọi
-- rls_session_check() lúc khởi động và từ chối chạy nếu có dòng ok = false.
--
-- Bảng mới ở migration sau: mặc định không cấp bảng cho app/job/etl; migration
-- mới phải tự bật RLS, tạo policy và cấp GRANT rõ ràng. Không auto-grant ALL
-- TABLES trong default privileges. View rls_coverage phải rỗng (test CI).
-- =============================================================================

DO $$
DECLARE
    v_missing TEXT;
BEGIN
    IF current_user <> 'opspilot_owner' THEN
        RAISE EXCEPTION 'Schema migration phải chạy bằng opspilot_owner, hiện tại: %', current_user;
    END IF;
    SELECT string_agg(r, ', ') INTO v_missing
      FROM unnest(ARRAY['opspilot_owner', 'opspilot_app', 'opspilot_job', 'opspilot_etl']) AS r
     WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r);
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'Thiếu role: %. Chạy db/roles_setup.sql bằng superuser trước migration.', v_missing;
    END IF;
    IF (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = 'opspilot_app')
       OR pg_has_role('opspilot_app', 'opspilot_owner', 'MEMBER')
       OR pg_has_role('opspilot_app', 'opspilot_job', 'MEMBER')
       OR pg_has_role('opspilot_app', 'opspilot_etl', 'MEMBER') THEN
        RAISE EXCEPTION 'Role opspilot_app không an toàn; kiểm tra BYPASSRLS/superuser/membership';
    END IF;
END;
$$;


CREATE OR REPLACE FUNCTION app_current_org() RETURNS BIGINT
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT nullif(current_setting('opspilot.org_id', true), '')::BIGINT
$$;
COMMENT ON FUNCTION app_current_org() IS 'Doanh nghiệp của giao dịch hiện tại (opspilot.org_id); NULL nếu chưa đặt. Là ngữ cảnh do ứng dụng truyền, không phải bằng chứng xác thực.';


-- Đăng nhập: tìm doanh nghiệp theo mã khi chưa có org_id. Chạy với quyền owner (bỏ
-- qua RLS), chỉ trả về id của doanh nghiệp đang hoạt động.
CREATE OR REPLACE FUNCTION find_organization_id(p_code TEXT) RETURNS BIGINT
LANGUAGE sql STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT id FROM organization WHERE lower(code) = lower(p_code) AND status = 'ACTIVE'
$$;
COMMENT ON FUNCTION find_organization_id(TEXT) IS 'Mã doanh nghiệp → id, cho bước đăng nhập. Chỉ opspilot_app được gọi.';


-- Kiểm tra phiên hiện tại có đủ điều kiện để RLS có hiệu lực. Ứng dụng gọi lúc khởi
-- động; có dòng ok = false thì không chạy.
CREATE OR REPLACE FUNCTION rls_session_check()
RETURNS TABLE (check_name TEXT, ok BOOLEAN, detail TEXT)
LANGUAGE sql STABLE AS $$
    SELECT 'dang_nhap_dung_opspilot_app'::TEXT,
           session_user = 'opspilot_app' AND current_user = 'opspilot_app',
           ('session=' || session_user || ', current=' || current_user)::TEXT
    UNION ALL
    SELECT 'khong_phai_superuser', NOT r.rolsuper, current_user::TEXT
      FROM pg_roles r WHERE r.rolname = current_user
    UNION ALL
    SELECT 'khong_co_bypassrls', NOT r.rolbypassrls, current_user::TEXT
      FROM pg_roles r WHERE r.rolname = current_user
    UNION ALL
    SELECT 'khong_duoc_ke_thua_role_dac_quyen',
           NOT (pg_has_role(current_user, 'opspilot_owner', 'MEMBER')
                OR pg_has_role(current_user, 'opspilot_job', 'MEMBER')
                OR pg_has_role(current_user, 'opspilot_etl', 'MEMBER')),
           current_user::TEXT
    UNION ALL
    SELECT 'khong_so_huu_bang_co_rls',
           NOT EXISTS (SELECT 1 FROM pg_class c
                        WHERE c.relnamespace = 'public'::regnamespace AND c.relrowsecurity
                          AND pg_has_role(current_user, c.relowner, 'MEMBER')),
           current_user::TEXT
$$;
COMMENT ON FUNCTION rls_session_check() IS 'Ứng dụng gọi lúc khởi động: mọi dòng phải ok = true thì RLS mới có hiệu lực với phiên này.';


-- Bật RLS cho bảng tenant. Bảng NOT NULL: một policy ALL theo org_id.
-- Bảng org_id nullable: tách policy theo command để dòng shared (NULL) chỉ được
-- ĐỌC. Nếu dùng một policy ALL với USING (org_id IS NULL OR org_id = current),
-- tenant có thể UPDATE một dòng shared thành dòng của chính mình.
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT c.oid::regclass AS tbl, a.attnotnull AS org_required
          FROM pg_class c
          JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped
         WHERE c.relnamespace = 'public'::regnamespace
           AND c.relkind IN ('r', 'p')
           AND NOT c.relispartition
         ORDER BY c.relname
    LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', r.tbl);
        IF r.org_required THEN
            EXECUTE format(
                'CREATE POLICY tenant_isolation ON %s '
                'USING (organization_id = app_current_org()) '
                'WITH CHECK (organization_id = app_current_org())', r.tbl);
        ELSE
            EXECUTE format(
                'CREATE POLICY tenant_read ON %s FOR SELECT '
                'USING (organization_id IS NULL OR organization_id = app_current_org())', r.tbl);
            EXECUTE format(
                'CREATE POLICY tenant_insert ON %s FOR INSERT '
                'WITH CHECK (organization_id = app_current_org())', r.tbl);
            EXECUTE format(
                'CREATE POLICY tenant_update ON %s FOR UPDATE '
                'USING (organization_id = app_current_org()) '
                'WITH CHECK (organization_id = app_current_org())', r.tbl);
            EXECUTE format(
                'CREATE POLICY tenant_delete ON %s FOR DELETE '
                'USING (organization_id = app_current_org())', r.tbl);
        END IF;
    END LOOP;

    -- Partition con: không cấp quyền trực tiếp cho runtime roles.
    -- Truy vấn qua bảng cha tuân theo chính sách tenant của bảng cha.
    FOR r IN
        SELECT c.oid::regclass AS tbl
          FROM pg_class c
         WHERE c.relnamespace = 'public'::regnamespace AND c.relispartition AND c.relkind = 'r'
    LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', r.tbl);
    END LOOP;
END;
$$;

ALTER TABLE organization ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON organization
    USING (id = app_current_org())
    WITH CHECK (id = app_current_org());


-- ---------------------------------------------------------------------------
-- Quyền truy cập dữ liệu: default deny rồi cấp có mục đích.
-- ĐÂY LÀ DATABASE RIÊNG CỦA OPSPILOT. Bảng/sequence từ extension không cần
-- được cấp quyền CRUD ở đây. Không cấp quyền cho PUBLIC.
-- ---------------------------------------------------------------------------
-- Flyway creates flyway_schema_history before V1 as the migration user. Because
-- this script executes under SET ROLE opspilot_owner, a schema-wide REVOKE would
-- try to alter that table and fail. Restrict the default-deny pass to relations
-- owned by the approved schema owner; this covers every OpsPilot business object
-- without granting opspilot_owner control of Flyway history.
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT c.oid::regclass AS relation
          FROM pg_class c
          JOIN pg_roles owner_role ON owner_role.oid = c.relowner
         WHERE c.relnamespace = 'public'::regnamespace
           AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
           AND owner_role.rolname = 'opspilot_owner'
    LOOP
        EXECUTE format(
            'REVOKE ALL PRIVILEGES ON TABLE %s FROM PUBLIC, opspilot_app, opspilot_job, opspilot_etl',
            r.relation
        );
    END LOOP;
END;
$$;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public
    FROM PUBLIC, opspilot_app, opspilot_job, opspilot_etl;

-- API: chỉ cấp DML cho các bảng có organization_id. Không cấp quyền cho
-- platform_user/subscription_plan (tenant-less), chức năng quản trị platform
-- phải có đường service/role riêng được duyệt trong migration sau.
-- JOB: cũng chỉ được cấp DML tenant; bypass RLS vốn là đặc quyền cao, tuyệt đối
-- không dùng opspilot_job làm datasource cho request REST thông thường.
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT c.oid::regclass AS tbl
          FROM pg_class c
          JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'organization_id'
                             AND NOT a.attisdropped
         WHERE c.relnamespace = 'public'::regnamespace
           AND c.relkind IN ('r', 'p') AND NOT c.relispartition
         ORDER BY c.relname
    LOOP
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON TABLE %s TO opspilot_app, opspilot_job', r.tbl);
    END LOOP;
END;
$$;

-- Organization: API chỉ đọc/sửa tenant hiện hành; tạo tenant do job onboarding.
GRANT SELECT, UPDATE ON organization TO opspilot_app;
GRANT SELECT, INSERT, UPDATE ON organization TO opspilot_job;

-- Catalog không chứa org_id: API được đọc, không có quyền thay đổi.
GRANT SELECT ON feature, permission, subscription_plan TO opspilot_app, opspilot_job;

-- Bảng hệ thống, data/ML và subscription do job/owner quản lý. API chỉ đọc.
REVOKE INSERT, UPDATE ON organization_subscription, metric_definition, ml_dataset,
                         ml_model, ai_eval_case, forecast_value FROM opspilot_app;

-- Các bảng append-only: chỉ INSERT/SELECT; không cho UPDATE hoặc DELETE.
REVOKE UPDATE ON audit_log, inventory_movement, order_status_history,
                 task_status_history, sales_return_item, ai_tool_call
    FROM opspilot_app, opspilot_job;

-- Không cấp DELETE mặc định cho app/job. Chỉ các thao tác thực sự cần xoá bản nháp
-- hoặc liên kết được phép xoá mới có DELETE (vẫn chịu RLS/trigger).
GRANT DELETE ON role_permission, sales_order_item, stock_count_item,
                stock_transfer_item, task_checklist_item TO opspilot_app;
-- Job dọn idempotency record hết hạn; không cần quyền DELETE của owner.
GRANT DELETE ON idempotency_record TO opspilot_job;

-- Identity/generated values. Không cấp quyền sequence cho ETL.
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO opspilot_app, opspilot_job;

-- ---------------------------------------------------------------------------
-- Quyền gọi hàm. Hạn chế SECURITY DEFINER và những hàm thao tác xuyên tenant.
-- ---------------------------------------------------------------------------
REVOKE EXECUTE ON FUNCTION ensure_monthly_partitions(REGCLASS, INT, INT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION default_partition_row_count(REGCLASS)        FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION drop_old_partitions(REGCLASS, INT)           FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION seed_system_roles(BIGINT)                    FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION find_organization_id(TEXT)                   FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION rls_session_check()                          FROM PUBLIC;

GRANT EXECUTE ON FUNCTION ensure_monthly_partitions(REGCLASS, INT, INT) TO opspilot_job;
GRANT EXECUTE ON FUNCTION default_partition_row_count(REGCLASS)        TO opspilot_job;
GRANT EXECUTE ON FUNCTION drop_old_partitions(REGCLASS, INT)           TO opspilot_job;
GRANT EXECUTE ON FUNCTION seed_system_roles(BIGINT)                    TO opspilot_job;
GRANT EXECUTE ON FUNCTION find_organization_id(TEXT)                   TO opspilot_app;
GRANT EXECUTE ON FUNCTION rls_session_check()                          TO opspilot_app;
GRANT EXECUTE ON FUNCTION app_current_org()                             TO opspilot_app;
GRANT EXECUTE ON FUNCTION app_is_job_role()                             TO opspilot_app, opspilot_job;

-- ---------------------------------------------------------------------------
-- ETL: DENY BY DEFAULT, cấp SELECT CỘT phù hợp báo cáo/dự báo; không blanket
-- SELECT ALL TABLES. Thêm cột trong tương lai phải review dữ liệu cá nhân.
-- ID nghiệp vụ vẫn là định danh giả, không mặc định là dữ liệu đã ẩn danh tuyệt đối.
-- ---------------------------------------------------------------------------
GRANT SELECT (id, code, timezone, currency, locale, status, created_at)
    ON organization TO opspilot_etl;
GRANT SELECT (id, organization_id, parent_id, unit_type, code, depth, status)
    ON org_unit TO opspilot_etl;
GRANT SELECT (id, organization_id, parent_id, code)
    ON product_category TO opspilot_etl;
GRANT SELECT (id, organization_id, category_id, code, product_type, unit_id,
              track_inventory, status, created_at, updated_at)
    ON product TO opspilot_etl;
GRANT SELECT (id, organization_id, product_id, price, valid_from, valid_to)
    ON product_price TO opspilot_etl;
GRANT SELECT (id, organization_id, employment_status, hire_date, termination_date)
    ON employee TO opspilot_etl;
GRANT SELECT (id, organization_id, org_unit_id, warehouse_unit_id,
              channel, order_status, fulfillment_status, currency,
              subtotal_amount, discount_amount, tax_amount, total_amount,
              returned_amount, paid_amount, refunded_amount, payment_status,
              order_at, confirmed_at, fulfilled_at, completed_at, cancelled_at,
              source, created_at, updated_at)
    ON sales_order TO opspilot_etl;
GRANT SELECT (id, organization_id, sales_order_id, product_id, quantity,
              unit_price, line_amount, line_discount_amount, order_discount_allocated,
              net_amount, tax_rate, tax_amount, fulfilled_qty, returned_qty)
    ON sales_order_item TO opspilot_etl;
GRANT SELECT (id, organization_id, sales_order_id, org_unit_id,
              returned_at, total_refund_amount, created_at)
    ON sales_return TO opspilot_etl;
GRANT SELECT (id, organization_id, sales_return_id, sales_order_id,
              sales_order_item_id, quantity, refund_amount, tax_refund_amount, restock)
    ON sales_return_item TO opspilot_etl;
GRANT SELECT (id, organization_id, sales_order_id, sales_return_id,
              kind, payment_method_id, amount, status, paid_at, created_at)
    ON payment TO opspilot_etl;
GRANT SELECT (id, organization_id, warehouse_unit_id, product_id,
              on_hand_qty, reserved_qty, available_qty, avg_unit_cost)
    ON stock_balance TO opspilot_etl;
GRANT SELECT (id, organization_id, warehouse_unit_id, product_id,
              movement_type, quantity, unit_cost, occurred_at, source, created_at)
    ON inventory_movement TO opspilot_etl;

-- Các bảng khác, đặc biệt app_user, refresh_token, customer, platform_user,
-- integration_connection, ai_message, audit_log, task_comment, stored_file:
-- KHÔNG được cấp SELECT cho ETL; mở rộng sau khi rà soát privacy.


-- Bảng có dữ liệu doanh nghiệp (hoặc là organization) mà chưa bật RLS hoặc chưa có
-- chính sách. Test CI: SELECT count(*) FROM rls_coverage phải bằng 0.
CREATE OR REPLACE VIEW rls_coverage AS
SELECT c.relname AS table_name,
       c.relrowsecurity AS rls_enabled,
       EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid) AS has_policy
  FROM pg_class c
 WHERE c.relnamespace = 'public'::regnamespace
   AND c.relkind IN ('r', 'p')
   AND NOT c.relispartition
   AND (c.relname = 'organization'
        OR EXISTS (SELECT 1 FROM pg_attribute a
                    WHERE a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped))
   AND NOT (c.relrowsecurity AND EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid));
COMMENT ON VIEW rls_coverage IS 'Bảng có dữ liệu doanh nghiệp nhưng chưa bật RLS hoặc chưa có chính sách; phải rỗng.';

-- Default-deny EXECUTE for functions created by opspilot_owner AFTER V14.
-- This does NOT retroactively change ltree/btree_gist functions installed above;
-- each new application function requiring access needs an explicit GRANT.
ALTER DEFAULT PRIVILEGES FOR ROLE opspilot_owner
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
-- ---------------------------------------------------------------------------
-- Postflight privilege assertions (errors abort this statement/script).
-- These checks verify ACL shape, not the correctness of tenant transaction logic.
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM rls_coverage) THEN
        RAISE EXCEPTION 'rls_coverage chưa rỗng: bảng tenant thiếu RLS/policy';
    END IF;
    IF has_table_privilege('opspilot_app', 'public.platform_user', 'SELECT')
       OR has_table_privilege('opspilot_app', 'public.audit_log', 'DELETE')
       OR has_function_privilege('opspilot_app',
            'public.ensure_monthly_partitions(regclass,integer,integer)', 'EXECUTE') THEN
        RAISE EXCEPTION 'App được cấp quyền vượt phạm vi cho phép';
    END IF;
    IF has_table_privilege('opspilot_etl', 'public.app_user', 'SELECT')
       OR has_column_privilege('opspilot_etl', 'public.customer', 'phone', 'SELECT')
       OR has_table_privilege('opspilot_etl', 'public.sales_order', 'UPDATE') THEN
        RAISE EXCEPTION 'ETL được cấp quyền ngoài whitelist';
    END IF;
    IF NOT has_table_privilege('opspilot_app', 'public.sales_order', 'SELECT')
       OR NOT has_function_privilege('opspilot_app', 'public.find_organization_id(text)', 'EXECUTE')
       OR NOT has_function_privilege('opspilot_job',
              'public.ensure_monthly_partitions(regclass,integer,integer)', 'EXECUTE') THEN
        RAISE EXCEPTION 'Thiếu quyền bắt buộc của app hoặc job';
    END IF;
END;
$$;

-- ========================= 3 / 3: SECURITY INSPECTION =========================
-- Read-only inspection under the original bootstrap administrator.
RESET TIME ZONE;
RESET ROLE;
