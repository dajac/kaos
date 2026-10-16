#!/usr/bin/env bash
# The overview of the pipeline, for "./factory status": the open issues
# grouped by who they wait for, rendered as Markdown from a snapshot of
# status.sh and the loop's state, with no model.
#
# Usage: scripts/factory/overview.sh <snapshot.json>
set -euo pipefail
. "$(dirname "$0")/lib.sh"

render() {
    local snapshot=$1 loop=down job='null' paused=no
    if [[ -f $FACTORY_STATE/lock/pid ]] && kill -0 "$(cat "$FACTORY_STATE/lock/pid")" 2>/dev/null; then
        loop=up
    fi
    [[ -f $FACTORY_STATE/job.json ]] && job=$(cat "$FACTORY_STATE/job.json")
    [[ -f $FACTORY_STATE/paused ]] && paused=yes
    jq -r --arg loop "$loop" --arg paused "$paused" --argjson job "$job" --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
        def ago($t): $t | fromdateiso8601 | strftime("%b %d %H:%M");
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

render "${1:?usage: overview.sh <snapshot.json>}"
