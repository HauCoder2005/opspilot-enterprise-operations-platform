# Design-pattern decision guide (load only for nontrivial design/refactoring)

Choose the **simplest implementation that satisfies the change**. Pattern names are not acceptance criteria.

| Signal in actual requirements | Candidate | Avoid when |
| --- | --- | --- |
| Several interchangeable business algorithms or policies | Strategy (possibly Spring-injected implementations) | One stable branch or only two trivial cases |
| Creating different object types needs encapsulated construction rules | Factory | A constructor/static factory is sufficient |
| External SDK or framework details leak into business logic | Adapter / Port | A thin direct dependency already respects boundaries |
| Multiple independent handlers of a committed business event | Domain/application events (Spring Modulith) | Direct, synchronous call is simpler and correctly coupled |
| Query filters compose dynamically and are difficult to maintain | JPA Specification or focused query builder | Simple, fixed query; avoid generic filter engines |
| Repeated cross-cutting behavior with a safe interception boundary | Decorator / Spring advice | Ordinary method call or framework configuration suffices |
| Shared SQL-heavy reporting with stable, typed output | JdbcClient + DTO/record | Entity lifecycle and state changes are central; prefer JPA |

## Review before introducing any pattern

1. Name the change pressure or concrete duplication it removes.
2. Confirm module boundaries, dependency direction and testability improve.
3. Compare against a plain method/class or framework feature.
4. Add the fewest abstractions that solve the demonstrated problem.
5. Cover key business invariants with tests; avoid test suites coupled to implementation details.

Anti-patterns: interface-for-every-service, repository-for-every-table without domain need, giant base service, god-object, generic CRUD framework, speculative plugin system, overuse of events for ordinary control flow, moving business rules into controllers or SQL by convenience.
