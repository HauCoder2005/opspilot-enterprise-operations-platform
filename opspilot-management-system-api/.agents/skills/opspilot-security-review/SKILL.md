---
name: opspilot-security-review
description: Use when reviewing changes to authentication, authorization, Java Spring Security, JWT, refresh tokens, database row-level security, password handling, and privilege escalation in OpsPilot.
---
# OpsPilot Security Review

- Scope the exact Jira task; read only affected source and tests plus linked contracts.
- Verify unauthenticated 401 and unauthorized 403 consistently through established ApiResponse, including malformed/expired JWT.
- Password: hash encoding, dummy verify for nonexistent accounts, failure count commit, lockout expiry, timing/error-based enumeration, log redaction.
- JWT: issuer/audience/key/algorithm configuration, expiration/JTI/token_version checks, don't put mutable permissions in tokens, avoid hardcoded keys.
- Refresh token: cryptographically secure random, hash-only at rest, lock or concurrency guard for rotation, family reuse detection, revocation rules; distinguish web cookie CSRF implications from mobile clients.
- Provisioning: issuer privilege + unit scope + assignable role policies; never trust role/org supplied by client without authority check; protect last OWNER.
- PostgreSQL RLS: `opspilot_app` no BYPASSRLS; local org setting per transaction; test two tenants; prevent stale ThreadLocal context leakage; confirm current transaction boundaries.
- Audit: failure logs survive auth exceptions, success never persists for rolled-back write, masks credentials/PII.
- Error handling: concurrent requests, DB uniqueness, SQLState mapping, optimistic lock, status migration, employee termination.
- DoD statuses: PASS with file/line/test run, FAIL with reproduction, NOT VERIFIED if evidence absent, BLOCKED by OPEN decision.

Do not waive critical security controls to optimize token usage or LOC. Do not execute Git commands or alter Jira without authorization.
