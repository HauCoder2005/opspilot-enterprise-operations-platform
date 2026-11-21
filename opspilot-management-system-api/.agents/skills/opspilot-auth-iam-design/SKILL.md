---
name: opspilot-auth-iam-design
description: Use when designing or assessing OpsPilot authentication, account creation by a manager, employee provisioning, role assignment, multi-tenant authorization, or integration across AUTH IAM ORG HR.
---
# OpsPilot — Identity, Auth & IAM Design

1. Retrieve task-specific scope from Jira if connected; otherwise request the task description. Read current source and actual migrations before proposing changes.
2. Model the distinct flows: tenant onboarding + first OWNER (KAN-95); admin user creation (KAN-85); HR employee create-account (KAN-89); role assign/revoke with scoped permission (KAN-87); login (KAN-75); JWT (KAN-76); refresh/logout (KAN-77); forced password change (KAN-78); termination/disable and session invalidation.
3. Authentication answers who the caller is; IPolicyService answers permission + scope + business rule. Org hierarchy does not automatically grant permission. Keep workflows separate instead of growing one gigantic AuthService.
4. Check user management: creator has `iam.user.manage` on applicable unit, role grant uses `iam.role.assign`, no privilege escalation, no self-lockout, no last OWNER removal, no cross-tenant IDs.
5. DB invariant: use `opspilot_app` with RLS, transaction-local `set_config('opspilot.org_id', ..., true)` before querying tenant-scoped tables. Before login, resolve org through approved SECURITY DEFINER function.
6. Lockout failures must commit failed-count / audit correctly, even when HTTP response is 401; check simultaneous failures and expiry behavior.
7. Password first-login must force change; use `DelegatingPasswordEncoder` hashes with correct prefix and protect temporary credentials.
8. Split DB write atomicity from external notification delivery. Do not claim atomicity across email/SMS without an outbox or clear boundary.
9. Identify OPEN decisions instead of inferring: one employee/multiple units, privileged role grant matrix, account invitation/method, web refresh cookie/mobile token handling, auth status disclosure policy.
10. Output per flow: actor, precondition, decision/policy, happy path, failure path, audit, transactional boundary, database rows, API request/response, test matrix.

**Never code without authorization.** Acceptance criteria must map to real tests. Existing code and migration schema must be inspected before naming actual columns/classes.
