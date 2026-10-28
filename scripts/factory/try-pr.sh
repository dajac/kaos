#!/usr/bin/env bash
# Builds and runs a PR, a branch or a worktree of Kaos against its own
# broker, and drives it to a known state with a scenario: what
# "./factory try" runs for David, and what the build job uses for its
# screenshots.
#
# Usage: scripts/factory/try-pr.sh <PR|branch|main|.> [--scenario <name|file|none>] [--detach] [--no-build] [--as <name>]
#        scripts/factory/try-pr.sh --stop <name>
#        scripts/factory/try-pr.sh --clean
#
# - <PR> or <branch>: a worktree .claude/worktrees/try-<name> at its
#   head; "main": origin/main; ".": the current worktree, as it is. The
#   name is try-<PR|branch|main|worktree>, or try-<name> with --as, so
#   that parallel runs of one target (David's, a job's) do not collide.
# - It builds the shaded jar (-DskipTests; --no-build reuses an earlier
#   one of the same head), starts apache/kafka:4.4.0 in its own
#   container on a free port, runs the server on free ports, runs the
#   scenario (default.json unless --scenario names another, or a file;
#   "none" for none), and prints the UI's URL on its last line. A
#   scenario name is looked up in the tree's scenarios, then in the
#   factory's, then, for a PR, among the files published for it on the
#   issue-screenshots branch (pr-<PR>/): a PR does not commit the
#   scenarios made only to show it.
# - Without --detach it waits, and stops everything on Ctrl-C. With
#   --detach it returns, leaving everything running: --stop <name> stops
#   it. Its state is in .factory/try/<name>/ (url, logs, pids).
# - --clean stops every run and removes the try-* worktrees.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

image=apache/kafka:4.4.0
state_root=$FACTORY_STATE/try

# Stops a run: the server first, waiting for it to exit, then its broker.
# Removing the broker first would leave the server retrying it for a
# minute, long enough to reach the next run's broker on the same port.
stop() {
    local dir=$state_root/$1 pid i
    [[ -d $dir ]] || return 0
    if [[ -f $dir/server.pid ]]; then
        pid=$(cat "$dir/server.pid")
        kill "$pid" 2>/dev/null || true
        for ((i = 0; i < 20; i++)); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 0.5
        done
        kill -9 "$pid" 2>/dev/null || true
    fi
    if [[ -f $dir/container ]]; then
        docker rm -f "$(cat "$dir/container")" >/dev/null 2>&1 || true
    fi
    rm -rf "$dir"
    info "stopped $1"
}

case ${1:-} in
--stop)
    stop "${2:?usage: try-pr.sh --stop <name>}"
    exit 0
    ;;
--clean)
    for dir in "$state_root"/*; do
        [[ -d $dir ]] && stop "$(basename "$dir")"
    done
    for tree in "$FACTORY_HOME"/.claude/worktrees/try-*; do
        [[ -d $tree ]] || continue
        git -C "$FACTORY_HOME" worktree remove --force "$tree" && info "removed $tree"
    done
    exit 0
    ;;
esac

target=${1:?usage: try-pr.sh <PR|branch|main|.> [--scenario <name|file|none>] [--detach] [--no-build] [--as <name>]}
shift
scenario=
detach=false
build=true
as=
while (($#)); do
    case $1 in
    --scenario) scenario=${2:?--scenario needs a name or a file}; shift 2 ;;
    --detach) detach=true; shift ;;
    --no-build) build=false; shift ;;
    --as) as=${2:?--as needs a name}; shift 2 ;;
    *) die "unknown option $1" ;;
    esac
done

case $target in
.) key=$(basename "$(git rev-parse --show-toplevel)") ;;
*) key=${target//\//-} ;;
esac
name=try-${as:-$key}

# The tree to run, at the target's head, fetched into a ref of its own
# (FETCH_HEAD is shared with the loop's fetches).
if [[ $target == . ]]; then
    tree=$(git rev-parse --show-toplevel)
else
    ref=refs/factory/try/$name
    case $target in
    main) git -C "$FACTORY_HOME" fetch -q origin "+main:$ref" ;;
    *[!0-9]*) git -C "$FACTORY_HOME" fetch -q origin "+refs/heads/$target:$ref" ;;
    *) git -C "$FACTORY_HOME" fetch -q origin "+pull/$target/head:$ref" ;;
    esac
    rev=$(git -C "$FACTORY_HOME" rev-parse "$ref")
    tree=$FACTORY_HOME/.claude/worktrees/$name
    if [[ -d $tree ]]; then
        [[ $(git -C "$tree" rev-parse HEAD) == "$rev" ]] || build=true
        git -C "$tree" checkout -q --detach "$rev"
    else
        build=true
        git -C "$FACTORY_HOME" worktree add -q --detach "$tree" "$rev"
    fi
fi

dir=$state_root/$name
stop "$name"
mkdir -p "$dir"
# Whatever goes wrong from here, nothing is left running, unless the run
# is handed over with --detach.
handed_over=false
trap '$handed_over || stop "$name"' EXIT
trap 'exit 130' INT TERM

# A scenario is a file, or a name: the tree's, the factory's, or one
# published for the PR.
[[ -n $scenario ]] || scenario=default.json
# A name may leave out its .json.
[[ $scenario == none || -f $scenario || $scenario == *.json ]] || scenario=$scenario.json
if [[ $scenario != none && ! -f $scenario ]]; then
    found=
    for candidate in "$tree/scripts/factory/scenarios/$scenario" "$FACTORY_SCRIPTS/scenarios/$scenario"; do
        [[ -f $candidate ]] && found=$candidate && break
    done
    if [[ -z $found && $target =~ ^[0-9]+$ ]] &&
        git -C "$FACTORY_HOME" fetch -q origin "+issue-screenshots:refs/factory/issue-screenshots" 2>/dev/null &&
        git -C "$FACTORY_HOME" show "refs/factory/issue-screenshots:pr-$target/$scenario" >"$dir/$scenario" 2>/dev/null; then
        found=$dir/$scenario
    fi
    scenario=${found:-$scenario}
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
server=$!
echo "$server" >"$dir/server.pid"
url=http://localhost:$http_port
echo "$url" >"$dir/url"

# Ready when our server, not another run's that took the same free port
# first, answers on it.
deadline=$(($(date +%s) + 120))
until curl -sf "$url/api/v1/meta" >/dev/null 2>&1 &&
    [[ $(lsof -nP -iTCP:"$http_port" -sTCP:LISTEN -t 2>/dev/null | head -n 1) == "$server" ]]; do
    if (($(date +%s) > deadline)) || ! kill -0 "$server" 2>/dev/null; then
        tail -n 40 "$dir/server.log" >&2
        die "the server did not start (or another run took its port)"
    fi
    sleep 1
done

if [[ $scenario != none ]]; then
    info "running the scenario $(basename "$scenario")"
    "$FACTORY_SCRIPTS/scenario.sh" "$url" "$scenario" >"$dir/scenario.log" 2>&1 ||
        { tail -n 20 "$dir/scenario.log" >&2; die "the scenario failed"; }
fi

if $detach; then
    handed_over=true
    info "running as $name; stop it with: scripts/factory/try-pr.sh --stop $name"
    echo "$url/"
    exit 0
fi
info "open $url/ ; Ctrl-C stops the server and the broker"
echo "$url/"
wait "$server" || true
