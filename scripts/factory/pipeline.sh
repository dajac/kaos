#!/usr/bin/env bash
# The Pipeline issue: a pinned issue whose body shows the whole pipeline,
# rendered from a snapshot of status.sh and the loop's state, with no
# model. Editing an issue body notifies no one.
#
# Usage: scripts/factory/pipeline.sh render <snapshot.json>   print the markdown
#        scripts/factory/pipeline.sh update <snapshot.json>   update the issue when it changed
#        scripts/factory/pipeline.sh ensure                   create and pin the issue if missing
set -euo pipefail
. "$(dirname "$0")/lib.sh"

number_file=$FACTORY_STATE/pipeline-issue

render() {
    local snapshot=$1 loop=down job='null' paused=no
    if [[ -f $FACTORY_STATE/lock/pid ]] && kill -0 "$(cat "$FACTORY_STATE/lock/pid")" 2>/dev/null; then
        loop=up
    fi
    [[ -f $FACTORY_STATE/job.json ]] && job=$(cat "$FACTORY_STATE/job.json")
    [[ -f $FACTORY_STATE/paused ]] && paused=yes
    jq -r --arg loop "$loop" --arg paused "$paused" --argjson job "$job" --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
        def ago($t): ((($now | fromdateiso8601) - ($t | fromdateiso8601)) / 60 | floor) as $m
            | if $m < 60 then "\($m) min" elif $m < 2880 then "\($m / 60 | floor) h" else "\($m / 1440 | floor) d" end;
        def link: "#\(.number) \(.title | gsub("\\|"; "/"))";
        def what:
            if .stage == "triaged" then "triage proposal to accept, comment on or close"
            elif .stage == "spec-review" then "spec PR to review"
            elif .stage == "in-review" then "PR ready for review"
            elif .stage == "blocked" then "blocked, see the last kaos-agent comment"
            else .stage end;
        def table($rows; $cols): if ($rows | length) == 0 then "_None._" else
            ("| " + ($cols | join(" | ")) + " |"), ("|" + ($cols | map("---") | join("|")) + "|"), ($rows[] | "| " + join(" | ") + " |")
            end;
        .prs as $prs
        | def pr_of($n): [$prs[] | select(.closes | index($n))] + [$prs[] | select(.head | test("^(spec/)?\($n)-"))] | unique_by(.number);
          def prs_text($n): [pr_of($n)[] | "#\(.number)\(if .draft then " (draft)" else "" end), CI \(.checks)"] | join("; ");
          .
        | .issues as $issues
        | ($job.issue // "") as $running
        | [$issues[] | select(.stage == "triaged" or .stage == "spec-review" or .stage == "in-review" or .stage == "blocked")] as $david
        | [$issues[] | select(.stage == "in-progress" or .stage == "reviewing" or .stage == "changes-requested")] as $flight
        | [$issues[] | select(.stage == "queued")] as $queued
        | [$issues[] | select(.stage == "needs-triage" or .stage == "none")] as $triage
        | [$issues[] | select(.stage == "needs-info")] as $info
        | [$issues[] | select(.stage == "invalid")] as $invalid
        | "Updated \($now[0:16] | sub("T"; " ")) UTC · loop \($loop) · running: \(if $job then "\($job.kind) #\($job.issue)" else "nothing" end) · paused: \($paused)",
          "",
          "This issue is the overview of the [software factory](../blob/main/docs/factory/README.md). kaos-agent rewrites it; do not edit it.",
          "",
          "## Waiting for David (\($david | length))",
          "",
          table([$david[] | [link, what, prs_text(.number), ago(.updated_at)]]; ["Issue", "What", "PRs", "Since"]),
          "",
          "## In flight (\($flight | length))",
          "",
          table([$flight[] | [link, (.stage + (if ($running == (.number | tostring)) then " (running)" else "" end)), prs_text(.number), ago(.updated_at)]]; ["Issue", "Stage", "PRs", "Since"]),
          "",
          "## Queued (\($queued | length))",
          "",
          table([$queued[] | [link, (if .needs_spec then "spec first" else "build" end), (if (.labels | index("priority:high")) then "high" else "" end), ago(.updated_at)]]; ["Issue", "Next", "Priority", "Since"]),
          "",
          "## To triage (\($triage | length)) · Needs info (\($info | length))",
          "",
          table([($triage + $info)[] | [link, .stage, ago(.created_at)]]; ["Issue", "Stage", "Opened"]),
          (if ($invalid | length) > 0 then "", "## Several stage labels (\($invalid | length))", "", table([$invalid[] | [link, (.stages | join(", "))]]; ["Issue", "Stages"]) else empty end)
    ' "$snapshot"
}

ensure() {
    local number id
    if [[ -f $number_file ]] && [[ $(gh issue view "$(cat "$number_file")" --repo "$FACTORY_REPO" --json state --jq .state 2>/dev/null) == OPEN ]]; then
        cat "$number_file"
        return
    fi
    number=$(gh issue list --repo "$FACTORY_REPO" --state open --label pipeline --json number --jq '.[0].number // empty')
    if [[ -z $number ]]; then
        number=$(gh issue create --repo "$FACTORY_REPO" --title Pipeline --label pipeline \
            --body "The overview of the software factory. kaos-agent fills it in." | sed -E 's#.*/##')
    fi
    id=$(gh issue view "$number" --repo "$FACTORY_REPO" --json id --jq .id)
    gh api graphql -f query='mutation($id: ID!) { pinIssue(input: {issueId: $id}) { issue { number } } }' -F id="$id" >/dev/null ||
        info "could not pin #$number; pin it by hand"
    echo "$number" >"$number_file"
    echo "$number"
}

case ${1:-} in
render) render "${2:?snapshot}" ;;
update)
    body=$(render "${2:?snapshot}")
    # The first line carries the time; compare the rest.
    if [[ ! -f $FACTORY_STATE/pipeline.md ]] || [[ $(tail -n +2 "$FACTORY_STATE/pipeline.md") != "$(tail -n +2 <<<"$body")" ]]; then
        gh issue edit "$(ensure)" --repo "$FACTORY_REPO" --body "$body" >/dev/null
        echo "$body" >"$FACTORY_STATE/pipeline.md"
    fi
    ;;
ensure) ensure ;;
*) die "usage: pipeline.sh render|update <snapshot.json> | ensure" ;;
esac
