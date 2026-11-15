# Khởi tạo và migration cơ sở dữ liệu

## Mục đích và nguồn chuẩn

Flyway quản lý thứ tự, checksum và lịch sử thay đổi schema của OpsPilot. Hibernate
luôn dùng `ddl-auto=none`; JPA không tạo hoặc cập nhật schema.

`src/main/resources/db/migration/V1__initial_schema.sql` là bản hợp nhất SQL V1–V14
đã tạo cơ sở dữ liệu OpsPilot hiện hữu. Đây là nguồn chuẩn cho database mới: 77 bảng
chính, 18 partition bảng, extension `ltree`/`btree_gist`, constraint, index, sequence,
identity, function, trigger, RLS, grant và ownership. V1 không tạo database hoặc role.

Hai điều chỉnh dành riêng cho Flyway không thay đổi mô hình đã duyệt:

- timezone tạo partition được cố định là `Asia/Ho_Chi_Minh`, giống database hiện hữu;
- bước thu hồi quyền chỉ xử lý object do `opspilot_owner` sở hữu, không đụng bảng
  `flyway_schema_history` do tài khoản migration sở hữu.

Không sửa V1 sau khi đã áp dụng ở bất kỳ môi trường dùng chung nào. Mọi thay đổi tiếp
theo phải là file version mới.

## Vai trò PostgreSQL

`database/bootstrap/01_roles.sql` tạo hoặc chuẩn hóa bốn role đã duyệt:

- `opspilot_owner`: sở hữu schema nghiệp vụ;
- `opspilot_app`: datasource request của ứng dụng, không bypass RLS;
- `opspilot_job`: job có `BYPASSRLS`;
- `opspilot_etl`: quyền đọc theo whitelist, có `BYPASSRLS`.

Script không chứa password và chỉ chạy bằng superuser trên cluster dành riêng cho
OpsPilot. Docker PostgreSQL chỉ chạy script này trong lần `initdb` đầu tiên của named
volume. Flyway V1 dùng `SET ROLE opspilot_owner`; vì vậy role bootstrap phải hoàn tất
trước migration.

Team stack hiện giữ cách đăng nhập đơn giản bằng local superuser cho API và Flyway.
Đây chỉ là tiện ích phát triển. Superuser bỏ qua RLS, nên kết quả chạy bằng `postgres`
không chứng minh khả năng cô lập tenant production. Khi tách credential, cấp secret
riêng cho migration user có quyền `SET ROLE opspilot_owner` và cho API
`opspilot_app`; tuyệt đối không dùng owner/job/ETL cho request REST.

## Thiết lập cho developer mới

Yêu cầu: Docker Compose và cổng API/team DB còn trống. Không cần chạy SQL thủ công.

```bash
cp .env.example .env.local
```

Đặt tối thiểu các giá trị sau trong `.env.local` và không commit file này:

```dotenv
TEAM_DB_PASSWORD=<mật-khẩu-local-mạnh>
TEAM_DB_PORT=55432
TEAM_API_PORT=8080
TEAM_DB_TIMEZONE=Asia/Ho_Chi_Minh
```

Khởi động stack:

```bash
docker compose --env-file .env.local -f compose.team.yaml up --build -d
docker compose --env-file .env.local -f compose.team.yaml ps --all
docker compose --env-file .env.local -f compose.team.yaml logs migrate
```

Luồng phụ thuộc là `db healthy -> migrate exit 0 -> api start`. API dùng hostname
Docker `db:5432`, không thể vô tình kết nối PostgreSQL trên host. PostgreSQL được publish
ở `127.0.0.1:55432`; API ở `127.0.0.1:8080`.

Các URL kiểm tra:

- health: `http://127.0.0.1:8080/actuator/health`;
- OpenAPI: `http://127.0.0.1:8080/v3/api-docs`;
- Swagger UI: `http://127.0.0.1:8080/swagger-ui/index.html`.

Không chạy `docker compose down -v`: tùy chọn `-v` xóa named volume và toàn bộ dữ
liệu local. Dừng thông thường bằng `docker compose ... stop`.

## Kiểm tra migration

Kiểm tra Flyway và chạy lại idempotent:

```bash
docker compose --env-file .env.local -f compose.team.yaml run --rm migrate info
docker compose --env-file .env.local -f compose.team.yaml run --rm migrate validate
docker compose --env-file .env.local -f compose.team.yaml run --rm migrate
```

Lần chạy sau phải báo schema ở version 1 và `No migration necessary`. Không dùng
`repair` để che checksum mismatch.

Kiểm tra cấu trúc bằng PostgreSQL client:

```bash
PGPASSWORD='<TEAM_DB_PASSWORD>' psql -X -h 127.0.0.1 -p 55432 \
  -U postgres -d opspilot -v expected_database=opspilot \
  -f database/verification/verify_schema.sql

PGPASSWORD='<TEAM_DB_PASSWORD>' psql -X -h 127.0.0.1 -p 55432 \
  -U postgres -d opspilot \
  -f database/verification/schema_fingerprint.sql
```

Verifier kiểm tra bảng, partition, extension, role, function, trigger, RLS, grant,
owner, sequence, identity, index và bản ghi Flyway V1.

## Nhận quản lý database hiện hữu

Quy trình này chỉ dành cho database đã được khởi tạo bằng SQL chuẩn nhưng chưa có
`flyway_schema_history`. Không chạy V1 lên database đó.

### 1. Backup và xác minh đích

Lên lịch bảo trì, xác nhận hostname/port/tên database và tạo backup có kiểm thử:

```bash
PGPASSWORD='<password>' pg_dump -Fc -h <host> -p <port> \
  -U <backup-user> -d opspilot -f opspilot-before-flyway.dump
```

Kiểm tra backup có thể đọc bằng `pg_restore --list`. Chuẩn bị cả kế hoạch restore và
thời gian rollback; baseline không thay thế backup.

Chạy preflight bằng credential có quyền đọc catalog:

```bash
PGPASSWORD='<password>' psql -X -h <host> -p <port> \
  -U <migration-user> -d opspilot -v expected_database=opspilot \
  -f database/verification/verify_existing_before_baseline.sql

PGPASSWORD='<password>' psql -X -h <host> -p <port> \
  -U <migration-user> -d opspilot \
  -f database/verification/schema_fingerprint.sql \
  > schema-fingerprint.before.txt
```

Preflight dừng nếu sai database/PostgreSQL version, đã có history, thiếu object,
constraint/function/partition/extension, role, owner, RLS hoặc grant quan trọng. Ngoài
script, đối chiếu danh sách object và dump schema với V1 đã review. Không baseline nếu
có khác biệt chưa giải thích.

### 2. Tạo marker V1 rõ ràng

Chỉ sau khi backup và preflight đều đạt, chạy lệnh `baseline` chủ động. Ví dụ trên
Linux với Flyway image:

```bash
docker run --rm --network host \
  -e FLYWAY_URL='jdbc:postgresql://127.0.0.1:<port>/opspilot' \
  -e FLYWAY_USER='<migration-user>' \
  -e FLYWAY_PASSWORD='<password>' \
  -e FLYWAY_DEFAULT_SCHEMA=public \
  -e FLYWAY_BASELINE_VERSION=1 \
  -e FLYWAY_BASELINE_DESCRIPTION='OpsPilot existing schema adopted' \
  -e FLYWAY_BASELINE_ON_MIGRATE=false \
  -e FLYWAY_CLEAN_DISABLED=true \
  flyway/flyway:12.4.0 baseline
```

Đây là marker `BASELINE` version 1, không phải bản ghi `SQL`; V1 sẽ không được replay.
Không bật `baselineOnMigrate` để tự nhận mọi database không rỗng.

### 3. Xác minh sau baseline

```bash
PGPASSWORD='<password>' psql -X -h <host> -p <port> \
  -U <migration-user> -d opspilot \
  -v expected_database=opspilot -v expected_history_type=BASELINE \
  -f database/verification/verify_schema.sql

PGPASSWORD='<password>' psql -X -h <host> -p <port> \
  -U <migration-user> -d opspilot \
  -f database/verification/schema_fingerprint.sql \
  > schema-fingerprint.after.txt

diff -u schema-fingerprint.before.txt schema-fingerprint.after.txt
```

Diff phải rỗng. Đối chiếu thêm số dòng hoặc data-only dump trước/sau. Sau đó mount
`src/main/resources/db/migration` và chạy `flyway validate`, rồi `flyway migrate`;
không có V2 thì không được thay đổi schema. Không tự `repair`, xóa history hoặc sửa
checksum đã áp dụng.

## Thêm migration mới

Tạo đúng một file version mới, ví dụ:

```text
V2__add_feature_configuration.sql
V3__create_new_business_index.sql
V4__update_inventory_constraints.sql
```

Tên phải là `V<version>__<mô_tả_snake_case>.sql`. Không đổi tên, sắp xếp lại hoặc sửa
migration đã áp dụng. Migration phải chạy trong transaction khi PostgreSQL hỗ trợ,
không chứa `CREATE DATABASE`/password/role toàn cluster. Bảng tenant mới phải có
`organization_id`, khóa chống tham chiếu chéo tenant, RLS policy, grant tối thiểu và
kiểm tra `rls_coverage` trong cùng migration.

Review SQL, chạy fresh stack, chạy lại lần hai, rồi thử upgrade từ database ở version
trước. Flyway áp migration pending theo thứ tự version và kiểm tra checksum trước khi
thay đổi database.

## Lỗi thường gặp

- `TEAM_DB_PASSWORD is missing`: truyền `--env-file .env.local` và đặt giá trị không
  rỗng.
- `password authentication failed`: kiểm tra đúng credential của team volume; đổi
  biến sau khi volume đã init không đổi password trong database.
- `Connection refused`: chờ healthcheck, kiểm tra `docker compose ps` và log `db`.
- `role "opspilot_owner" does not exist`: role bootstrap chưa chạy; dùng volume mới
  đúng cấu hình hoặc provision role có kiểm soát, không nhét `CREATE ROLE` vào V1.
- `Found non-empty schema ... but no schema history table`: dừng; backup, chạy
  preflight và explicit baseline. Không bật baseline tự động.
- `Migration checksum mismatch`: file migration đã bị sửa. Khôi phục file đúng từ
  Git và điều tra; không âm thầm `repair`.
- SQL migration lỗi: PostgreSQL/Flyway rollback migration transactional và API không
  được start. Sửa bằng migration version mới nếu version lỗi đã được áp dụng ở nơi
  khác.
- API không start: kiểm tra exit code/log của `migrate` trước; không bật Spring-managed
  Flyway song song. `FLYWAY_ENABLED=false` là bắt buộc trong team Compose.

## Backup, phục hồi và giới hạn bảo mật

Trước baseline hoặc migration có rủi ro, phải có backup, retention, mã hóa, kiểm tra
restore và RPO/RTO được duyệt. Flyway quản lý DDL chứ không thay thế PITR, WAL archive
hoặc quy trình disaster recovery. `clean` bị vô hiệu hóa trong cấu hình thường; không
dùng clean/drop/down-volume như cách rollback.

Team stack không phải mô hình production: password local dùng một login, database/API
publish trên loopback, và API chạy bằng superuser để onboarding đơn giản. Kiểm thử RLS
production phải kết nối bằng `opspilot_app`, đặt tenant context trong transaction và
thử cả truy cập chéo tenant; kết quả bằng superuser không có giá trị chứng minh isolation.
