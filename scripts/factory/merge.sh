#!/usr/bin/env bash
# The merge of a PR, in two modes:
#
# - "./factory merge <PR>": David's, in his own terminal, with his own gh
#   login. It refuses to run with the bot's identity.
# - "merge.sh --auto <PR>": the loop's merge pass, with the bot's
#   identity. It runs only there: the guard hook refuses merge.sh, with
#   or without --auto, in every Claude session.
#
# It merges only a PR that is ready: not a draft, based on main, approved
# by David on its current head, with every review thread resolved and CI
# green. It squashes with the PR title and the part of the body above
# the "---" rule (the rest is review material) and the co-authors of its
# commits, then retargets the PRs based on its branch to main and
# deletes the branch.
#
# --auto adds: the PR is the bot's, not a spec PR, and closes an issue in
# in-review; the approval carries no note and David wrote nothing after
# it (otherwise one comment per approval says why it waits); GitHub sees
# no conflict with main (on a conflict, the issue goes back to
# in-progress for the address job, with one comment per head). Exit
# codes of --auto: 0 merged, 3 held (the reason on stderr), 1 an error.
#
# GitHub does not let anyone approve their own PR, so a PR opened under
# David's handle (the factory's bootstrap PRs) cannot pass the approval
# gate; David merges those with the button.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

auto=false
if [[ ${1:-} == --auto ]]; then
    auto=true
    shift
fi
pr=${1:?usage: factory merge <PR>, or merge.sh --auto <PR>}

# Stops without merging: --auto holds the PR until a later poll, David's
# merge fails with the reason.
hold() {
    if $auto; then
        echo "factory: #$pr held: $*" >&2
        exit 3
    fi
    die "$*"
}

# Posts <body> on the PR unless a comment of the bot already carries
# <marker>, so that a poll repeats no comment.
comment_once() {
    local marker=$1 body=$2
    gh api "repos/$FACTORY_REPO/issues/$pr/comments" --paginate |
        jq -se --arg bot "$FACTORY_IDENTITY" --arg m "$marker" 'any(.[][]; .user.login == $bot and (.body | contains($m)))' >/dev/null &&
        return 0
    gh api "repos/$FACTORY_REPO/issues/$pr/comments" -f body="$body"$'\n\n'"$marker" >/dev/null
}

if $auto; then
    [[ -n ${FACTORY_IDENTITY:-} ]] || die "merge.sh --auto runs with the bot's identity, in the loop"
else
    [[ -z ${FACTORY_IDENTITY:-} ]] || die "this shell has the bot's identity; run ./factory merge in your own terminal"
    login=$(gh api user --jq .login)
    [[ $login == "$FACTORY_DAVID" ]] || die "gh is logged in as $login, not $FACTORY_DAVID"
fi

view=$(gh pr view "$pr" --repo "$FACTORY_REPO" \
    --json number,title,body,state,isDraft,baseRefName,headRefName,headRefOid,author,closingIssuesReferences)
[[ $(jq -r .state <<<"$view") == OPEN ]] || hold "#$pr is not open"
[[ $(jq -r .isDraft <<<"$view") == false ]] || hold "#$pr is a draft"
[[ $(jq -r .baseRefName <<<"$view") == main ]] || hold "#$pr is based on $(jq -r .baseRefName <<<"$view"), not main: merge its parent first"
head=$(jq -r .headRefOid <<<"$view")
branch=$(jq -r .headRefName <<<"$view")

if $auto; then
    [[ $(jq -r .author.login <<<"$view") == "app/${FACTORY_IDENTITY%\[bot\]}" ]] || hold "#$pr is not the bot's"
    [[ $branch != spec/* ]] || hold "#$pr is a spec PR"
    issue=$(jq -r '[.closingIssuesReferences[]?.number] | first // empty' <<<"$view")
    [[ -n $issue ]] || hold "#$pr closes no issue"
    gh issue view "$issue" --repo "$FACTORY_REPO" --json labels --jq '.labels[].name' | grep -qx in-review ||
        hold "#$issue is not in-review"
fi

# David's last review must approve the current head.
reviews=$(gh api "repos/$FACTORY_REPO/pulls/$pr/reviews" --paginate | jq -s '[.[][]]')
last_review=$(jq --arg david "$FACTORY_DAVID" '[.[] | select(.user.login == $david and (.state == "APPROVED" or .state == "CHANGES_REQUESTED"))] | last // empty' <<<"$reviews")
[[ -n $last_review && $(jq -r .state <<<"$last_review") == APPROVED ]] || hold "#$pr is not approved by $FACTORY_DAVID"
[[ $(jq -r .commit_id <<<"$last_review") == "$head" ]] || hold "#$pr has commits after the approval; approve its current head"

# An approval with a note, or anything David wrote after it, may carry a
# request that must be handled before the merge: --auto holds the PR and
# says so once per approval. David's own merge is his decision.
if $auto; then
    review_id=$(jq -r .id <<<"$last_review")
    approved_at=$(jq -r .submitted_at <<<"$last_review")
    note=
    jq -e '(.body // "") | gsub("^\\s+|\\s+$"; "") | ascii_downcase | test("^((lgtm|looks good|looks good to me|ship it)[.!]?)?$")' \
        <<<"$last_review" >/dev/null || note="the approval has a note"
    if [[ -z $note ]]; then
        later=$(jq --arg david "$FACTORY_DAVID" --arg at "$approved_at" --argjson id "$review_id" \
            '[.[] | select(.user.login == $david and .id != $id and .submitted_at > $at)] | length' <<<"$reviews")
        later=$((later + $(gh api "repos/$FACTORY_REPO/pulls/$pr/comments" --paginate |
            jq -s --arg david "$FACTORY_DAVID" --arg at "$approved_at" --argjson id "$review_id" \
                '[.[][] | select(.user.login == $david and (.pull_request_review_id == $id or .created_at > $at))] | length')))
        for n in "$pr" "$issue"; do
            later=$((later + $(gh api "repos/$FACTORY_REPO/issues/$n/comments" --paginate |
                jq -s --arg david "$FACTORY_DAVID" --arg at "$approved_at" '[.[][] | select(.user.login == $david and .created_at > $at)] | length')))
        done
        ((later == 0)) || note="$FACTORY_DAVID wrote after the approval"
    fi
    if [[ -n $note ]]; then
        comment_once "<!-- factory-merge-held: $review_id -->" \
            "Approved, but not merged automatically: $note. Once it is handled, run \`./factory merge $pr\`, or approve again without a note."
        hold "$note"
    fi
fi

pull=$(gh api graphql -F n="$pr" -F owner="${FACTORY_REPO%%/*}" -F name="${FACTORY_REPO#*/}" -f query='
    query($owner: String!, $name: String!, $n: Int!) {
      repository(owner: $owner, name: $name) {
        pullRequest(number: $n) { mergeable reviewThreads(first: 100) { totalCount nodes { isResolved } } }
      }
    }' | jq '.data.repository.pullRequest')
threads=$(jq .reviewThreads <<<"$pull")
(($(jq .totalCount <<<"$threads") <= 100)) || hold "#$pr has more than 100 review threads; check them by hand"
unresolved=$(jq '[.nodes[] | select(.isResolved | not)] | length' <<<"$threads")
((unresolved == 0)) || hold "#$pr has $unresolved unresolved review threads"

# The exit code of gh pr checks depends on the state of the checks; the
# JSON is what counts, and no JSON means gh could not read them.
checks=$(gh pr checks "$pr" --repo "$FACTORY_REPO" --json name,bucket) || true
jq -e 'type == "array"' <<<"${checks:-null}" >/dev/null 2>&1 || die "cannot read the checks of #$pr"
pending=$(jq -r '[.[] | select(.bucket != "pass" and .bucket != "skipping")] | map("\(.name): \(.bucket)") | join(", ")' <<<"$checks")
[[ -z $pending ]] || hold "#$pr CI is not green: $pending"
[[ $(jq length <<<"$checks") -gt 0 ]] || hold "#$pr has no CI checks"

# GitHub computes the mergeability asynchronously: UNKNOWN waits for the
# next poll. A conflict sends the issue back to in-progress, where the
# tick starts an address job that merges main into the branch; David
# then approves the new head.
if $auto; then
    case $(jq -r '.mergeable // "UNKNOWN"' <<<"$pull") in
    MERGEABLE) ;;
    CONFLICTING)
        "$(dirname "$0")/stage.sh" "$issue" in-progress >&2
        comment_once "<!-- factory-merge-conflict: $head -->" \
            "Approved, but not merged: the branch conflicts with \`main\`. The address job merges \`main\` into it; review the new head and approve it again."
        hold "#$pr conflicts with main; #$issue goes back to in-progress"
        ;;
    *) hold "the mergeability of #$pr is not computed yet" ;;
    esac
fi

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
