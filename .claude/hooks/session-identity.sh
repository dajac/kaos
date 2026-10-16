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
# The trusted copy: the factory's checkout of main for the jobs
# (FACTORY_TRUSTED), the main checkout otherwise; until the factory is
# merged, the main checkout has none and the session's own copy stands
# in.
identity=${FACTORY_TRUSTED:+$FACTORY_TRUSTED/scripts/factory/identity.sh}
[[ -n $identity && -f $identity ]] || identity=$home/scripts/factory/identity.sh
[[ -f $identity ]] || identity=$project/scripts/factory/identity.sh
[[ -f $identity ]] || exit 0
# The line runs in the user's shell, bash or zsh, which cannot always
# tell identity.sh where it lives: the hook says it.
printf 'FACTORY_IDENTITY_DIR=%q . %q\n' "$(cd "$(dirname "$identity")" && pwd)" "$identity" >>"$CLAUDE_ENV_FILE"
