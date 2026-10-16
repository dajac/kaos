#!/usr/bin/env bash
# David's merge of a PR: "./factory merge <PR>". Run by David in his own
# terminal, with his own gh login; Claude sessions are refused it by the
# guard hook, and it refuses to run with the bot's identity.
#
# It merges only a PR that is ready: not a draft, based on main, approved
# by David on its current head, with every review thread resolved and CI
# green. It squashes with the PR title and the part of the body above
# the "---" rule (the rest is review material), then deletes the branch
# unless another open PR is based on it.
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

unresolved=$(gh api graphql -F n="$pr" -F owner="${FACTORY_REPO%%/*}" -F name="${FACTORY_REPO#*/}" -f query='
    query($owner: String!, $name: String!, $n: Int!) {
      repository(owner: $owner, name: $name) {
        pullRequest(number: $n) { reviewThreads(first: 100) { nodes { isResolved } } }
      }
    }' --jq '[.data.repository.pullRequest.reviewThreads.nodes[] | select(.isResolved | not)] | length')
((unresolved == 0)) || die "#$pr has $unresolved unresolved review threads"

checks=$(gh pr checks "$pr" --repo "$FACTORY_REPO" --json name,bucket 2>/dev/null || echo '[]')
pending=$(jq -r '[.[] | select(.bucket != "pass" and .bucket != "skipping")] | map("\(.name): \(.bucket)") | join(", ")' <<<"$checks")
[[ -z $pending ]] || die "#$pr CI is not green: $pending"
[[ $(jq length <<<"$checks") -gt 0 ]] || die "#$pr has no CI checks"

title="$(jq -r .title <<<"$view") (#$pr)"
message=$(jq -r .body <<<"$view" | commit_message)

merged=$(gh api -X PUT "repos/$FACTORY_REPO/pulls/$pr/merge" -f merge_method=squash -f sha="$head" \
    -f commit_title="$title" -f commit_message="$message" --jq .sha)
author=$(gh api "repos/$FACTORY_REPO/commits/$merged" --jq '.commit.author.name + " <" + .commit.author.email + ">"')
info "merged #$pr as $merged, authored by $author"

if [[ -n $(gh pr list --repo "$FACTORY_REPO" --base "$branch" --state open --json number --jq '.[].number') ]]; then
    info "kept the branch $branch: open PRs are based on it"
else
    gh api -X DELETE "repos/$FACTORY_REPO/git/refs/heads/$branch" >/dev/null && info "deleted the branch $branch"
fi
