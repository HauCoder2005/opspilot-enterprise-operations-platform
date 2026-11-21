---
name: opspilot-superpowers-tdd
description: Use when planning nontrivial Java changes, implementing new authentication flows, debugging, writing unit/integration tests, or preparing evidence-based completion for OpsPilot.
---
# Superpowers Bridge — Plan / TDD / Verification

Superpowers is an external Claude Code plugin; this SKILL.md is only a workflow bridge.

- Start with exact AC/DoD and existing code; generate a narrow implementation plan before edits.
- For each important behavior, prepare failing test/reproduction, minimal correction, passing test; verify integration with PostgreSQL 18/Testcontainers when RLS/transaction behavior matters.
- Diagnose root cause and distinguish environmental failures from implementation failures.
- Before claiming completion, report exact test commands, results and remaining unknowns.
- Focus on brute force, account lockout, token expiry, token family reuse and cross-tenant isolation.
- The Superpowers plugin may suggest Git branches/worktrees/commits. **Do not perform any Git operation for OpsPilot**, even read-only, until the user explicitly changes the active rule.
- Do not spawn uncontrolled parallel agents against production credentials, migrations, DB or network.
