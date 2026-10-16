#!/usr/bin/env bash
# Runs the tests of the factory scripts: every function named test_* in
# test/*_test.sh, each in its own subshell and temporary directory.
# Usage: scripts/factory/test/run.sh [file ...]
set -uo pipefail
cd "$(dirname "$0")"
TEST_DIR=$(pwd)
export FACTORY_TEST_SCRIPTS=$(cd .. && pwd)

fail() {
    echo "    $*" >&2
    exit 1
}

assert_eq() {
    [[ $1 == "$2" ]] || fail "expected '$2', got '$1'${3:+ ($3)}"
}

assert_contains() {
    [[ $1 == *"$2"* ]] || fail "expected '$1' to contain '$2'"
}

passed=0
failed=0
files=("$@")
((${#files[@]})) || files=(*_test.sh)
for file in "${files[@]}"; do
    for test in $(bash -c ". '$TEST_DIR/$file' && declare -F" | awk '$3 ~ /^test_/ {print $3}'); do
        tmp=$(mktemp -d)
        if (
            cd "$tmp" && . "$TEST_DIR/$file" && set -e && "$test"
        ) >"$tmp.log" 2>&1; then
            passed=$((passed + 1))
        else
            failed=$((failed + 1))
            echo "FAIL $file $test"
            sed 's/^/    /' "$tmp.log"
        fi
        rm -rf "$tmp" "$tmp.log"
    done
done
echo "$passed passed, $failed failed"
((failed == 0))
