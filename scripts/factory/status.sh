#!/usr/bin/env bash
# Prints the snapshot of the pipeline as one JSON document: the open
# issues with their stage, the open PRs with their CI, and the PRs
# merged or closed in the last two days. Plain gh and jq, no model: the
# loop compares two snapshots to decide whether a tick is needed.
#
# Usage: scripts/factory/status.sh
set -euo pipefail
. "$(dirname "$0")/lib.sh"

stages='["needs-triage","triaged","needs-info","queued","spec-review","in-progress","reviewing","in-review","changes-requested","blocked"]'
since=$(jq -rn "now - 172800 | strftime(\"%Y-%m-%d\")")

issues=$(gh issue list --repo "$FACTORY_REPO" --state open --limit 500 \
    --json number,title,author,labels,updatedAt,createdAt)
prs=$(gh pr list --repo "$FACTORY_REPO" --state open --limit 200 \
    --json number,title,author,headRefName,baseRefName,isDraft,updatedAt,closingIssuesReferences,statusCheckRollup,reviewDecision)
done_prs=$(gh pr list --repo "$FACTORY_REPO" --state all --limit 100 --search "closed:>=$since" \
    --json number,title,author,headRefName,baseRefName,state,mergedAt,closedAt,closingIssuesReferences)

jq -n --argjson issues "$issues" --argjson prs "$prs" --argjson done "$done_prs" --argjson stages "$stages" '
    def checks:
        if (. // []) | length == 0 then "none"
        elif any(.[]; (.conclusion // .state) as $s | $s == "FAILURE" or $s == "ERROR" or $s == "CANCELLED" or $s == "TIMED_OUT" or $s == "ACTION_REQUIRED" or $s == "STARTUP_FAILURE" or $s == "STALE") then "fail"
        elif any(.[]; (.status // "COMPLETED") != "COMPLETED" or .state == "PENDING" or .state == "EXPECTED") then "pending"
        else "pass" end;
    {
        issues: [$issues[]
            | select(any(.labels[]; .name == "pipeline") | not)
            | ([.labels[].name]) as $labels
            | {
                number, title,
                author: .author.login,
                labels: $labels,
                stages: [$labels[] | select(. as $l | $stages | index($l))],
                accepted: ($labels | index("accepted") != null),
                needs_spec: ($labels | index("needs-spec") != null),
                updated_at: .updatedAt,
                created_at: .createdAt
            }
            | .stage = (if (.stages | length) == 1 then .stages[0] elif (.stages | length) == 0 then "none" else "invalid" end)
        ] | sort_by(.number),
        prs: [$prs[] | {
            number, title,
            author: .author.login,
            head: .headRefName, base: .baseRefName,
            draft: .isDraft,
            closes: [.closingIssuesReferences[]?.number],
            checks: (.statusCheckRollup | checks),
            review: .reviewDecision,
            updated_at: .updatedAt
        }] | sort_by(.number),
        closed_prs: [$done[] | select(.state != "OPEN") | {
            number, author: .author.login, head: .headRefName, base: .baseRefName,
            merged: (.mergedAt != null),
            closed_at: .closedAt,
            closes: [.closingIssuesReferences[]?.number]
        }] | sort_by(.number)
    }'
