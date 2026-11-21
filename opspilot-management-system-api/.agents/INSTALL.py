#!/usr/bin/env python3
"""Install OpsPilot instruction skills without overwriting existing project data.

Supports a kit extracted in the repository root OR one level below it.
Does not use Git, network or install Claude third-party plugins.
"""
from pathlib import Path
import argparse
import os
import shutil
import sys

HERE = Path(__file__).resolve().parent
SKILLS = HERE / 'skills'
TOOLING = HERE / 'tooling'
ROUTER_IMPORT = '@.agents/claude-tooling/CLAUDE-SKILLS-ROUTER.md'
KIT_SKILLS = (
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


def find_project(explicit):
    if explicit:
        return Path(explicit).expanduser().resolve()
    # Work in root when extracted directly, or detect root from nested kit.
    for candidate in [Path.cwd(), HERE, HERE.parent, HERE.parent.parent]:
        candidate = candidate.resolve()
        if (candidate / '.agents').is_dir() and any((candidate / mark).exists() for mark in ('pom.xml', 'database', 'docs', 'scripts')):
            return candidate
    return None


def copy_missing(src, dst):
    if dst.exists() or dst.is_symlink():
        print('SKIP existing: ' + str(dst))
        return False
    if src.is_dir():
        shutil.copytree(src, dst)
    else:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
    print('ADD: ' + str(dst))
    return True


def register_skill(src, dst):
    if dst.is_symlink():
        if dst.resolve() == src.resolve():
            print('OK already linked: ' + str(dst))
        else:
            print('SKIP existing different symlink: ' + str(dst))
        return
    if dst.exists():
        print('SKIP existing Claude skill: ' + str(dst))
        return
    try:
        dst.symlink_to(os.path.relpath(src, dst.parent), target_is_directory=True)
        print('LINK: ' + str(dst))
    except (OSError, NotImplementedError) as exc:
        shutil.copytree(src, dst)
        print('COPY: ' + str(dst) + ' (symlink unavailable: ' + str(exc) + ')')


def main():
    parser = argparse.ArgumentParser(description='Register OpsPilot Claude Code skills')
    parser.add_argument('--project', help='Path to opspilot-management-system-api repository root')
    args = parser.parse_args()
    project = find_project(args.project)
    if project is None or not (project/'.agents').is_dir() or not any((project/x).exists() for x in ('pom.xml','database','docs','scripts')):
        print('ERROR: Repository root not found. Extract this ZIP in the OpsPilot root and run python3 INSTALL.py there.')
        print('Or specify: python3 INSTALL.py --project /path/to/opspilot-management-system-api')
        return 2
    if not SKILLS.is_dir() or not TOOLING.is_dir():
        print('ERROR: kit missing skills/ or tooling/. Extract the complete ZIP; do not extract only README-FIRST.md.')
        return 3
    missing = [name for name in KIT_SKILLS if not (SKILLS / name / 'SKILL.md').is_file()]
    if missing:
        print('ERROR: Missing required kit skills:', ', '.join(missing))
        return 4
    sources = [SKILLS / name for name in KIT_SKILLS]
    print(f'Validated {len(sources)} kit skills. Other pre-existing skills will be preserved.')
    print('Target OpsPilot repository:',project)
    print('Source skill kit:',HERE)
    target_skills=project/'.agents'/'skills'
    target_skills.mkdir(parents=True,exist_ok=True)
    for item in sources:
        copy_missing(item, target_skills/item.name)
    target_tooling=project/'.agents'/'claude-tooling'
    target_tooling.mkdir(parents=True,exist_ok=True)
    for f in sorted(TOOLING.iterdir()):
        if f.is_file():
            copy_missing(f, target_tooling/f.name)
    claude_skills=project/'.claude'/'skills'
    claude_skills.mkdir(parents=True,exist_ok=True)
    # Register all valid OpsPilot skills found in the shared directory,
    # including skills that predate the kit. Never overwrite existing links.
    all_skills = sorted(
        p for p in target_skills.iterdir()
        if p.is_dir() and p.name.startswith('opspilot-') and (p/'SKILL.md').is_file()
    )
    for source in all_skills:
        register_skill(source, claude_skills/source.name)
    print(f'Registered/checked {len(all_skills)} OpsPilot skills for Claude Code.')
    claude_md=project/'CLAUDE.md'
    if claude_md.exists():
        text=claude_md.read_text(encoding='utf-8')
        if ROUTER_IMPORT not in text:
            prefix='' if text.endswith('\n') or not text else '\n'
            with claude_md.open('a',encoding='utf-8') as o:
                o.write(prefix+'\n## OpsPilot Skills Router\n'+ROUTER_IMPORT+'\n')
            print('APPEND router to CLAUDE.md (existing content preserved).')
        else:
            print('OK CLAUDE.md already imports router.')
    else:
        claude_md.write_text('# OpsPilot Claude Code\n\n'+ROUTER_IMPORT+'\n',encoding='utf-8')
        print('CREATED CLAUDE.md.')
    print('DONE. Plugin runtimes (Ponytail, Context7, Superpowers, JDTLS, Serena) are NOT installed by this script.')
    print('Verify with: python3 VERIFY.py --project "' + str(project) + '"')
    return 0

if __name__ == '__main__':
    sys.exit(main())
