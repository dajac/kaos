#!/usr/bin/env bash
# Removes the local worktree and branch of a merged or closed PR's
# branch. The remote branch is deleted at the merge (./factory merge, or
# GitHub's auto-delete); the bot never deletes a remote branch.
#
# It touches only the factory's own branches, <N>-<slug> and
# spec/<N>-<slug>, never David's, and keeps a worktree with uncommitted
# changes.
#
# Usage: scripts/factory/cleanup.sh <branch>
set -euo pipefail
. "$(dirname "$0")/lib.sh"

branch=${1:?usage: cleanup.sh <branch>}
[[ $branch =~ ^(spec/)?[0-9]+-[a-z0-9-]+$ ]] || die "$branch is not a factory branch; left alone"
dir=$FACTORY_HOME/.claude/worktrees/${branch//\//-}
[[ -d $FACTORY_HOME/.claude/worktrees/$branch ]] && dir=$FACTORY_HOME/.claude/worktrees/$branch
if [[ -d $dir ]]; then
    if [[ -n $(git -C "$dir" status --porcelain) ]]; then
        die "$dir has uncommitted changes; left alone"
    fi
    git -C "$FACTORY_HOME" worktree remove "$dir"
    echo "removed the worktree $dir"
fi
if git -C "$FACTORY_HOME" show-ref --verify --quiet "refs/heads/$branch"; then
    git -C "$FACTORY_HOME" branch -D "$branch" >/dev/null
    echo "deleted the local branch $branch"
fi
