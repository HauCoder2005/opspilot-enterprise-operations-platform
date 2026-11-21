# OpsPilot Claude Code — bộ cài Skills (bản dễ giải nén)

**Quan trọng:** Gói này chứa **9 SKILL.md hướng dẫn** và công cụ đăng ký skill, **không chứa mã chạy gốc của Ponytail / Context7 / Superpowers / JDTLS / Serena**. Plugin/MCP phải cài riêng trong Claude Code, theo `tooling/PLUGIN-SETUP.md`.

## Đã sửa gì?

- Các file nằm trong `skills/` và `tooling/`, **không nằm trong thư mục ẩn `.agents/` trong ZIP**.
- Có `INSTALL.py` và `VERIFY.py` ngay ở tầng trên cùng, dễ tìm.
- Script **chỉ thêm các mục chưa tồn tại**, không ghi đè `.agents/.codex`, `.git`, source Java, database, hoặc skills cũ.
- Link/copy 9 skill vào `<repo>/.claude/skills`, nguồn gốc là `<repo>/.agents/skills`.
- Thêm duy nhất import router vào `CLAUDE.md` nếu thiếu; giữ lại nội dung hiện có.

## Cách 1: Giải nén ngay trong thư mục gốc dự án (khuyến nghị)

Cấu trúc sau giải nén (trước khi chạy script):

```text
opspilot-management-system-api/
├── .agents/               <- có sẵn
├── database/              <- có sẵn
├── docs/                  <- có sẵn
├── INSTALL.py             <- vừa giải nén
├── VERIFY.py              <- vừa giải nén
├── README-FIRST.md        <- vừa giải nén
├── skills/                <- vừa giải nén, 9 thư mục
└── tooling/               <- vừa giải nén
```

Tại root dự án chạy:

Linux/Arch/macOS:
```bash
python3 INSTALL.py
python3 VERIFY.py
```

Windows PowerShell:
```powershell
py -3 INSTALL.py
py -3 VERIFY.py
```

## Cách 2: Nếu giải nén ZIP vào một thư mục khác

Không nhất thiết phải di chuyển file. Trong thư mục đã giải nén chạy:

```bash
python3 INSTALL.py --project /duong-dan-toi/opspilot-management-system-api
python3 VERIFY.py --project /duong-dan-toi/opspilot-management-system-api
```

Trên Windows:
```powershell
py -3 INSTALL.py --project "D:\duong-dan\opspilot-management-system-api"
py -3 VERIFY.py --project "D:\duong-dan\opspilot-management-system-api"
```

**Nếu script báo không thấy repo:** đảm bảo đường dẫn thực sự có `.agents/` và `database/` hoặc `docs/` hoặc `pom.xml`.

## Kiểm tra đầu ra

- `VERIFY.py` phải in `RESULT: 9/9 skills, router=True`.
- Mở Claude Code tại repository root; dùng `/memory`, `/plugin` và `/mcp` để kiểm tra bổ sung.
- Skill instructions tự động xuất hiện trong Claude Code chỉ khi phiên bản CLI hỗ trợ discovery/symlink; nếu không, kiểm tra bản sao trong `.claude/skills` hoặc nâng cấp CLI.

## Phân biệt Skills với Plugins

- **Skills** = file Markdown để Claude biết *khi nào* và *cách nào* sử dụng quy trình.
- **Plugins/MCP/LSP** = mã công cụ thực sự chạy, cần cài qua Claude Code plugin marketplace hoặc công cụ quản lý tương ứng.
- Bộ cài này không chạy code bên ngoài, không tải package, không kết nối Jira, không cài plugin, không chạy Git.

Đọc thêm `tooling/PLUGIN-SETUP.md` sau khi `VERIFY.py` đạt 9/9.
