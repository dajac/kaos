#!/usr/bin/env bash
# Publishes screenshots for a PR: commits them to the orphan
# issue-screenshots branch, under pr-<PR>/, and prints one Markdown image
# per file. gh cannot upload attachments to a private repository, so the
# images live there, never merged.
#
# Usage: scripts/factory/publish-shots.sh <PR> <file.png>...
#
# Each PR writes only its own directory, so a push rejected because of a
# concurrent one is retried after a rebase, which cannot conflict.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

pr=${1:?usage: publish-shots.sh <PR> <file.png>...}
shift
(($#)) || die "usage: publish-shots.sh <PR> <file.png>..."
# A worktree of the factory's own, detached: it never touches a local
# issue-screenshots branch or worktree of David's sessions.
dir=$FACTORY_HOME/.claude/worktrees/factory-screenshots
git -C "$FACTORY_HOME" fetch -q origin "+issue-screenshots:refs/factory/issue-screenshots"
if [[ ! -d $dir ]]; then
    git -C "$FACTORY_HOME" worktree add -q --detach "$dir" refs/factory/issue-screenshots
else
    git -C "$dir" checkout -q --detach --force refs/factory/issue-screenshots
    git -C "$dir" clean -q -fd
fi
mkdir -p "$dir/pr-$pr"
for file in "$@"; do
    cp "$file" "$dir/pr-$pr/"
done
git -C "$dir" add "pr-$pr"
git -C "$dir" commit -q -m "Add the screenshots of #$pr" || info "no new screenshot"
for attempt in 1 2 3 4 5; do
    git -C "$dir" push -q origin HEAD:refs/heads/issue-screenshots && break
    ((attempt < 5)) || die "cannot push to issue-screenshots"
    sleep $((attempt * 2))
    git -C "$dir" fetch -q origin issue-screenshots
    git -C "$dir" rebase -q FETCH_HEAD
done
for file in "$@"; do
    base=$(basename "$file")
    echo "![${base%.png}](https://github.com/$FACTORY_REPO/blob/issue-screenshots/pr-$pr/$base?raw=true)"
done
