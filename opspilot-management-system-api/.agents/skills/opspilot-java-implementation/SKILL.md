---
name: opspilot-java-implementation
description: Implement or refactor Java/Spring backend behavior in OpsPilot, including REST APIs, domain logic, persistence and module boundaries. Use for code-changing tasks; not for documentation-only changes or independent code reviews.
---

# OpsPilot Java implementation

Use the active task and `../../../AGENTS.md` as authority. Do not repeat the technology baseline here.

## Efficient execution

1. Identify the required behavior, affected module, acceptance criteria and invariants. Read only relevant files, nearby tests and approved decisions; expand the search only when evidence warrants it.
2. Choose one simple design that fits existing code. Note tradeoffs internally; do not narrate reasoning unless asked. If a decision changes approved business behavior or architecture, surface it rather than inventing policy.
3. Implement the smallest complete vertical slice. Reuse conventions and dependencies; do not scaffold unrelated modules, empty classes, utilities or speculative abstractions.
4. Add focused tests for new behavior, failure paths and relevant permission/transaction boundaries. Run targeted checks first; widen scope when a change is cross-cutting or failures require it.
5. Inspect the diff, fix introduced defects, and stop when the task's acceptance criteria are met. Never report unexecuted checks as passed.

## Code and design standards

- Prefer expressive names, short cohesive methods, early returns and explicit invariants. Avoid hidden side effects, excessive indirection and duplication of business rules.
- Keep controllers for transport and validation, application services for use cases/transactions, domain for rules, and repositories/adapters for persistence. Keep module public APIs narrow.
- Use constructor injection, composition and immutable DTO records where appropriate. Introduce an interface only for an actual port, independent implementation, or useful test seam; not one per class.
- Prefer standard Spring/Java mechanisms over custom frameworks. Use design patterns to remove a demonstrated source of complexity, not to decorate CRUD.
- Keep cross-module interaction through published module APIs/events; no direct access to another module's repository or JPA entities.
- Respect organization/unit scope, transaction consistency, SQL parameterization, idempotency and Flyway migrations whenever relevant. Query performance must be checked rather than guessed.
- When choosing between patterns or refactoring a design, read `references/pattern-decisions.md`; skip it for straightforward changes.

## Compact completion report

Respond in Vietnamese in **at most 8 short lines** unless the user requests detail:
- `Done:` implemented behavior and affected module.
- `Files:` key paths (group when possible).
- `Verified:` exact checks and PASS/FAIL/NOT RUN.
- `Risks:` only material open issues, otherwise `none`.

Do not paste full source files, restate the task, list every unchanged file, or provide a long design essay. Never trade code quality or verification for brevity.
