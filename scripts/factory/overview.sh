#!/usr/bin/env bash
# The overview of the pipeline, for "./factory status": the open issues
# grouped by who they wait for, rendered as plain text for the terminal
# from a snapshot of status.sh and the loop's state, with no model.
#
# The lines fit $COLUMNS, else the terminal's width, else 100 columns.
# FACTORY_NOW (an ISO 8601 time) replaces the current time, for tests.
#
# Usage: scripts/factory/overview.sh <snapshot.json>
set -euo pipefail
. "$(dirname "$0")/lib.sh"

render() {
    local snapshot=$1 loop=down job='null' paused=no width=${COLUMNS:-}
    if [[ -f $FACTORY_STATE/lock/pid ]] && kill -0 "$(cat "$FACTORY_STATE/lock/pid")" 2>/dev/null; then
        loop=up
    fi
    [[ -f $FACTORY_STATE/job.json ]] && job=$(cat "$FACTORY_STATE/job.json")
    [[ -f $FACTORY_STATE/paused ]] && paused=yes
    if [[ ! $width =~ ^[0-9]+$ ]]; then
        width=100
        if [[ -t 1 ]]; then
            width=$(tput cols 2>/dev/null || echo 100)
        fi
    fi
    jq -r --arg loop "$loop" --arg paused "$paused" --argjson job "$job" --argjson width "$width" \
        --arg now "${FACTORY_NOW:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}" '
        ($now | fromdateiso8601) as $now_s
        | def age($t): ($now_s - ($t | fromdateiso8601)) as $d
            | if $d < 3600 then "\([($d / 60 | floor), 0] | max)m"
              elif $d < 86400 then "\($d / 3600 | floor)h"
              else "\($d / 86400 | floor)d" end;
          def action:
            if .stage == "triaged" then "accept the triage"
            elif .stage == "spec-review" then "review the spec"
            elif .stage == "in-review" then "review the PR"
            elif .stage == "blocked" then "unblock"
            else .stage end;
          def pad($n): if length < $n then . + (" " * ($n - length)) else . end;
          def cut($n): if length > $n then .[0:$n - 1] + "…" else . end;
          # One line per row, in aligned columns: the second column holds
          # the title, shortened so that the line fits $width. Columns
          # empty on every row are left out.
          def lines($rows):
            if ($rows | length) == 0 then "  nothing" else
                [range($rows[0] | length) as $i | select($i == 1 or ([$rows[][$i] | length] | max) > 0) | $i] as $keep
                | [$rows[] | [.[$keep[]]]] as $rows
                | [range($rows[0] | length) as $i | [$rows[][$i] | length] | max] as $w
                | ([$width - 2 - 2 * (($w | length) - 1) - ($w | add) + $w[1], 10] | max) as $room
                | ([$w[1], $room] | min) as $tw
                | $rows[]
                | [to_entries[] | .key as $k | .value | if $k == 1 then cut($tw) | pad($tw) else pad($w[$k]) end]
                | "  " + join("  ") | sub(" +$"; "")
            end;
          .
        | .prs as $prs
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
        | ([$queued[] | select(.labels | index("priority:high"))] | length) as $high
        | ([$queued[] | select(.needs_spec)] | length) as $spec
        | "Updated \($now[0:16] | sub("T"; " ")) UTC · loop \($loop) · running: \(if $job then "\($job.kind) #\($job.issue)" else "nothing" end) · paused: \($paused)",
          "",
          "Waiting for David (\($david | length))",
          lines([$david[] | ["#\(.number)", .title, action, prs_text(.number), age(.updated_at)]]),
          "",
          "In flight (\($flight | length))",
          lines([$flight[] | ["#\(.number)", .title, (.stage + (if ($running == (.number | tostring)) then " (running)" else "" end)), prs_text(.number), age(.updated_at)]]),
          "",
          "Queued \($queued | length)\([(if $high > 0 then "\($high) high priority" else empty end), (if $spec > 0 then "\($spec) spec first" else empty end)] | if length > 0 then " (" + join(", ") + ")" else "" end) · To triage \($triage | length) · Needs info \($info | length)",
          (if ($invalid | length) > 0 then "", "Several stage labels (\($invalid | length))", lines([$invalid[] | ["#\(.number)", .title, (.stages | join(", ")), age(.updated_at)]]) else empty end)
    ' "$snapshot"
}

render "${1:?usage: overview.sh <snapshot.json>}"
