# OpsPilot — Claude Code Skills Kit (2026-11-17)

Package dành cho repository `opspilot-management-system-api` theo cây thư mục người dùng cung cấp. **Không chứa mã nguồn gốc hoặc runtime của plugin bên thứ ba.** Các `SKILL.md` là workflow instructions; plugin/MCP/LSP phải cài riêng theo tài liệu hãng. Không có tool nào được cài trực tiếp vào máy bằng cách giải nén ZIP.

## Cài đặt không ghi đè file cũ

1. Sao lưu hoặc xem trước nội dung ZIP. Giải nén **vào root repository** (nơi có `.agents/`, `database/`, `docs/` và `scripts/`). Các skill mới đều có tên `opspilot-*` để tránh trùng những skill có sẵn; nếu thấy tên trùng, ngừng và so sánh trước khi ghi.
2. Chạy installer từ root của repository:

    - Linux/macOS: `python3 .agents/claude-tooling/install_skill_links.py`
    - Windows PowerShell: `py -3 .agents/claude-tooling/install_skill_links.py`

   Script tạo `.claude/skills/opspilot-*` trỏ đến `.agents/skills/opspilot-*` bằng symlink; nếu hệ điều hành chặn symlink, nó tạo *managed copies* và ghi rõ cảnh báo. Không xóa/ghi đè skill của bạn, không đụng `.codex`, `.git`, hoặc các phần logic Java. Script chỉ bổ sung một dòng import vào `CLAUDE.md` ở repository root (nếu chưa có).
3. Cài plugin runtime theo `.agents/claude-tooling/PLUGIN-SETUP.md` bằng lệnh do người dùng tự chạy trong Claude Code. Đừng đưa các plugin/MCP vào `.agents/skills` như mã nguồn Java.
4. Khởi động `claude` từ **root repository** rồi thử `/opspilot-auth-iam-design`, `/opspilot-java-docs` hoặc yêu cầu Claude liệt kê những skill `opspilot-*`.
5. `/plugin`, `/mcp`, `/memory`, `/context`, `/doctor` để kiểm chứng plugin, kết nối và memory.

## Cấu trúc sau cài đặt

```text
opspilot-management-system-api/
├── CLAUDE.md                      # tạo mới hoặc thêm đúng một import; giữ nội dung cũ
├── .agents/
│   ├── .claude/                    # CÓ SẴN; không can thiệp
│   ├── .codex/                     # CÓ SẴN; không can thiệp
│   ├── skills/
│   │   ├── ...                     # giữ nguyên skills cũ
│   │   ├── opspilot-auth-iam-design/SKILL.md
│   │   ├── opspilot-security-review/SKILL.md
│   │   ├── opspilot-jira-task/SKILL.md
│   │   ├── opspilot-ponytail-lean/SKILL.md
│   │   ├── opspilot-java-docs/SKILL.md
│   │   ├── opspilot-superpowers-tdd/SKILL.md
│   │   ├── opspilot-java-navigation/SKILL.md
│   │   ├── opspilot-project-memory/SKILL.md
│   │   └── opspilot-token-budget/SKILL.md
│   └── claude-tooling/
│       ├── CLAUDE-SKILLS-ROUTER.md
│       ├── PLUGIN-SETUP.md
│       ├── install_skill_links.py
│       └── DECISION-LOG-TEMPLATE.md
└── .claude/
    └── skills/
        └── opspilot-*             # symlink/copy từ .agents/skills
```

## Chính sách ưu tiên

Bảo mật > correctness > yêu cầu Jira / DB schema > kiểm thử > đơn giản hóa > tối ưu token. Ponytail không được dùng để bỏ validation, access control, audit, rollback handling, concurrency testing. Superpowers không được tự dùng Git worktree, commit hoặc các lệnh Git vì baseline OpsPilot đang cấm agent thao tác Git. Các quyết định IAM không được tự chốt.

## Tài liệu

- Claude skills: https://code.claude.com/docs/en/skills
- Claude memory / CLAUDE.md: https://code.claude.com/docs/en/memory
- Plugins: https://code.claude.com/docs/en/discover-plugins
- Ponytail: https://github.com/DietrichGebert/ponytail
- Context7: https://github.com/upstash/context7
- Superpowers: https://github.com/obra/superpowers
- JDTLS: https://claude.com/plugins/jdtls-lsp
- Serena: https://github.com/oraios/serena
