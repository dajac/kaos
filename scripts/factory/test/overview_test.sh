# Tests of the overview (./factory status) and of ./factory run.

snapshot() {
    cat >snapshot.json <<'JSON'
{
  "issues": [
    {"number": 73, "title": "Ellipses | dots", "labels": ["triaged"], "stage": "triaged", "stages": ["triaged"], "needs_spec": false, "updated_at": "2026-10-16T08:00:00Z", "created_at": "2026-10-10T08:00:00Z"},
    {"number": 75, "title": "Session timeout", "labels": ["in-progress", "accepted"], "stage": "in-progress", "stages": ["in-progress"], "needs_spec": false, "updated_at": "2026-10-16T09:00:00Z", "created_at": "2026-10-10T08:00:00Z"},
    {"number": 76, "title": "Remove the auto-pilot", "labels": ["queued", "accepted", "needs-spec", "priority:high"], "stage": "queued", "stages": ["queued"], "needs_spec": true, "updated_at": "2026-10-15T09:00:00Z", "created_at": "2026-10-10T08:00:00Z"},
    {"number": 51, "title": "Stuck partitions", "labels": ["task"], "stage": "none", "stages": [], "needs_spec": false, "updated_at": "2026-10-15T09:00:00Z", "created_at": "2026-09-22T08:00:00Z"},
    {"number": 60, "title": "Two stages", "labels": ["queued", "blocked"], "stage": "invalid", "stages": ["queued", "blocked"], "needs_spec": false, "updated_at": "2026-10-15T09:00:00Z", "created_at": "2026-10-10T08:00:00Z"}
  ],
  "prs": [{"number": 89, "head": "75-session-timeout", "draft": true, "checks": "pending", "closes": [75]}],
  "closed_prs": []
}
JSON
}

test_render_groups_the_issues_by_who_they_wait_for() {
    git init -q home
    export FACTORY_HOME=$PWD/home
    snapshot
    mkdir -p "$FACTORY_HOME/.factory"
    echo '{"kind": "build", "issue": "75", "pid": 1}' >"$FACTORY_HOME/.factory/job.json"
    out=$("$FACTORY_TEST_SCRIPTS/overview.sh" snapshot.json)
    assert_contains "$out" "running: build #75"
    assert_contains "$out" "## Waiting for David (1)"
    assert_contains "$out" "| #73 Ellipses / dots | triage proposal to accept, comment on or close |  | Oct 16 08:00 |"
    assert_contains "$out" "| #75 Session timeout | in-progress (running) | #89 (draft), CI pending |"
    assert_contains "$out" "| #76 Remove the auto-pilot | spec first | high |"
    assert_contains "$out" "## To triage (1)"
    assert_contains "$out" "## Several stage labels (1)"
}

test_run_queues_a_request_for_the_loop() {
    git init -q home
    export FACTORY_HOME=$PWD/home
    out=$("$FACTORY_TEST_SCRIPTS/../../factory" run triage 76 2>&1)
    assert_contains "$out" "queued triage #76"
    assert_eq "$(cat "$FACTORY_HOME"/.factory/requests/*)" "triage:76"
    "$FACTORY_TEST_SCRIPTS/../../factory" run deploy 76 2>/dev/null && fail "accepted an unknown job"
    true
}
