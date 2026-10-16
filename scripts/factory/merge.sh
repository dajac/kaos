#!/usr/bin/env bash
# David's merge of a PR: "./factory merge <PR>". Run by David in his own
# terminal, with his own gh login; Claude sessions are refused it by the
# guard hook, and it refuses to run with the bot's identity.
#
# It merges only a PR that is ready: not a draft, based on main, approved
# by David on its current head, with every review thread resolved and CI
# green. It squashes with the PR title and the part of the body above
# the "---" rule (the rest is review material) and the co-authors of its
# commits, then retargets the PRs based on its branch to main and
# deletes the branch.
#
# GitHub does not let anyone approve their own PR, so a PR opened under
# David's handle (the factory's bootstrap PRs) cannot pass the approval
# gate; David merges those with the button.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

pr=${1:?usage: factory merge <PR>}
[[ -z ${FACTORY_IDENTITY:-} ]] || die "this shell has the bot's identity; run ./factory merge in your own terminal"
login=$(gh api user --jq .login)
[[ $login == "$FACTORY_DAVID" ]] || die "gh is logged in as $login, not $FACTORY_DAVID"

view=$(gh pr view "$pr" --repo "$FACTORY_REPO" --json number,title,body,state,isDraft,baseRefName,headRefName,headRefOid)
[[ $(jq -r .state <<<"$view") == OPEN ]] || die "#$pr is not open"
[[ $(jq -r .isDraft <<<"$view") == false ]] || die "#$pr is a draft"
[[ $(jq -r .baseRefName <<<"$view") == main ]] || die "#$pr is based on $(jq -r .baseRefName <<<"$view"), not main: merge its parent first"
head=$(jq -r .headRefOid <<<"$view")
branch=$(jq -r .headRefName <<<"$view")

# David's last review must approve the current head.
last_review=$(gh api "repos/$FACTORY_REPO/pulls/$pr/reviews" --paginate |
    jq -s --arg david "$FACTORY_DAVID" '[.[][] | select(.user.login == $david and (.state == "APPROVED" or .state == "CHANGES_REQUESTED"))] | last // empty')
[[ -n $last_review && $(jq -r .state <<<"$last_review") == APPROVED ]] || die "#$pr is not approved by $FACTORY_DAVID"
[[ $(jq -r .commit_id <<<"$last_review") == "$head" ]] || die "#$pr has commits after the approval; approve its current head"

threads=$(gh api graphql -F n="$pr" -F owner="${FACTORY_REPO%%/*}" -F name="${FACTORY_REPO#*/}" -f query='
    query($owner: String!, $name: String!, $n: Int!) {
      repository(owner: $owner, name: $name) {
        pullRequest(number: $n) { reviewThreads(first: 100) { totalCount nodes { isResolved } } }
      }
    }' | jq '.data.repository.pullRequest.reviewThreads')
(($(jq .totalCount <<<"$threads") <= 100)) || die "#$pr has more than 100 review threads; check them by hand"
unresolved=$(jq '[.nodes[] | select(.isResolved | not)] | length' <<<"$threads")
((unresolved == 0)) || die "#$pr has $unresolved unresolved review threads"

# The exit code of gh pr checks depends on the state of the checks; the
# JSON is what counts, and no JSON means gh could not read them.
checks=$(gh pr checks "$pr" --repo "$FACTORY_REPO" --json name,bucket) || true
jq -e 'type == "array"' <<<"${checks:-null}" >/dev/null 2>&1 || die "cannot read the checks of #$pr"
pending=$(jq -r '[.[] | select(.bucket != "pass" and .bucket != "skipping")] | map("\(.name): \(.bucket)") | join(", ")' <<<"$checks")
[[ -z $pending ]] || die "#$pr CI is not green: $pending"
[[ $(jq length <<<"$checks") -gt 0 ]] || die "#$pr has no CI checks"

title=$(jq -r .title <<<"$view")
[[ $title == *"(#$pr)" ]] || title="$title (#$pr)"
message=$(jq -r .body <<<"$view" | commit_message)
# The squash keeps the co-authors of the PR's commits: Claude, with the
# model each job ran on.
coauthors=$(gh api "repos/$FACTORY_REPO/pulls/$pr/commits" --paginate --jq '.[].commit.message' |
    grep -iE '^Co-Authored-By: ' | sort -fu || true)
while IFS= read -r line; do
    [[ -z $line ]] && continue
    grep -qixF -- "$line" <<<"$message" && continue
    if [[ -n $message ]]; then
        [[ $message == *$'\n'[Cc]o-[Aa]uthored-[Bb]y:* || $message == [Cc]o-[Aa]uthored-[Bb]y:* ]] ||
            message+=$'\n'
        message+=$'\n'"$line"
    else
        message=$line
    fi
done <<<"$coauthors"

merged=$(gh api -X PUT "repos/$FACTORY_REPO/pulls/$pr/merge" -f merge_method=squash -f sha="$head" \
    -f commit_title="$title" -f commit_message="$message" --jq .sha)
author=$(gh api "repos/$FACTORY_REPO/commits/$merged" --jq '.commit.author.name + " <" + .commit.author.email + ">"')
info "merged #$pr as $merged, authored by $author"

# GitHub closes the PRs whose base branch is deleted through the API (it
# retargets them only for its own delete-on-merge): move them to main
# first.
for child in $(gh pr list --repo "$FACTORY_REPO" --base "$branch" --state open --json number --jq '.[].number'); do
    gh pr edit "$child" --repo "$FACTORY_REPO" --base main >/dev/null
    info "retargeted #$child to main"
done
if gh api -X DELETE "repos/$FACTORY_REPO/git/refs/heads/$branch" >/dev/null 2>&1; then
    info "deleted the branch $branch"
else
    info "the branch $branch was already gone"
fi
