#!/usr/bin/env sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

set -a
for env_file in "$project_dir/.env" "$project_dir/.env.dev" "$project_dir/.env.local"; do
    if [ -f "$env_file" ]; then
        # These files are trusted local shell-compatible dotenv files.
        # shellcheck disable=SC1090
        . "$env_file"
    fi
done
set +a

cd "$project_dir"

if [ "$#" -eq 0 ]; then
    set -- "$project_dir/mvnw"
fi

exec "$@"
