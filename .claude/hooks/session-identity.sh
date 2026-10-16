#!/usr/bin/env bash
# SessionStart hook of every Claude session in Kaos: makes every later
# Bash command of the session run with the bot's identity, by writing a
# line that sources identity.sh to CLAUDE_ENV_FILE. Claude Code runs the
# file's contents before each command. identity.sh changes nothing until
# the bot exists.
set -euo pipefail
[[ -n ${CLAUDE_ENV_FILE:-} ]] || exit 0
project=${CLAUDE_PROJECT_DIR:-$PWD}
home=$(dirname "$(git -C "$project" rev-parse --path-format=absolute --git-common-dir)")
# The main checkout's copy is the trusted one; until the factory is
# merged, it has none and the session's own copy stands in.
identity=$home/scripts/factory/identity.sh
[[ -f $identity ]] || identity=$project/scripts/factory/identity.sh
[[ -f $identity ]] || exit 0
printf '. %q\n' "$identity" >>"$CLAUDE_ENV_FILE"
