# Local environment

Hướng dẫn môi trường local và Docker hiện được duy trì tại
[`docs/development/API-BOOTSTRAP.md`](../development/API-BOOTSTRAP.md).

Spring Boot không tự đọc dotenv. Dùng `scripts/run-with-env.sh` để nạp lần lượt
`.env`, `.env.dev`, `.env.local`; file sau ghi đè file trước. Giữ credential trong
file local đã được `.gitignore`, không đặt trong source hoặc image.
