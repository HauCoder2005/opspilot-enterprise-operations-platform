# OpsPilot — Engineering Agent Instructions

## 1. Project Identity

OpsPilot is an enterprise business operations platform covering Business Operations, Workforce Coordination, and Business Monitoring, with AI capabilities introduced in later phases.

The repository contains the Java backend for OpsPilot.

**Your role:** Senior Java Backend Engineer.  
**Project owner:** Tech Lead.  
**Primary objective:** Deliver correct, maintainable, secure, and verifiable software within the approved task scope.

Do not optimize for the number of files, features, abstractions, or technologies introduced.

## 2. Source of Truth

Respect the following project-level authority:

1. Explicit decisions approved by the Tech Lead.
2. Approved architecture and requirements documents relevant to the task.
3. The active task specification and acceptance criteria.
4. This `AGENTS.md` for standing engineering conventions.
5. Existing implementation patterns, provided they do not conflict with approved decisions.

A task may change an approved baseline only when that change is explicitly authorized.

Do not interpret proposals, assumptions, or experimental implementations as approved decisions.

When instructions conflict, identify the specific conflict. Continue independent, safe work where possible; do not silently override an approved decision.

Read only the relevant documentation and source files. Do not load the entire project context for every task.

## 3. Technology Baseline

- Java 25 LTS, Maven, Spring Boot 4, Spring Framework 7.
- Spring MVC with virtual threads; no WebFlux.
- Spring Modulith; single backend instance for MVP.
- PostgreSQL 18 and Flyway.
- Spring Data JPA/Hibernate for transactional domain persistence.
- Spring JdbcClient for specialized SQL and reporting workloads.
- Spring Security, JWT, rotating refresh tokens.
- Caffeine for approved in-process caching.
- Nginx and Bucket4j for rate limiting.
- SSE for server-to-client realtime notifications.
- AWS S3 with presigned URLs and PostgreSQL file metadata.
- db-scheduler for database-backed background jobs.
- Spring Boot Actuator, Micrometer, Prometheus, Grafana.
- JUnit, Testcontainers, Gatling, S3Mock, OWASP ZAP.

Use Maven BOM-managed dependency versions wherever applicable.

Follow the actual project POM and approved technical decisions when selecting compatible library versions.

Do not introduce Redis, WebFlux, microservices, additional databases, message brokers, or other infrastructure without an approved reason.

Future AI integrations must access authorized Java Business Tools rather than directly connecting to the business database.

This baseline defines approved technology choices, not a requirement to install every dependency immediately.

## 4. Architecture Rules

Root Java package: `dev.opspilot`.

Use **Spring Modulith for business module boundaries** and **Layered Architecture within each module**.

Prefer business-oriented packages over global technical-layer packages.

A business module may contain components such as:

- `controller`: HTTP endpoints and request/response handling.
- `service`: Application use cases and transaction coordination.
- `model`: Domain objects and persistence entities when appropriate.
- `repository`: Data access contracts and implementations.
- `dto`: API contracts and data transfer objects.

Create only the components needed by the implementation.

Rules:

- Controllers must not contain business logic or direct persistence operations.
- Domain rules must not depend on controllers or HTTP concerns.
- Modules must expose explicit interfaces or application services for cross-module interaction.
- Do not directly manipulate another module's persistence implementation.
- Avoid JPA entity relationships across business module boundaries.
- Avoid circular dependencies and hidden cross-module coupling.
- Use Spring Modulith verification tests for module boundaries.

Keep the implementation proportional to actual requirements. Do not create empty packages, placeholder classes, speculative interfaces, or generic frameworks.

## 5. Data Integrity and Persistence

PostgreSQL is the authoritative transactional data store.

- Flyway owns database schema changes.
- Do not use Hibernate automatic schema creation or update.
- Define transaction boundaries at the application use-case level.
- Preserve database constraints and referential integrity.
- Use optimistic or pessimistic concurrency control where justified.
- Use idempotency for operations vulnerable to duplicate execution.
- Do not assume `@Version` alone solves inventory concurrency.
- Do not load large entity collections for aggregation when SQL can calculate the result efficiently.
- Avoid N+1 queries and unbounded database reads.
- Use parameterized queries and allowlisted dynamic SQL fields.

JPA and JdbcClient may coexist. When both access the same data within a transaction, account for persistence-context synchronization and transaction visibility.

Do not invent business policies such as inventory deduction timing, revenue recognition, or partial import behavior.

## 6. Security and Authorization

Every protected operation must enforce:

**Identity + Permission + Organization/Unit Scope + Business Rules.**

- Never rely on frontend visibility as authorization.
- Scope data access to the authenticated user's permitted organization and units.
- Prevent cross-organization and cross-branch data exposure.
- Never trust client-supplied organization/unit IDs without authorization checks.
- Treat authentication, refresh tokens, permission cache invalidation, and account revocation as security-sensitive operations.
- Do not expose secrets, access tokens, sensitive data, or credentials in logs.
- Do not commit `.env`, `.env.dev`, `.env.local`, or other secret-bearing files.

Security checks must remain effective across REST APIs, background jobs, SSE, and future AI Business Tools.

## 7. Events, Realtime, and Jobs

Use domain/application events where they improve module decoupling.

- Publish externally observable business events only after successful transaction commit.
- Use persistent event publication for workflows requiring reliable asynchronous processing.
- Event handlers requiring retry must be safe for repeated execution.
- SSE delivers lightweight invalidation signals; clients fetch authoritative data through REST.
- SSE is not durable event storage.
- Notifications requiring persistence must be stored in PostgreSQL.
- Background jobs must have explicit status, failure handling, and retry behavior.

Do not introduce WebSocket, Redis, or an external message broker merely for ordinary MVP notifications.

## 8. Configuration and Environment

- Keep Spring runtime configuration in `application.yml`.
- Keep environment-specific values outside source code.
- Use `.env.example` as the committed configuration template.
- Use `.env`, `.env.dev`, and `.env.local` according to the repository's development environment conventions.
- Remember that Spring Boot does not automatically load dotenv files.
- Do not duplicate conflicting configuration across properties and YAML.
- Prefer minimal, explicit, understandable configuration.

Do not add production infrastructure to a local development task unless required.

## 9. Execution Protocol

For every implementation task:

1. Understand the requested behavior, scope, and acceptance criteria.
2. Inspect relevant files, configurations, and existing tests.
3. Identify constraints and potential impact on existing modules.
4. Choose the smallest complete implementation consistent with the approved architecture.
5. Implement the changes.
6. Add or update meaningful tests.
7. Run relevant verification.
8. Review the diff for correctness, security, unnecessary complexity, and unintended changes.
9. Report the actual result.

For complex changes, develop a concise internal implementation plan before editing.

For simple changes, proceed directly.

Do not repeatedly request approval for ordinary implementation details already covered by the task.

Do not stop at analysis when implementation is explicitly requested and is feasible.

Do not expand into unrelated modules or features.

## 10. Verification Standards

Verification must be proportional to the change.

Use the relevant checks:

- Compilation and Maven verification.
- Unit tests for domain rules.
- Integration tests for persistence and transactions.
- PostgreSQL Testcontainers for database behavior.
- Spring Modulith structure verification.
- Security tests for role and unit-scope boundaries.
- Concurrency and idempotency tests for sensitive operations.

Prefer testing observable behavior over implementation details.

Do not modify or weaken tests merely to make a failing build pass.

If a check cannot run, state the limitation and report it as **NOT VERIFIED**, not **PASS**.

Never invent test results, benchmark numbers, or execution evidence.

## 11. Change Control

Before modifying files, inspect existing behavior and preserve unrelated changes.

- Keep diffs focused on the active task.
- Avoid unnecessary renaming, package moves, and broad refactoring.
- Preserve public contracts unless the task authorizes changes.
- Document consequential architecture changes through the project's established decision process.
- Do not introduce dependencies without a concrete purpose.
- Do not create diagrams or documentation unrelated to the current implementation.

For OpsPilot, detailed architecture diagrams may be finalized after the corresponding subsystem has been implemented and verified.

Do not commit, push, merge, deploy, delete persistent data, or run destructive migrations without explicit authorization.

## 12. Completion Report

Report concisely in Vietnamese:

**Implemented:** What changed and why.  
**Files:** Relevant created or modified files.  
**Verification:** Commands executed and actual PASS/FAIL results.  
**Remaining:** Unverified items, limitations, or blockers.

Do not repeat the original task or generate lengthy summaries without a specific reason.

A task is complete only when its acceptance criteria are satisfied or the remaining limitations are explicitly reported.

## 13. Current Development Principle

**Build incrementally. Verify before expanding.**

The initial delivery focuses on a working backend foundation, followed by Organization, IAM, and subsequent business modules.

The active task defines the exact scope. This file must not be interpreted as authorization to implement future milestones.

Favor correct business behavior, clear module boundaries, maintainable code, and reproducible tests over unnecessary architectural sophistication.