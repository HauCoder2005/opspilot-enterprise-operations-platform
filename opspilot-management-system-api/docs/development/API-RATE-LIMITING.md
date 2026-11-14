# Rate limiting cho OpsPilot API

## Mục đích và thuật toán

Rate limiting giảm request bùng nổ, lỗi client và lạm dụng tài nguyên; nó **không thay thế authentication/authorization**. Bootstrap dùng Bucket4j Token Bucket trong bộ nhớ cho `/api/v1/**`.

Mỗi client có bucket chứa tối đa `RATE_LIMIT_BURST_CAPACITY=20` token. Mỗi request tiêu thụ một token. Bucket được nạp tham lam với tổng tốc độ `RATE_LIMIT_REQUESTS_PER_MINUTE=60`, tương đương xấp xỉ một token/giây. Vì capacity là 20, đây không phải quota rolling-window chính xác “60 request trong mọi khoảng 60 giây”: client có thể burst 20 request rồi tiếp tục theo tốc độ refill.

## Nhận dạng và vòng đời

Trong giai đoạn chưa authentication, key là remote IP do servlet container cung cấp. Filter cố ý không đọc `X-Forwarded-For`, vì client có thể giả header. Khi đặt sau reverse proxy, chỉ cấu hình forwarded headers khi proxy tin cậy đã xóa/ghi đè header; chiến lược danh tính tương lai nên dùng user đã xác thực cộng tenant scope, không dùng `organization_id` do client tự gửi.

Bucket map dùng Caffeine thread-safe, giới hạn `RATE_LIMIT_MAX_CLIENTS` và hết hạn sau `RATE_LIMIT_CLIENT_IDLE_MINUTES` không hoạt động. Filter chạy trước Spring Security: request dưới API path bị tính quota trước khi vào authorization; response 429 được ghi một lần và dừng filter chain.

Không giới hạn `/actuator/health`, Swagger UI hoặc `/v3/api-docs` vì chúng không thuộc `/api/v1/**`. Security/profile vẫn kiểm soát việc các URL đó có được truy cập hay không.

## Cấu hình

```dotenv
RATE_LIMIT_ENABLED=true
RATE_LIMIT_REQUESTS_PER_MINUTE=60
RATE_LIMIT_BURST_CAPACITY=20
RATE_LIMIT_MAX_CLIENTS=10000
RATE_LIMIT_CLIENT_IDLE_MINUTES=15
```

Đây là mặc định phát triển, không phải giới hạn traffic production. Đặt `RATE_LIMIT_ENABLED=false` để kiểm tra bypass; cần khởi động lại sau khi đổi cấu hình.

## HTTP 429

Khi thiếu token, API trả `application/problem+json`, status `429`, `X-RateLimit-Limit`, `X-RateLimit-Remaining` và `Retry-After`. `Retry-After` được làm tròn lên từ thời gian Bucket4j báo đến token kế tiếp, không hardcode.

```json
{
  "type": "about:blank",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau."
}
```

Swagger của `GET /api/v1/system/ping` document cả response này.

## Kiểm tra cục bộ

Với mặc định, gửi nhanh hơn capacity để thấy 429:

```sh
for i in $(seq 1 25); do
  curl -s -o /dev/null -D - http://localhost:8080/api/v1/system/ping | sed -n '1p;/Retry-After/p'
done
```

Để kiểm tra tất định hơn, tạm đặt `RATE_LIMIT_BURST_CAPACITY=2`, giữ `RATE_LIMIT_REQUESTS_PER_MINUTE=60`, khởi động lại và gửi ba request liên tiếp; request thứ ba phải là 429 với `Retry-After` khoảng 1 giây. Sau đó đặt `RATE_LIMIT_ENABLED=false`, khởi động lại và xác nhận tất cả request được chuyển tiếp.

## Giới hạn production và hướng mở rộng

- Mỗi instance có quota riêng; nhiều replica không chia sẻ trạng thái.
- Restart làm mất toàn bộ bucket.
- IP có thể đại diện nhiều người dùng sau NAT và proxy.
- Production nhiều replica cần gateway limiter hoặc distributed store đã được phê duyệt; Redis không cần cho bootstrap này.
- Khi có identity thật, có thể áp policy theo route/account type qua allowlist cấu hình rõ ràng và key từ danh tính đã xác thực. Không xây generic rule engine trước nhu cầu thực tế.
