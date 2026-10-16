# Tests of scenario.sh and publish-shots.sh.

fake_curl() {
    mkdir -p bin
    cat >bin/curl <<'CURL'
#!/usr/bin/env bash
# Logs the call; answers 202 to requests, and the content of
# $FAKE_ANSWER to GETs.
echo "$*" >>"$CURL_LOG"
case " $* " in
*" -w "*) printf 202 ;;
*) cat "$FAKE_ANSWER" ;;
esac
CURL
    chmod +x bin/curl
    PATH=$PWD/bin:$PATH
    export CURL_LOG=$PWD/curl.log FAKE_ANSWER=$PWD/answer.json
}

test_scenario_runs_its_include_then_its_steps() {
    fake_curl
    mkdir s
    echo '{"steps": [{"request": "POST /api/v1/simulator", "body": {"topics": [{"name": "t"}]}}, {"wait": {"path": "/api/v1/simulator", "jq": ".phase == \"RUNNING\"", "timeout_s": 5}}]}' >s/base.json
    echo '{"include": "base.json", "steps": [{"request": "PUT /api/v1/x/faults/freeze"}, {"sleep_s": 0}]}' >s/top.json
    echo '{"phase": "RUNNING"}' >answer.json
    "$FACTORY_TEST_SCRIPTS/scenario.sh" http://localhost:1 s/top.json >/dev/null
    assert_contains "$(sed -n 1p curl.log)" "-X POST -H Content-Type: application/json -d {\"topics\":[{\"name\":\"t\"}]} http://localhost:1/api/v1/simulator"
    assert_contains "$(sed -n 2p curl.log)" "http://localhost:1/api/v1/simulator"
    assert_contains "$(sed -n 3p curl.log)" "-X PUT http://localhost:1/api/v1/x/faults/freeze"
}

test_scenario_times_out_on_a_wait() {
    fake_curl
    echo '{"steps": [{"wait": {"path": "/s", "jq": ".ok", "timeout_s": 1}}]}' >w.json
    echo '{"ok": false}' >answer.json
    out=$("$FACTORY_TEST_SCRIPTS/scenario.sh" http://localhost:1 w.json 2>&1) && fail "the wait passed"
    assert_contains "$out" "timed out waiting for .ok"
}

test_the_shipped_scenarios_are_valid() {
    for file in "$FACTORY_TEST_SCRIPTS"/scenarios/*.json; do
        jq -e '.steps | type == "array"' "$file" >/dev/null || fail "$file has no steps"
        jq -e 'all(.steps[]; (has("request") and (keys - ["request", "body"] | length == 0))
            or (has("wait") and (.wait | has("path") and has("jq")) and (keys == ["wait"]))
            or (keys == ["sleep_s"]))' "$file" >/dev/null || fail "$file has an invalid step"
        jq -e '.include == null or (.include | type == "string")' "$file" >/dev/null || fail "$file has an invalid include"
        jq -r '.steps[]? | .wait.jq // empty' "$file" | while IFS= read -r filter; do
            jq -n "null | try ($filter) catch true" >/dev/null 2>&1 || fail "$file: invalid jq $filter"
        done
    done
}

test_publish_commits_the_shots_and_prints_markdown() {
    git init -q --bare remote.git
    git init -q seed
    git -C seed checkout -q --orphan issue-screenshots
    git -C seed commit -q --allow-empty -m "README only"
    git -C seed push -q ../remote.git issue-screenshots
    git -C seed commit -q --allow-empty -m "someone else's shots"
    git -C seed push -q ../remote.git HEAD:refs/heads/issue-screenshots
    git clone -q remote.git home
    git -C home commit -q --allow-empty -m main
    export FACTORY_HOME=$PWD/home
    printf 'png' >rows-after.png
    echo '{"steps": []}' >starting.json
    out=$("$FACTORY_TEST_SCRIPTS/publish-shots.sh" 81 rows-after.png starting.json)
    assert_eq "$out" "![rows-after](https://github.com/dajac/kaos/blob/issue-screenshots/pr-81/rows-after.png?raw=true)"
    assert_eq "$(git -C remote.git show issue-screenshots:pr-81/rows-after.png)" png
    assert_eq "$(git -C remote.git show issue-screenshots:pr-81/starting.json)" '{"steps": []}'
    assert_contains "$(git -C remote.git log --format=%s issue-screenshots)" "someone else's shots"
    ! git -C home show-ref --quiet refs/heads/issue-screenshots || fail "a local issue-screenshots branch was created"
}
