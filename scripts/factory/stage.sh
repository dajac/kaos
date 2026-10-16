#!/usr/bin/env bash
# Moves an issue to another stage: the only way the factory changes a
# stage label. It knows the legal transitions and David's gates, and
# refuses anything else, so that a mistaken tick cannot jump a gate.
#
# Usage: scripts/factory/stage.sh <issue> <stage> [--spec-approved <PR>]
#
# The gates:
# - triaged -> queued, and queued -> spec-review or in-progress, need the
#   accepted label, added by David (checked on the issue's timeline);
# - queued -> in-progress needs no needs-spec;
# - spec-review -> queued needs --spec-approved with a spec PR whose
#   last review by David approves its head; it removes needs-spec.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

usage() {
    die "usage: stage.sh <issue> <stage> [--spec-approved <PR>]"
}

issue=${1:-}
to=${2:-}
[[ $issue =~ ^[0-9]+$ && -n $to ]] || usage
shift 2
spec_pr=
while (($#)); do
    case $1 in
    --spec-approved)
        spec_pr=${2:-}
        [[ $spec_pr =~ ^[0-9]+$ ]] || usage
        shift 2
        ;;
    *) usage ;;
    esac
done

stages=(needs-triage triaged needs-info queued spec-review in-progress reviewing in-review changes-requested blocked)
is_stage() {
    local s
    for s in "${stages[@]}"; do [[ $s == "$1" ]] && return 0; done
    return 1
}
is_stage "$to" || die "unknown stage '$to'"

# The legal transitions, "from to" pairs; "none" is an issue without a
# stage label. Any stage may go to blocked, and blocked may go back to
# any stage but needs-triage's predecessor "none".
legal() {
    local from=$1 to=$2
    [[ $to == blocked && $from != blocked ]] && return 0
    [[ $from == blocked ]] && return 0
    case "$from $to" in
    "none needs-triage" | \
        "needs-triage triaged" | "needs-triage needs-info" | \
        "needs-info needs-triage" | \
        "triaged queued" | "triaged needs-triage" | \
        "queued spec-review" | "queued in-progress" | \
        "spec-review queued" | \
        "in-progress reviewing" | \
        "reviewing in-progress" | "reviewing in-review" | \
        "in-review changes-requested" | "in-review in-progress" | \
        "changes-requested in-progress")
        return 0
        ;;
    esac
    return 1
}

view=$(gh issue view "$issue" --repo "$FACTORY_REPO" --json number,state,labels)
[[ $(jq -r .state <<<"$view") == OPEN ]] || die "#$issue is not open"
labels=$(jq -r '.labels[].name' <<<"$view")
has_label() {
    grep -qx -- "$1" <<<"$labels"
}

current=()
for s in "${stages[@]}"; do
    has_label "$s" && current+=("$s")
done
((${#current[@]} <= 1)) || die "#$issue has several stage labels: ${current[*]}; fix them by hand"
from=${current[0]:-none}
[[ $from != "$to" ]] || die "#$issue is already $to"
legal "$from" "$to" || die "#$issue: $from -> $to is not a legal transition"

# True when the accepted label is on the issue and the last time it was
# added, David added it.
accepted_by_david() {
    has_label accepted || return 1
    local actor
    actor=$(gh api "repos/$FACTORY_REPO/issues/$issue/timeline" --paginate |
        jq -rs '[.[][] | select(.event == "labeled" and .label.name == "accepted")] | last | .actor.login // empty')
    [[ $actor == "$FACTORY_DAVID" ]]
}

remove=("$from")
case "$from $to" in
"triaged queued" | "queued spec-review" | "queued in-progress")
    accepted_by_david || die "#$issue: $from -> $to needs the accepted label, added by $FACTORY_DAVID"
    ;;
esac
case "$from $to" in
"queued spec-review")
    has_label needs-spec || die "#$issue: queued -> spec-review needs the needs-spec label"
    ;;
"queued in-progress")
    has_label needs-spec && die "#$issue: queued -> in-progress needs an approved spec first (needs-spec is set)"
    ;;
"spec-review queued")
    [[ -n $spec_pr ]] || die "#$issue: spec-review -> queued needs --spec-approved <PR>"
    head=$(gh pr view "$spec_pr" --repo "$FACTORY_REPO" --json headRefOid | jq -r .headRefOid)
    review=$(gh api "repos/$FACTORY_REPO/pulls/$spec_pr/reviews" --paginate |
        jq -s --arg david "$FACTORY_DAVID" '[.[][] | select(.user.login == $david and (.state == "APPROVED" or .state == "CHANGES_REQUESTED"))] | last // empty')
    [[ -n $review && $(jq -r .state <<<"$review") == APPROVED && $(jq -r .commit_id <<<"$review") == "$head" ]] ||
        die "#$issue: the spec PR #$spec_pr is not approved by $FACTORY_DAVID on its head"
    remove+=(needs-spec)
    ;;
esac

args=(--add-label "$to")
for label in "${remove[@]}"; do
    [[ $label != none ]] && has_label "$label" && args+=(--remove-label "$label")
done
gh issue edit "$issue" --repo "$FACTORY_REPO" "${args[@]}" >/dev/null
echo "#$issue: $from -> $to"
