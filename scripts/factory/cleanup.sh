#!/usr/bin/env bash
# Removes the local worktree and branch of a merged or closed PR's
# branch. The remote branch is deleted at the merge (./factory merge, or
# GitHub's auto-delete); the bot never deletes a remote branch.
#
# Usage: scripts/factory/cleanup.sh <branch>
set -euo pipefail
. "$(dirname "$0")/lib.sh"

branch=${1:?usage: cleanup.sh <branch>}
[[ $branch != main && $branch != issue-screenshots ]] || die "never cleans up $branch"
dir=$FACTORY_HOME/.claude/worktrees/${branch//\//-}
[[ -d $FACTORY_HOME/.claude/worktrees/$branch ]] && dir=$FACTORY_HOME/.claude/worktrees/$branch
if [[ -d $dir ]]; then
    git -C "$FACTORY_HOME" worktree remove --force "$dir"
    echo "removed the worktree $dir"
fi
if git -C "$FACTORY_HOME" show-ref --verify --quiet "refs/heads/$branch"; then
    git -C "$FACTORY_HOME" branch -D "$branch" >/dev/null
    echo "deleted the local branch $branch"
fi
