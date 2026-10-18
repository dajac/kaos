#!/usr/bin/env bash
# SessionStart hook of the factory's jobs: makes every later Bash
# command of the session run with the bot's identity, by writing a line
# that sources identity.sh to CLAUDE_ENV_FILE. Claude Code runs the
# file's contents before each command. identity.sh changes nothing until
# the bot exists. A job is a session that job.sh started, with
# FACTORY_TRUSTED set; an interactive session keeps David's login.
set -euo pipefail
[[ -n ${CLAUDE_ENV_FILE:-} && -n ${FACTORY_TRUSTED:-} ]] || exit 0
project=${CLAUDE_PROJECT_DIR:-$PWD}
home=$(dirname "$(git -C "$project" rev-parse --path-format=absolute --git-common-dir)")
# The trusted copy, the factory's checkout of main, only: a job never
# runs without it.
identity=$FACTORY_TRUSTED/scripts/factory/identity.sh
[[ -f $identity ]] || { echo "session-identity: no $identity" >&2; exit 1; }
# The line runs in the user's shell, bash or zsh, which cannot always
# tell identity.sh where it lives: the hook says it.
printf 'FACTORY_IDENTITY_DIR=%q . %q\n' "$(cd "$(dirname "$identity")" && pwd)" "$identity" >>"$CLAUDE_ENV_FILE"
