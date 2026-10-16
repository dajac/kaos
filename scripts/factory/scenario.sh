#!/usr/bin/env bash
# Drives a running Kaos server to a known state: runs the steps of a
# scenario file against its HTTP API.
#
# Usage: scripts/factory/scenario.sh <base-url> <scenario.json>
#
# A scenario is a JSON object with an optional "include" (another
# scenario of the same directory, run first) and "steps", each one of:
#   {"request": "METHOD /path", "body": {...}}   expects a 2xx answer
#   {"wait": {"path": "/path", "jq": "<filter>", "timeout_s": 60}}
#                                                waits until the filter of
#                                                GET /path is true
#   {"sleep_s": 30}
set -euo pipefail

url=${1:?usage: scenario.sh <base-url> <scenario.json>}
file=${2:?usage: scenario.sh <base-url> <scenario.json>}
[[ -f $file ]] || { echo "factory: no scenario $file" >&2; exit 1; }

include=$(jq -r '.include // empty' "$file")
if [[ -n $include ]]; then
    "$0" "$url" "$(dirname "$file")/$include"
fi

jq -c '.steps[]' "$file" | while IFS= read -r step; do
    if jq -e 'has("request")' <<<"$step" >/dev/null; then
        read -r method path <<<"$(jq -r .request <<<"$step")"
        body=$(jq -c '.body // empty' <<<"$step")
        args=(-sS -o /dev/null -w '%{http_code}' -X "$method")
        [[ -n $body ]] && args+=(-H 'Content-Type: application/json' -d "$body")
        code=$(curl "${args[@]}" "$url$path")
        [[ $code == 2* ]] || { echo "factory: $method $path answered $code" >&2; exit 1; }
        echo "scenario: $method $path: $code"
    elif jq -e 'has("wait")' <<<"$step" >/dev/null; then
        path=$(jq -r .wait.path <<<"$step")
        filter=$(jq -r .wait.jq <<<"$step")
        deadline=$(($(date +%s) + $(jq -r '.wait.timeout_s // 60' <<<"$step")))
        until curl -sSf "$url$path" 2>/dev/null | jq -e "$filter" >/dev/null 2>&1; do
            (($(date +%s) < deadline)) || { echo "factory: timed out waiting for $filter on $path" >&2; exit 1; }
            sleep 1
        done
        echo "scenario: $path: $filter"
    elif jq -e 'has("sleep_s")' <<<"$step" >/dev/null; then
        sleep "$(jq -r .sleep_s <<<"$step")"
        echo "scenario: slept $(jq -r .sleep_s <<<"$step") s"
    else
        echo "factory: unknown step $step" >&2
        exit 1
    fi
done
