#!/usr/bin/env python3
"""Verify registration of OpsPilot skill instructions (not plugin runtimes)."""
import argparse
from pathlib import Path
import sys

HERE=Path(__file__).resolve().parent
ROUTER='@.agents/claude-tooling/CLAUDE-SKILLS-ROUTER.md'
KIT_SKILLS=(
    'opspilot-auth-iam-design',
    'opspilot-java-docs',
    'opspilot-java-navigation',
    'opspilot-jira-task',
    'opspilot-ponytail-lean',
    'opspilot-project-memory',
    'opspilot-security-review',
    'opspilot-superpowers-tdd',
    'opspilot-token-budget',
)

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--project')
    a=ap.parse_args()
    if a.project:
        repo=Path(a.project).expanduser().resolve()
    else:
        candidates=[Path.cwd(),HERE,HERE.parent,HERE.parent.parent]
        repo=next((p.resolve() for p in candidates if (p/'.agents').is_dir() and any((p/x).exists() for x in ('pom.xml','database','docs','scripts'))),None)
    if not repo:
        print('ERROR: No project found. Pass --project /path/to/repository');return 2
    expected=KIT_SKILLS
    good=0
    for name in expected:
        master=repo/'.agents'/'skills'/name/'SKILL.md'
        claude=repo/'.claude'/'skills'/name/'SKILL.md'
        status='PASS' if master.is_file() and claude.is_file() else 'MISSING'
        good+=int(status=='PASS')
        print(f'{status}: {name}')
    claude_md=repo/'CLAUDE.md'
    ok=claude_md.is_file() and ROUTER in claude_md.read_text(encoding='utf-8')
    print(('PASS' if ok else 'MISSING')+': CLAUDE.md import')
    all_skills = sorted(p.name for p in (repo/'.agents'/'skills').iterdir()
                        if p.is_dir() and p.name.startswith('opspilot-') and (p/'SKILL.md').is_file())
    extras = [name for name in all_skills if name not in KIT_SKILLS]
    for name in extras:
        linked = (repo/'.claude'/'skills'/name/'SKILL.md').is_file()
        print(('PASS' if linked else 'OPTIONAL UNLINKED') + ': existing ' + name)
    print(f'RESULT: {good}/{len(expected)} kit skills, {sum((repo/".claude"/"skills"/name/"SKILL.md").is_file() for name in extras)}/{len(extras)} existing skills, router={ok}.')
    print('This verifies local SKILL.md files only. Check /plugin and /mcp inside Claude Code separately.')
    return 0 if good==len(expected) and ok else 1

if __name__=='__main__':sys.exit(main())
