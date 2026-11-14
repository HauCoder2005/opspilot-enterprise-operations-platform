# Khởi động OpsPilot API

## Yêu cầu

- JDK 25 và Docker có Compose plugin (Docker chỉ cần cho cách chạy container).
- PostgreSQL 18 đã có database `opspilot`; bootstrap này không tạo database, role, bảng hay migration.
- Maven Wrapper đi kèm repository, không cần cài Maven hệ thống.

Tài khoản `postgres` cục bộ là superuser và **bỏ qua RLS**. Đây chỉ là giới hạn chấp nhận cho phát triển ban đầu, không chứng minh được phân quyền theo tổ chức/chi nhánh và không được dùng làm mẫu production.

## Cấu hình môi trường

Sao chép `.env.example` thành `.env.local`, rồi điền tối thiểu `DB_PASSWORD` nếu PostgreSQL yêu cầu mật khẩu. Script nạp lần lượt `.env`, `.env.dev`, `.env.local`; file sau ghi đè file trước. Spring Boot không tự nạp dotenv nên luôn chạy qua script:

```sh
cp .env.example .env.local
./scripts/run-with-env.sh ./mvnw spring-boot:run
```

Các biến đang có hiệu lực gồm cấu hình ứng dụng, datasource/Hikari, Flyway, Swagger, rate limit, JWT flag/TTL và CORS. `JWT_SECRET`, nhóm Storage, AI và Email trong template là **reserved**, chưa được bind và không bắt buộc khi khởi động. Giữ `JWT_ENABLED=false` cho đến khi IAM thực sự được triển khai.

`FLYWAY_ENABLED=false` và `spring.jpa.hibernate.ddl-auto=none` là mặc định bắt buộc cho schema được bootstrap thủ công. Không bật Flyway trước khi lịch sử migration được review. JPA dùng cho lifecycle aggregate về sau; `JdbcClient` được Spring Boot cấu hình từ cùng `DataSource` để dùng cho SQL chuyên biệt. Cả hai không sở hữu schema trong task này.

## Chạy và kiểm tra native

```sh
./scripts/run-with-env.sh ./mvnw clean verify
./scripts/run-with-env.sh ./mvnw spring-boot:run
curl -i http://localhost:8080/api/v1/system/ping
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/health/liveness
curl -i http://localhost:8080/actuator/health/readiness
```

`ping` chỉ xác nhận tiến trình API và không lộ chẩn đoán database. Readiness chứa health của database; liveness chỉ chứa trạng thái tiến trình nên PostgreSQL tạm mất kết nối không làm liveness fail.

Security chỉ cho phép `GET /api/v1/system/ping`, health probes và Swagger khi Swagger được bật. Mọi route khác bị deny. CORS chỉ áp dụng dưới `API_BASE_PATH` và chỉ chấp nhận origin cấu hình trong `CORS_ALLOWED_ORIGINS` (phân tách bằng dấu phẩy nếu có nhiều origin).

## Chạy bằng Docker

```sh
./scripts/run-with-env.sh docker compose config
./scripts/run-with-env.sh docker compose build api
./scripts/run-with-env.sh docker compose up -d --wait api
./scripts/run-with-env.sh docker compose logs api
```

Compose chỉ chạy API, không tạo PostgreSQL khác. Cấu hình dùng `network_mode: host`, phù hợp môi trường Linux/Arch: trong container, `DB_HOST=127.0.0.1` trỏ tới PostgreSQL trên host. Trên Docker Desktop hoặc môi trường không hỗ trợ host network, bỏ host network, publish cổng API và đặt `DB_HOST=host.docker.internal` (cần mapping host-gateway trên Linux nếu không có sẵn).

Image được build nhiều stage bằng Java 25, runtime chạy user `opspilot` không phải root, không copy dotenv/secrets và chỉ khai báo cổng 8080. Điều chỉnh bộ nhớ bằng `JVM_MEMORY_OPTIONS`.

## Profile và xử lý sự cố

- `dev`: Swagger bật mặc định.
- `prod`: Swagger tắt mặc định; chỉ bật khi có quyết định vận hành rõ ràng.
- Lỗi datasource: kiểm tra host/port/database/user/password và `DB_SSLMODE`; với native dùng `127.0.0.1`, với container phụ thuộc network như trên.
- Lỗi Flyway trên schema có sẵn: đặt lại `FLYWAY_ENABLED=false`; không tự baseline.
- HTTP 403 ở route khác: đúng với bootstrap allowlist, chưa phải lỗi JWT.
- Health readiness `DOWN` nhưng liveness `UP`: tiến trình còn sống nhưng dependency database chưa sẵn sàng.

Các URL làm việc: `/api/v1/system/ping`, `/actuator/health`, `/swagger-ui.html`, `/swagger-ui/index.html`, `/v3/api-docs`, `/v3/api-docs.yaml`.
