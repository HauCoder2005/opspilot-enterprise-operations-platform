# Runtime plugin installation: distinct from Skills

The ZIP only adds OpsPilot instructions and launcher links. Plugins below are installed by the user's real Claude Code runtime, typically in a user/plugin registry, **not** by copying source into `.agents/skills`.

## Prerequisites

- Run `claude --version` from terminal and update if skill symlink discovery is unavailable. Directory symlink support was documented for Claude Code 2.1.203+.
- Check `node --version` for Ponytail's lifecycle hooks.
- For Java LSP, ensure `jdtls` executable exists and supports the Java version in use: `command -v jdtls` (Linux) or `where jdtls` (Windows).
- Do not install unreviewed hooks in an enterprise repository or grant unrestricted execution permissions.

## Install with interactive commands *inside* Claude Code

Send each of the following separately (do not paste entire block as one slash command):

```text
/plugin marketplace add DietrichGebert/ponytail
/plugin install ponytail@ponytail
/plugin marketplace add upstash/context7
/plugin install context7@context7-marketplace
/plugin marketplace add anthropics/claude-plugins-official
/plugin install superpowers@claude-plugins-official
/plugin install jdtls-lsp@claude-plugins-official
/plugin install serena@claude-plugins-official
```

Installation availability, names and scopes can change: verify `/plugin` and the authoritative upstream docs for any error. Prefer personal scope when using one machine across projects; project scope only if a project owner explicitly decides to share plugin configuration.

**Recommended activation order:** Ponytail + Context7 + Superpowers, then JDTLS. Serena can be left disabled until code-navigation benchmarks show it adds value; installing overlapping LSP/MCP does not automatically improve reasoning and adds context cost.

## Verify

Inside Claude Code: `/plugin`, `/mcp`, `/memory`, `/context`, `/doctor`. For normal docs lookup ask Context7 for the installed Spring Security release's `SecurityFilterChain` / `JwtAuthenticationConverter` docs, quoting the version it actually found.

## Security notes

- Review Ponytail hooks and the permissions they request. Do not allow them to access credentials or auto-run prohibited Git commands.
- Superpowers may suggest branching or worktrees. **Do not run** them under OpsPilot's current no-Git agent rule; use its planning/TDD/debugging skills without Git operations.
- Context7 connects to an external service. Do not paste sensitive source code, secrets or internal Jira content into external doc queries.
- Serena requires local service/exec permissions. Verify the plugin is actually active before asking Claude to call its tools.
- `CLAUDE.md`/skills are instruction files, not hard security boundaries; system permissions, branch protections, and test gates remain necessary.

## References

- https://github.com/DietrichGebert/ponytail
- https://github.com/upstash/context7/blob/master/plugins/claude/context7/README.md
- https://github.com/obra/superpowers
- https://claude.com/plugins/jdtls-lsp
- https://claude.com/plugins/serena
- https://code.claude.com/docs/en/discover-plugins
