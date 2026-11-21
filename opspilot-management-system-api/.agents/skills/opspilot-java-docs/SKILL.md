---
name: opspilot-java-docs
description: Use when verifying version-specific Java 25, Spring Boot 4, Spring Security 7.x, Hibernate, PostgreSQL 18, Flyway or Testcontainers APIs during OpsPilot coding or review.
---
# Context7 Bridge — Versioned Documentation

This is an instruction skill. The Context7 plugin/MCP runtime is separately installed from `upstash/context7`.

1. Determine **actual installed dependency versions** from pom.xml, parent/BOM and dependency tree; do not assume requested version is active.
2. When Context7 is available, resolve the library/version and query only the relevant API surface (e.g. SecurityFilterChain, NimbusJwtEncoder, JwtAuthenticationConverter, DelegatingPasswordEncoder, Spring transaction semantics).
3. Prefer official documentation and code for exact version. If Context7 lacks exact version, label evidence approximate and request confirmation before an incompatible change.
4. Keep external lookup queries free of source secrets, private tokens, user PII and internal-only Jira text.
5. Return concise API usage notes and links, no full copied documentation dumps.
6. Do not override Spring Boot managed dependencies without explicit need, compatibility evidence and tests.

If Context7 MCP is unavailable, report it and use locally available source/docs; never claim a tool ran when it did not.
