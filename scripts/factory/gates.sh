#!/usr/bin/env bash
# Runs the checks a PR must pass, from the root of the worktree it is
# run in, and ends with exactly one verdict line:
#   GATES: PASS
#   GATES: FAIL <step>
#   GATES: MISCONFIGURED <why>
# Jobs quote that line, so that a skipped step can never read as a pass.
#
# Usage: scripts/factory/gates.sh [quick|full]
#   quick  the format check, then ./mvnw verify without the integration tests
#   full   the format check, then ./mvnw verify with the integration tests
#          (Docker); required before a PR leaves draft
# The format step only checks: a job runs ./mvnw spotless:apply and commits
# the result, so that the verdict is about the tree it pushes.
# The factory's script tests run in both. Each step's output goes to
# .factory/logs/gates-<step>.log in the worktree; a failing step prints
# its last lines.
set -uo pipefail

level=${1:-full}
case $level in
quick | full) ;;
*)
    echo "GATES: MISCONFIGURED unknown level '$level', expected quick or full"
    exit 2
    ;;
esac

root=$(git rev-parse --show-toplevel 2>/dev/null) || {
    echo "GATES: MISCONFIGURED not in a git worktree"
    exit 2
}
cd "$root"
logs=$root/.factory/logs
mkdir -p "$logs"

misconfigured() {
    echo "GATES: MISCONFIGURED $1"
    exit 2
}

# ./mvnw runs the JDK of JAVA_HOME when it is set, the one on PATH otherwise.
java_major=$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}')
[[ -n $java_major ]] || misconfigured "java not found"
((java_major >= 21)) || misconfigured "JDK $java_major, the build needs 21 or later"
if [[ $level == full ]]; then
    docker info >/dev/null 2>&1 || misconfigured "Docker is not running, the integration tests need it"
fi

step() {
    local name=$1
    shift
    echo "gates: $name: $*"
    if ! "$@" >"$logs/gates-$name.log" 2>&1; then
        tail -n 60 "$logs/gates-$name.log"
        echo "GATES: FAIL $name (log: .factory/logs/gates-$name.log)"
        exit 1
    fi
}

step format ./mvnw -B -ntp -q spotless:check
step factory-tests scripts/factory/test/run.sh
if [[ $level == quick ]]; then
    step verify ./mvnw -B -ntp verify -DskipITs
else
    step verify ./mvnw -B -ntp verify
fi
echo "GATES: PASS"
