# OpsPilot Codex Skills v1.0.1 — HƯỚNG DẪN CÀI ĐẶT

## Tại sao folder giải nén trông trống?

Các file Skill nằm trong **thư mục ẩn** `.agents` và `.codex` (tên bắt đầu bằng dấu chấm).
Trình quản lý file trên Linux thường ẩn chúng. Nhấn **Ctrl+H** để hiện file ẩn.

## Nội dung

- `skills`
- `skills`
- `skills`
- `.codex/config.toml.example` (chỉ là ví dụ; không tự có hiệu lực)

## Cài trên Arch Linux

1. Mở Terminal tại **thư mục gốc repository** `opspilot-system-business` (nơi có `pom.xml`).
2. Giải nén ZIP vào thẳng thư mục gốc:

```bash
unzip -n ~/Downloads/opspilot-codex-skills-v1.0.1.zip -d .
```

3. Xem các thư mục ẩn và kiểm tra Skills:

```bash
ls -la
find .agents/skills -type f -print
```

4. Nếu đã giải nén vào một folder riêng, mở folder đó và nhấn **Ctrl+H**, rồi sao chép thư mục `.agents` vào **root repository**.
5. Có thể dùng Skill trong Codex bằng tên `$opspilot-java-implementation` hoặc `$opspilot-code-review`.

## Cấu hình tùy chọn

`.codex/config.toml.example` là **file ví dụ**, không tự kích hoạt. Chỉ khi cần hãy đối chiếu với cấu hình Codex hiện tại và tạo `.codex/config.toml`. Đừng ghi đè cấu hình đang dùng mà chưa kiểm tra.

## Lưu ý

- ZIP **không bao gồm AGENTS.md**: file đó thuộc quy ước project của bạn và cần được duy trì riêng.
- Không cần cài thêm dependency vào `pom.xml` chỉ để dùng Skill.
