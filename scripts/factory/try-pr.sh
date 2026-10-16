#!/usr/bin/env bash
# Builds and runs a PR, a branch or a worktree of Kaos against its own
# broker, and drives it to a known state with a scenario: what
# "./factory try" runs for David, and what the build job and the UX
# reviewer use for their screenshots.
#
# Usage: scripts/factory/try-pr.sh <PR|branch|main|.> [--scenario <file>] [--detach] [--no-build]
#        scripts/factory/try-pr.sh --stop <name>
#
# - <PR> or <branch>: a worktree .claude/worktrees/try-<name> at its
#   head; "main": origin/main; ".": the current worktree, as it is.
# - It builds the shaded jar (-DskipTests), starts apache/kafka:4.3.1 in
#   its own container on a free port (so that parallel runs never share
#   topics or groups), runs the server on free ports, runs the scenario
#   (the PR's own scripts/factory/scenarios/default.json unless
#   --scenario says otherwise: a file, or the name of one of the tree's
#   scenarios; "none" for no scenario), and prints the UI's URL.
# - Without --detach it waits, and stops everything on Ctrl-C. With
#   --detach it returns, leaving everything running, and prints the
#   name to stop it with: try-pr.sh --stop <name>. Its state is in
#   .factory/try/<name>/ (url, logs, pids).
# - --no-build reuses the jar of an earlier build of the same tree.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

image=apache/kafka:4.3.1
state_root=$FACTORY_STATE/try

stop() {
    local dir=$state_root/$1
    [[ -d $dir ]] || die "nothing runs as $1"
    if [[ -f $dir/server.pid ]]; then
        kill "$(cat "$dir/server.pid")" 2>/dev/null || true
    fi
    if [[ -f $dir/container ]]; then
        docker rm -f "$(cat "$dir/container")" >/dev/null 2>&1 || true
    fi
    rm -rf "$dir"
    info "stopped $1"
}

if [[ ${1:-} == --stop ]]; then
    stop "${2:?usage: try-pr.sh --stop <name>}"
    exit 0
fi

target=${1:?usage: try-pr.sh <PR|branch|main|.> [--scenario <file>] [--detach]}
shift
scenario=
detach=false
build=true
while (($#)); do
    case $1 in
    --scenario) scenario=${2:?--scenario needs a file}; shift 2 ;;
    --detach) detach=true; shift ;;
    --no-build) build=false; shift ;;
    *) die "unknown option $1" ;;
    esac
done

# The tree to run.
case $target in
.)
    tree=$(git rev-parse --show-toplevel)
    name=$(basename "$tree")
    ;;
main)
    name=main
    tree=$FACTORY_HOME/.claude/worktrees/try-main
    git -C "$FACTORY_HOME" fetch -q origin main
    rev=$(git -C "$FACTORY_HOME" rev-parse origin/main)
    ;;
*)
    if [[ $target =~ ^[0-9]+$ ]]; then
        name=$target
        git -C "$FACTORY_HOME" fetch -q origin "pull/$target/head"
    else
        name=${target//\//-}
        git -C "$FACTORY_HOME" fetch -q origin "$target"
    fi
    rev=$(git -C "$FACTORY_HOME" rev-parse FETCH_HEAD)
    tree=$FACTORY_HOME/.claude/worktrees/try-$name
    ;;
esac
if [[ $target != . ]]; then
    if [[ -d $tree ]]; then
        [[ $(git -C "$tree" rev-parse HEAD) == "$rev" ]] || build=true
        git -C "$tree" checkout -q --detach "$rev"
    else
        build=true
        git -C "$FACTORY_HOME" worktree add -q --detach "$tree" "$rev"
    fi
fi
name=try-$name
dir=$state_root/$name
[[ -d $dir ]] && stop "$name"
mkdir -p "$dir"

# A scenario is a file, or the name of one of the tree's scenarios.
[[ -n $scenario ]] || scenario=default.json
if [[ $scenario != none && ! -f $scenario ]]; then
    scenario=$tree/scripts/factory/scenarios/$scenario
fi
[[ $scenario == none || -f $scenario ]] || die "no scenario $scenario"

free_port() {
    local port=$1
    while nc -z localhost "$port" 2>/dev/null; do
        port=$((port + 1))
    done
    echo "$port"
}

if $build || ! ls "$tree"/kaos-server/target/kaos-server-*.jar >/dev/null 2>&1; then
    info "building $tree"
    (cd "$tree" && ./mvnw -q -B -ntp package -DskipTests) >"$dir/build.log" 2>&1 ||
        { tail -n 40 "$dir/build.log" >&2; die "the build failed, see $dir/build.log"; }
fi
jar=$(ls "$tree"/kaos-server/target/kaos-server-*.jar | grep -v original | head -n 1)

kafka_port=$(free_port 19400)
container=kaos-$name-$kafka_port
info "starting $image on localhost:$kafka_port"
docker run -d --rm --name "$container" -p "$kafka_port:9092" \
    -e KAFKA_NODE_ID=1 \
    -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
    -e KAFKA_ADVERTISED_LISTENERS="PLAINTEXT://localhost:$kafka_port" \
    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
    -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
    "$image" >/dev/null
echo "$container" >"$dir/container"

http_port=$(free_port 18100)
proxy_port=$(free_port 19500)
info "starting the server on localhost:$http_port"
java -jar "$jar" --bootstrap-server "localhost:$kafka_port" --port "$http_port" --proxy-port "$proxy_port" \
    >"$dir/server.log" 2>&1 &
echo $! >"$dir/server.pid"
url=http://localhost:$http_port
echo "$url" >"$dir/url"

deadline=$(($(date +%s) + 120))
until curl -sf "$url/api/v1/meta" >/dev/null 2>&1; do
    if (($(date +%s) > deadline)) || ! kill -0 "$(cat "$dir/server.pid")" 2>/dev/null; then
        tail -n 40 "$dir/server.log" >&2
        stop "$name"
        die "the server did not start"
    fi
    sleep 1
done

if [[ $scenario != none ]]; then
    info "running the scenario $(basename "$scenario")"
    "$FACTORY_SCRIPTS/scenario.sh" "$url" "$scenario" >"$dir/scenario.log" 2>&1 ||
        { tail -n 20 "$dir/scenario.log" >&2; stop "$name"; die "the scenario failed"; }
fi

echo "$url/"
if $detach; then
    info "running as $name; stop it with: scripts/factory/try-pr.sh --stop $name"
    exit 0
fi
info "open $url/ ; Ctrl-C stops the server and the broker"
trap 'stop "$name"; exit 0' INT TERM
wait "$(cat "$dir/server.pid")" || true
stop "$name"
