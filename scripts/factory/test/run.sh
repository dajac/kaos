#!/usr/bin/env bash
# Runs the tests of the factory scripts: every function named test_* in
# test/*_test.sh, each in its own bash with "set -e", in its own
# temporary directory, so that any failing command fails the test.
# Usage: scripts/factory/test/run.sh [file ...]
set -uo pipefail
cd "$(dirname "$0")"
TEST_DIR=$(pwd)
export FACTORY_TEST_SCRIPTS=$(cd .. && pwd)
# The tests must not see the caller's bot or identity: a Claude session
# runs them with the bot's environment.
unset FACTORY_HOME FACTORY_IDENTITY FACTORY_IDENTITY_DIR FACTORY_SCRIPTS FACTORY_TRUSTED GH_TOKEN GH_CONFIG_DIR \
    GIT_CONFIG_COUNT FACTORY_GIT_CONFIG_BASE GIT_AUTHOR_NAME GIT_AUTHOR_EMAIL GIT_COMMITTER_NAME GIT_COMMITTER_EMAIL \
    GIT_SSH_COMMAND CLAUDE_ENV_FILE CLAUDE_PROJECT_DIR
PATH=$(tr ':' '\n' <<<"$PATH" | grep -v '/scripts/factory/bin$' | paste -sd: -)
# Commits made by the tests need an identity, which CI's runners lack.
export GIT_AUTHOR_NAME=factory-test GIT_AUTHOR_EMAIL=factory-test@example.com
export GIT_COMMITTER_NAME=factory-test GIT_COMMITTER_EMAIL=factory-test@example.com

passed=0
failed=0
files=("$@")
((${#files[@]})) || files=(*_test.sh)
for file in "${files[@]}"; do
    for test in $(bash -c ". '$TEST_DIR/helpers.sh' && . '$TEST_DIR/$file' && declare -F" | awk '$3 ~ /^test_/ {print $3}'); do
        tmp=$(mktemp -d)
        bash -c 'set -eo pipefail; cd "$1"; . "$2/helpers.sh"; . "$2/$3"; "$4"' _ "$tmp" "$TEST_DIR" "$file" "$test" >"$tmp.log" 2>&1
        if (($? == 0)); then
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
