#!/usr/bin/env bash
# Takes a screenshot of a page with the local Chrome, headless: no
# dependency, no code to maintain. It shows a state, not an interaction:
# put the server in the state to show first, with a scenario.
#
# Usage: scripts/factory/capture.sh <url> <out.png> [--size 1440x900] [--wait-ms 6000] [--dark]
#
# --wait-ms leaves the UI time to receive its first snapshot over SSE:
# Chrome takes the shot when it stops the page after that time. (Its
# --virtual-time-budget would wait forever on the open SSE stream.)
set -euo pipefail

url=${1:?usage: capture.sh <url> <out.png> [--size WxH] [--wait-ms N] [--dark]}
out=${2:?usage: capture.sh <url> <out.png> [--size WxH] [--wait-ms N] [--dark]}
shift 2
size=1440x900
wait_ms=6000
theme=()
while (($#)); do
    case $1 in
    --size) size=${2:?}; shift 2 ;;
    --wait-ms) wait_ms=${2:?}; shift 2 ;;
    --dark) theme=(--force-dark-mode --blink-settings=preferredColorScheme=0); shift ;;
    *) echo "factory: unknown option $1" >&2; exit 2 ;;
    esac
done

chrome=${FACTORY_CHROME:-}
for candidate in "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" google-chrome chromium; do
    [[ -n $chrome ]] && break
    if [[ -x $candidate ]] || command -v "$candidate" >/dev/null 2>&1; then
        chrome=$candidate
    fi
done
[[ -n $chrome ]] || { echo "factory: Chrome not found" >&2; exit 1; }

# A profile of its own, so that it never touches David's Chrome.
profile=$(mktemp -d)
trap 'rm -rf "$profile"' EXIT
mkdir -p "$(dirname "$out")"
rm -f "$out"
"$chrome" --headless=new --disable-gpu --hide-scrollbars --no-first-run --no-default-browser-check \
    --user-data-dir="$profile" --window-size="${size/x/,}" --timeout="$wait_ms" \
    ${theme[@]+"${theme[@]}"} --screenshot="$out" "$url" >/dev/null 2>&1 &
pid=$!
deadline=$(($(date +%s) + wait_ms / 1000 + 30))
while kill -0 "$pid" 2>/dev/null; do
    [[ -s $out ]] && sleep 1 && break
    (($(date +%s) < deadline)) || break
    sleep 1
done
kill "$pid" 2>/dev/null || true
wait "$pid" 2>/dev/null || true
[[ -s $out ]] || { echo "factory: no screenshot of $url" >&2; exit 1; }
echo "$out"
