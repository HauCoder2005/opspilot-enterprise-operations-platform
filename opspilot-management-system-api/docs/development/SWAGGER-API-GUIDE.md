# Hướng dẫn Swagger/OpenAPI

## Vai trò trong OpsPilot

OpenAPI mô tả contract HTTP có cấu trúc; Swagger UI đọc mô tả đó để duyệt, tìm kiếm và gọi thử API. Trong OpsPilot, tài liệu được sinh từ controller, DTO và annotation, nên phải phản ánh đúng behavior đã triển khai thay vì tạo endpoint minh họa giả.

Ở profile `dev`, truy cập:

- UI: `http://localhost:8080/swagger-ui.html` hoặc `/swagger-ui/index.html`.
- JSON: `http://localhost:8080/v3/api-docs`.
- YAML: `http://localhost:8080/v3/api-docs.yaml`.

## Sử dụng UI

Endpoint hiện tại nằm trong tag **System**. Mở tag để xem `GET /api/v1/system/ping`, mục đích, response `200` và lỗi `429`. Ô **Filter** trên thanh đầu chỉ hiển thị các operation/tag khớp chuỗi tìm kiếm; ví dụ nhập `System` hoặc `ping`. Đây là lọc hiển thị phía UI, không gọi API filter nghiệp vụ.

Mở operation để xem parameter, request schema và response schema. Ping không có parameter hay request body; response `200` là DTO gồm `service` và `status`. Nhấn **Try it out**, sau đó **Execute** để gửi request thật. Không đặt password, token hay secret vào example hoặc request không cần chúng.

Mã thường gặp:

- `200`: yêu cầu thành công.
- `400`: dữ liệu/validation không hợp lệ (API tương lai phải trả problem JSON nhất quán và chỉ rõ field vi phạm).
- `401`: chưa có danh tính hợp lệ.
- `403`: có request nhưng không được phép hoặc route chưa được mở trong bootstrap.
- `429`: vượt token bucket; đọc `Retry-After`, đợi số giây tương ứng rồi thử lại.
- `5xx`: lỗi phía dịch vụ; không suy luận chi tiết nội bộ từ response công khai.

## Document API mới

Ví dụ thật đang dùng trong `SystemController`:

```java
@Tag(name = "System", description = "Các API nền tảng và kiểm tra khả dụng công khai")
@Operation(operationId = "pingSystem", summary = "Kiểm tra API đang hoạt động")
@ApiResponse(responseCode = "200", description = "Dịch vụ API đang hoạt động")
@ApiResponse(responseCode = "429", description = "Client đã vượt quá giới hạn yêu cầu")
```

DTO dùng `@Schema` cho ý nghĩa và example ổn định. Chỉ thêm `@Parameter`, request-body example, validation và error code thực sự có trong contract. Mỗi endpoint dưới `/api/v1/**` phải document `429` khi được rate limiter bảo vệ. Không lặp mô tả hiển nhiên và không invent permission/security scheme; bootstrap chưa có JWT thật nên chưa có nút Authorize.

Nhóm theo trách nhiệm bằng `@Tag`: hiện chỉ có `System`. Khi có API thật mới dùng Organization, Identity & Access, Workforce, Catalog, Sales, Inventory, Task, Reporting, Platform hoặc AI. Không tạo `GroupedOpenApi` nếu chưa cần nhiều specification độc lập.

Với API phân trang/lọc về sau, annotation phải ghi rõ tên filter, bắt buộc/tùy chọn, mặc định, field sort được phép, page bắt đầu từ 0, page size tối đa và behavior khi sai; không document contract chưa triển khai.

## Cấu hình và lỗi thường gặp

`SWAGGER_FILTER_ENABLED` điều khiển ô tìm kiếm; `SWAGGER_TRY_IT_OUT_ENABLED` mở sẵn khả năng gọi thử. `SWAGGER_ENABLED=false` tắt cả UI và API docs. Profile `prod` mặc định tắt Swagger và Security không allowlist các đường dẫn Swagger khi docs bị tắt.

- UI 404: kiểm tra profile và `SWAGGER_ENABLED`.
- UI mở nhưng không tải spec: gọi trực tiếp `/v3/api-docs`, kiểm tra Security và log startup.
- Không thấy endpoint: controller phải nằm dưới `dev.opspilot`, có mapping hợp lệ và không bị ẩn.
- Không thấy ô search: đặt `SWAGGER_FILTER_ENABLED=true`, khởi động lại rồi hard refresh trình duyệt.
- Try It Out lỗi CORS: khi UI chạy khác origin, thêm đúng origin vào `CORS_ALLOWED_ORIGINS`; không dùng wildcard với credential.
- JSON đúng nhưng UI cache cũ: reload không cache hoặc mở cửa sổ riêng tư.
