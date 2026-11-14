---
name: opspilot-code-review
description: Review, debug or assess a concrete OpsPilot backend diff, failure or subsystem for correctness, security, transactions and module boundaries. Use for explicit review/debug requests; not for routine feature implementation or style-only reformatting.
---

# OpsPilot focused review

Review only the requested change and relevant call paths, tests and contracts. Use `../../../AGENTS.md` and approved decisions. Prefer observable defects to style opinions.

## Review workflow

1. Establish the change scope (`git diff`, affected files, target behavior) and baseline. Do not read unrelated modules without a reason.
2. Trace the risk-bearing paths: authorization (identity, permission, org/unit scope), database transaction and concurrency, module boundaries, error contracts, event reliability, and rollback/retry as applicable.
3. Seek concrete evidence: specific code location, triggering scenario, violated invariant and likely impact. Distinguish a verified defect from a hypothesis requiring a test.
4. Run the smallest relevant tests or a focused reproduction when feasible. Do not silently weaken existing tests. If the user asked for fixes, apply the narrowest fix and verify; otherwise provide findings without unrelated edits.
5. Stop once material risks are covered; avoid speculative optimization and repeated full-suite runs.

## Findings contract

Order by severity (Critical, High, Medium, Low). For each actionable finding include:
`[Severity] path:line — defect; reproduction/trigger; impact; minimal fix/test.`

Conclude in Vietnamese with a short verdict, tests actually run and **only material** unverified risks. If no substantive findings, state that explicitly without claiming the code is proven bug-free. Default to **at most 12 lines**; expand only when there are several independently material findings or the user requests detail.
