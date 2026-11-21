---
name: opspilot-project-memory
description: Use when handing off an OpsPilot coding session, preserving approved architectural decisions, comparing decisions to prior work, or recovering task context without bloating prompt windows.
---
# Durable Memory, Short Sessions

1. Claude Code's built-in `/memory` is the default. Do not install unreviewed external memory plugins solely to 'remember longer'.
2. Keep memory as compact pointers: approved decision IDs, affected KAN issues, repository paths, tests run, blockers, links to canonical docs.
3. NEVER invent past approvals or write an unresolved proposal as APPROVED.
4. Never store passwords, API keys, access/refresh tokens, private credentials, raw employee data or secrets.
5. After substantial work, offer a short handoff (task, changed files, verified tests, open blockers). Write to `DECISION-LOG-TEMPLATE.md` or project notes only if edits are authorized.
6. Re-read actual Jira/schema/source when restarting work; memory is an index, not a substitute for authoritative current files.
