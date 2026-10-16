#!/usr/bin/env bash
# Takes a screenshot of a page with the local Chrome, headless: no
# dependency, no code to maintain. It shows a state, not an interaction:
# put the server in the state to show first, with a scenario.
#
# Usage: scripts/factory/capture.sh <url> <out.png> [--size 1440x900] [--wait-ms 6000]
#
# Chrome's headless screenshot is taken at the page's load event, before
# the UI has received its first snapshot over SSE; its
# --virtual-time-budget would wait forever on the open SSE stream, and
# its --timeout does not delay the shot. So Chrome loads a local wrapper
# page instead: the UI in a full-size frame, and a hidden image that a
# one-shot local server answers only after --wait-ms. The load event, and
# so the shot, wait for that image.
set -euo pipefail

url=${1:?usage: capture.sh <url> <out.png> [--size WxH] [--wait-ms N]}
out=${2:?usage: capture.sh <url> <out.png> [--size WxH] [--wait-ms N]}
shift 2
size=1440x900
wait_ms=6000
while (($#)); do
    case $1 in
    --size) size=${2:?}; shift 2 ;;
    --wait-ms) wait_ms=${2:?}; shift 2 ;;
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

work=$(mktemp -d)
pids=()
cleanup() {
    local pid
    for pid in ${pids[@]+"${pids[@]}"}; do
        kill "$pid" 2>/dev/null || true
    done
    rm -rf "$work"
}
trap cleanup EXIT

port=18900
while nc -z localhost "$port" 2>/dev/null; do
    port=$((port + 1))
done
(
    sleep "$((wait_ms / 1000)).$((wait_ms % 1000 / 100))"
    printf 'HTTP/1.0 200 OK\r\nContent-Type: image/gif\r\nContent-Length: 0\r\nConnection: close\r\n\r\n'
) | nc -l localhost "$port" >/dev/null 2>&1 &
pids+=($!)
cat >"$work/page.html" <<EOF
<!doctype html>
<html><body style="margin:0;overflow:hidden">
<iframe src="$url" style="border:0;width:100vw;height:100vh;display:block"></iframe>
<img src="http://localhost:$port/wait.gif" style="position:absolute;width:1px;height:1px;opacity:0">
</body></html>
EOF

mkdir -p "$(dirname "$out")"
rm -f "$out"
# A profile of its own, so that it never touches David's Chrome.
"$chrome" --headless=new --disable-gpu --hide-scrollbars --no-first-run --no-default-browser-check \
    --user-data-dir="$work/profile" --window-size="${size/x/,}" \
    --screenshot="$out" "file://$work/page.html" >/dev/null 2>&1 &
pids+=($!)
chrome_pid=$!
deadline=$(($(date +%s) + wait_ms / 1000 + 30))
while kill -0 "$chrome_pid" 2>/dev/null; do
    [[ -s $out ]] && sleep 1 && break
    (($(date +%s) < deadline)) || break
    sleep 1
done
[[ -s $out ]] || { echo "factory: no screenshot of $url" >&2; exit 1; }
echo "$out"
