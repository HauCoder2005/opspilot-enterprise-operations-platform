---
name: opspilot-jira-task
description: Use when analyzing or reviewing OpsPilot Jira issues KAN-*, checking dependencies and acceptance criteria, producing an implementation or test plan, or preparing a handoff.
---
# OpsPilot Jira Task Workflow

1. Read the specified KAN-* issue from the live Jira connector if available. If absent, clearly mark the version as unverified and use user-provided ticket text.
2. Resolve parent/child dependencies and exact acceptance criteria. Differentiate ticket status from implementation status.
3. Read actual code and tests before marking any condition PASS; no invented task progress.
4. Produce a concise matrix: requirement, owner module, evidence, status, test needed, blocker.
5. Check interactions with IAM/HR/ORG/AUDIT only if relevant; never silently expand the target ticket scope.
6. Use terms EXISTING, APPROVED, PROPOSED, OPEN, NOT VERIFIED accurately. Flag conflicts between live ticket, migrations and implementation.
7. Follow current agent rule: no Git operations, no `.git` access. No Jira write or status transition unless user authorizes it.
8. Close with implementation-ready handoff or review findings, not an unearned Done claim.
