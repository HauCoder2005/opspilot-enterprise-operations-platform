---
name: opspilot-java-navigation
description: Use when finding Spring Java symbols and call chains, understanding relationships across controllers services repositories, or investigating large codebase changes in OpsPilot using JDTLS or Serena if enabled.
---
# Java LSP / Serena Bridge — Semantic Navigation

The actual `jdtls-lsp` plugin + local `jdtls` binary, or `serena` MCP plugin/service, are external runtimes and **not included** in this skill.

- Prefer built-in file/symbol reads when enough; use one semantic navigation tool, not both JDTLS and Serena for identical queries.
- Investigate controller -> service -> repository -> DB contract, then callers and tests; read only needed symbols and related type definitions.
- Resolve interfaces (`I*`) and method-security annotations before proposing refactors.
- Avoid broad full-repo dumps to save context and reduce leakage.
- If tools unavailable, fall back to ordinary read/search; report that precise symbol tooling was not active.
- Never run a Git-backed navigation shortcut prohibited by the task.
