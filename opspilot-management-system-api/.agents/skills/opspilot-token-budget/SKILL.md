---
name: opspilot-token-budget
description: Use when Claude Code context is filling, logs are huge, research is repetitive, or a costly tool/agent workflow needs to be scoped for OpsPilot.
---
# Token / Context Discipline

- Only open files/symbols needed by the selected ticket; prefer focused reads, bounded excerpts, and references to canonical files.
- Keep one task focus per session; place test logs into files only if authorized, then report summaries and failing lines.
- Load relevant SKILL.md(s) on demand, not all skill bodies at every turn.
- Do not repeat full Jira descriptions or third-party docs in every response; use concise evidence matrix.
- Reuse existing DTO/service contracts and avoid verbose restatement of known context.
- Use `/context` and `/compact` when needed; preserve important decisions and failures in a short handoff first.
- Never skip tests, policy checks or audit to save tokens. Optimize retrieval and repetition, not correctness.
