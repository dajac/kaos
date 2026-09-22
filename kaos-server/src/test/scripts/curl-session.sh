#!/usr/bin/env bash
#
# Drives a full run of the HTTP API with curl: creates and starts a simulator, watches the SSE
# stream, injects a fault into a consumer, clears it, and stops the simulator.
#
# Usage: kaos-server/src/test/scripts/curl-session.sh [base-url]
#
# It needs a running server (./run.sh, default http://localhost:8081) connected to a broker
# (docker run -p 9092:9092 apache/kafka:4.3.1). Only curl, grep and sed are used.

set -euo pipefail

BASE="${1:-http://localhost:8081}/api/v1"
TOPIC="curl-orders"
GROUP="curl-group"
EVENTS="$(mktemp)"
trap 'kill "${SSE_PID:-}" 2>/dev/null || true; rm -f "$EVENTS"' EXIT

step() { printf '\n== %s\n' "$*"; }

# Prints the method, the path and the status of a request, then its body if there is one.
call() {
    local method="$1" path="$2" body="${3:-}"
    local args=(-s -w '\n%{http_code}' -X "$method" "$BASE$path")
    if [[ -n "$body" ]]; then
        args+=(-H 'Content-Type: application/json' -d "$body")
    fi
    local response
    response=$(curl "${args[@]}")
    printf '%s %s -> %s\n' "$method" "$path" "${response##*$'\n'}"
    local content="${response%$'\n'*}"
    if [[ -n "$content" ]]; then
        printf '  %s\n' "$content"
    fi
}

# Waits until the snapshot matches the pattern, for at most 60 seconds.
await_snapshot() {
    local pattern="$1"
    for _ in $(seq 1 120); do
        if curl -s "$BASE/simulator/snapshot" | grep -q -- "$pattern"; then
            return 0
        fi
        sleep 0.5
    done
    echo "Timed out waiting for $pattern" >&2
    exit 1
}

step "Metadata"
curl -s "$BASE/meta" | sed -E 's/"fields":\[.*\],"nameRule"/"fields":[...],"nameRule"/'
echo

step "Watch the event stream"
curl -sN "$BASE/simulator/events" >"$EVENTS" &
SSE_PID=$!
sleep 1

step "Create and start: one topic, one producer, one group of three consumers"
call POST /simulator "{
  \"topics\": [{\"name\": \"$TOPIC\", \"partitions\": 6}],
  \"producers\": [{\"topics\": [\"$TOPIC\"]}],
  \"groups\": [{\"id\": \"$GROUP\", \"subscribedTopics\": [\"$TOPIC\"],
                \"consumers\": [{}, {}, {\"overrides\": {\"protocol\": \"classic\"}}]}]
}"
await_snapshot '"phase":"RUNNING"'
await_snapshot '"status":"STABLE".*"status":"STABLE".*"status":"STABLE"'
echo "The simulator runs and its three consumers are stable."

step "Inject a fault: freeze consumer-0"
call PUT "/simulator/groups/$GROUP/consumers/consumer-0/faults/freeze"
await_snapshot '"faults":\["freeze"\]'
curl -s "$BASE/simulator/snapshot" | grep -o '"id":"consumer-0","clientId":"[^"]*","protocol":"[a-z]*","subscribed":[a-z]*,"faults":\[[^]]*\]' || true
curl -s "$BASE/simulator/snapshot" | grep -o '"status":"FROZEN"' | sed -n 1p

step "Clear the fault"
call DELETE "/simulator/groups/$GROUP/consumers/consumer-0/faults/freeze"

step "Errors are problem details"
call PUT "/simulator/groups/$GROUP/consumers/nobody/faults/freeze"
call PUT "/simulator/groups/$GROUP/consumers/consumer-0/faults/unknown"
call POST /simulator '{"topics": [{"name": "x", "partitions": 0}]}'

step "Stop"
call DELETE /simulator
await_snapshot '"phase":"IDLE"'
call DELETE /simulator
call GET /simulator
sleep 1
kill "$SSE_PID" 2>/dev/null || true
wait "$SSE_PID" 2>/dev/null || true

step "The event stream"
echo "Events received: $(grep -c '^event: snapshot' "$EVENTS") snapshot, $(grep -c '^event: status' "$EVENTS") status"
echo "The status events:"
grep -A1 '^event: status' "$EVENTS" | grep '^data:' | sed 's/^/  /'
echo "The snapshot sent on connect, the first one with the freeze (abridged), and the last one:"
snapshots=$(grep -A2 '^event: snapshot' "$EVENTS" | grep -E '^(id|data):')
sed -n '1,2p' <<<"$snapshots" | cut -c1-200 | sed 's/^/  /'
frozen=$(grep -B1 -m1 '"faults":\["freeze"\]' <<<"$snapshots")
sed -n 1p <<<"$frozen" | sed 's/^/  /'
echo "  data: ...$(sed -n 2p <<<"$frozen" | grep -o '"id":"consumer-0".*' | cut -c1-400)..."
tail -n 2 <<<"$snapshots" | cut -c1-200 | sed 's/^/  /'
