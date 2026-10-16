#!/usr/bin/env bash
# Creates or refreshes the factory's trusted checkout,
# .claude/worktrees/factory-main, detached at origin/<ref> (main unless
# FACTORY_REF says otherwise), and prints its path. The jobs' settings,
# hooks and scripts are taken from there: neither from a job's own
# branch, nor from David's main checkout, which may be dirty or stale.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

ref=${FACTORY_REF:-main}
dir=$FACTORY_HOME/.claude/worktrees/factory-main
git -C "$FACTORY_HOME" fetch -q origin "$ref"
if [[ -d $dir ]]; then
    git -C "$dir" checkout -q --detach --force "origin/$ref"
    git -C "$dir" clean -q -fdx -e .factory
else
    git -C "$FACTORY_HOME" worktree add -q --detach "$dir" "origin/$ref"
fi
echo "$dir"
