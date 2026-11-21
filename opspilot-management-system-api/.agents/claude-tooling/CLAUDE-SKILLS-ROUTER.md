# OpsPilot Claude Code — Skill Router

## Scope and priority

Use this file only for `opspilot-management-system-api`. Existing `CLAUDE.md`, `AGENTS.md` (if separately imported), approved requirements, actual DB migrations and Jira tickets remain authoritative. Do not silently override them.

- **Skill source of truth**: `.agents/skills/opspilot-*/SKILL.md`.
- Claude Code discoverable entry points: `.claude/skills/opspilot-*`, linked/copied by `.agents/claude-tooling/install_skill_links.py`.
- Before substantial work, pick only the relevant skill(s), read their SKILL.md, and use supporting files on demand. **Do not load all skills every request.**
- When a third-party skill mentions a plugin/tool, check availability before using it. A SKILL.md does NOT install the actual Ponytail, Context7, Superpowers, JDTLS, or Serena runtime.

## Routing

- Auth, account provisioning, IAM, ORG, HR flows -> `opspilot-auth-iam-design`.
- Code/security audit, boundary checks, DoD -> `opspilot-security-review`.
- Any Jira issue, AC, state review -> `opspilot-jira-task`.
- Reuse-first and avoiding overengineering -> `opspilot-ponytail-lean`.
- Spring Boot / Security / JPA / PostgreSQL version-specific API research -> `opspilot-java-docs`.
- TDD, debugging, tests and evidence-based completion -> `opspilot-superpowers-tdd`.
- Navigation of Java symbols, references, larger refactors -> `opspilot-java-navigation`.
- Approved decisions, handoff, session continuity -> `opspilot-project-memory`.
- Excessive context, token constraints, retrieval discipline -> `opspilot-token-budget`.

## Absolute guardrails

- Until authorized: **audit and design only**; no source code edits or DB mutations.
- No Git operations of any kind, including status, diff, log, worktree, commit, push, and no read/write of `.git` (Jira agent rule); inform user if third-party plugins would need Git.
- Do not update Jira without explicit permission; do not claim a ticket is Done without test evidence.
- No secrets or tokens in logs, comments, skill files, memory, or prompts.
- Keep data isolation with PostgreSQL RLS; never bypass permission + unit scope + business rules.
- Keep interfaces I-prefixed, constructor injection, existing layered naming. Do not introduce AbstractCrudService or redundant abstractions.
- Source of truth for actual implemented DB columns/types is the actual migration SQL, not an imagined schema.
- Record ambiguity as OPEN DECISION; differentiate APPROVED vs EXISTING vs PROPOSED vs UNVERIFIED.
- **Security, correctness, evidence and reliable behavior beat saving tokens or LOC.**
