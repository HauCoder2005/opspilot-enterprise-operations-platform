---
name: opspilot-ponytail-lean
description: Use when an OpsPilot coding request risks unnecessary wrappers, classes, dependencies, duplicate abstractions, giant methods, or wasted context. Applies Ponytail's reuse-first principles without compromising security.
---
# Ponytail Bridge — Reuse-First Engineering

The actual Ponytail plugin must be installed separately via Claude Code marketplace (`DietrichGebert/ponytail`). This skill alone does not activate Ponytail hooks or commands.

Before writing code:
1. Is requested behavior already present or not necessary? Don't reimplement it.
2. Reuse existing contracts/services first.
3. Prefer Java 25 / Spring Security 7 platform features and existing dependencies.
4. Introduce the smallest correct extension that handles validation, transaction, audit and concurrency.
5. Do not introduce new libraries, generic base services, factories, decorators or self-made auth frameworks without a concrete requirement.
6. Never cut authentication checks, RLS, unit authorization, error handling, audit or tests to make fewer lines or tokens.
7. If Ponytail is active, consult the installed plugin's guidance; do not impersonate its actual tools if absent.

Report deletions/simplifications as suggestions, not as proofs of correctness. Avoid any Ponytail commands that implicitly use Git under OpsPilot's agent restrictions.
