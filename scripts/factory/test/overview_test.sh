# Tests of the overview (./factory status) and of ./factory run.

snapshot() {
    cat >snapshot.json <<'JSON'
{
  "issues": [
    {"number": 73, "title": "Ellipses | dots", "labels": ["triaged"], "stage": "triaged", "stages": ["triaged"], "needs_spec": false, "updated_at": "2026-10-16T08:00:00Z", "created_at": "2026-10-10T08:00:00Z"},
    {"number": 74, "title": "Group configs refused by the broker are not shown", "labels": ["spec-review"], "stage": "spec-review", "stages": ["spec-review"], "needs_spec": true, "updated_at": "2026-10-14T10:00:00Z", "created_at": "2026-10-10T08:00:00Z"},
    {"number": 81, "title": "Row text unreadable", "labels": ["in-review"], "stage": "in-review", "stages": ["in-review"], "needs_spec": false, "updated_at": "2026-10-16T09:40:00Z", "created_at": "2026-10-10T08:00:00Z"},
    {"number": 82, "title": "Stuck job", "labels": ["blocked"], "stage": "blocked", "stages": ["blocked"], "needs_spec": false, "updated_at": "2026-10-16T09:59:30Z", "created_at": "2026-10-10T08:00:00Z"},
    {"number": 75, "title": "Session timeout", "labels": ["in-progress", "accepted"], "stage": "in-progress", "stages": ["in-progress"], "needs_spec": false, "updated_at": "2026-10-16T09:00:00Z", "created_at": "2026-10-10T08:00:00Z"},
    {"number": 76, "title": "Remove the auto-pilot", "labels": ["queued", "accepted", "needs-spec", "priority:high"], "stage": "queued", "stages": ["queued"], "needs_spec": true, "updated_at": "2026-10-15T09:00:00Z", "created_at": "2026-10-10T08:00:00Z"},
    {"number": 77, "title": "Rename a field", "labels": ["queued", "accepted"], "stage": "queued", "stages": ["queued"], "needs_spec": false, "updated_at": "2026-10-15T09:00:00Z", "created_at": "2026-10-10T08:00:00Z"},
    {"number": 51, "title": "Stuck partitions", "labels": ["task"], "stage": "none", "stages": [], "needs_spec": false, "updated_at": "2026-10-15T09:00:00Z", "created_at": "2026-09-22T08:00:00Z"},
    {"number": 61, "title": "Refused configs", "labels": ["needs-triage"], "stage": "needs-triage", "stages": ["needs-triage"], "needs_spec": false, "updated_at": "2026-10-15T09:00:00Z", "created_at": "2026-10-08T08:00:00Z"},
    {"number": 62, "title": "Unclear report", "labels": ["needs-info"], "stage": "needs-info", "stages": ["needs-info"], "needs_spec": false, "updated_at": "2026-10-15T09:00:00Z", "created_at": "2026-10-08T08:00:00Z"},
    {"number": 60, "title": "Two stages", "labels": ["queued", "blocked"], "stage": "invalid", "stages": ["queued", "blocked"], "needs_spec": false, "updated_at": "2026-10-15T09:00:00Z", "created_at": "2026-10-10T08:00:00Z"}
  ],
  "prs": [
    {"number": 89, "head": "75-session-timeout", "draft": true, "checks": "pending", "closes": [75]},
    {"number": 90, "head": "81-row-text", "draft": false, "checks": "pass", "closes": [81]},
    {"number": 91, "head": "spec/74-group-configs", "draft": false, "checks": "pass", "closes": []}
  ],
  "closed_prs": []
}
JSON
}

overview() {
    git init -q home
    export FACTORY_HOME=$PWD/home FACTORY_NOW=2026-10-16T10:00:00Z
    snapshot
    mkdir -p "$FACTORY_HOME/.factory"
    echo '{"kind": "build", "issue": "75", "pid": 1}' >"$FACTORY_HOME/.factory/job.json"
    COLUMNS=$1 "$FACTORY_TEST_SCRIPTS/overview.sh" snapshot.json
}

test_render_prints_the_sections_as_plain_text() {
    out=$(overview 100)
    expected='Updated 2026-10-16 10:00 UTC · loop down · running: build #75 · paused: no

Waiting for David (4)
  #73  Ellipses | dots                                    accept the triage                2h
  #74  Group configs refused by the broker are not shown  review the spec    #91, CI pass  2d
  #81  Row text unreadable                                review the PR      #90, CI pass  20m
  #82  Stuck job                                          unblock                          0m

In flight (1)
  #75  Session timeout  in-progress (running)  #89 (draft), CI pending  1h

Queued 2 (1 high priority, 1 spec first) · To triage 2 · Needs info 1

Several stage labels (1)
  #60  Two stages  queued, blocked  1d'
    assert_eq "$out" "$expected"
}

test_render_shortens_the_titles_to_the_width() {
    out=$(overview 70)
    assert_contains "$out" "  #74  Group configs refused by…  review the spec    #91, CI pass  2d"
    assert_contains "$out" "  #73  Ellipses | dots            accept the triage                2h"
    assert_contains "$out" "  #75  Session ti…  in-progress (running)  #89 (draft), CI pending  1h"
    while IFS= read -r line; do
        [[ $line == Updated* || $line == Queued* ]] && continue
        ((${#line} <= 70)) || fail "line longer than 70 columns: $line"
    done <<<"$out"
}

test_render_without_a_terminal_fits_100_columns() {
    git init -q home
    export FACTORY_HOME=$PWD/home FACTORY_NOW=2026-10-16T10:00:00Z
    snapshot
    out=$(env -u COLUMNS "$FACTORY_TEST_SCRIPTS/overview.sh" snapshot.json)
    assert_contains "$out" "running: nothing"
    assert_contains "$out" "  #74  Group configs refused by the broker are not shown  review the spec    #91, CI pass  2d"
}

test_render_shows_nothing_for_an_empty_section() {
    git init -q home
    export FACTORY_HOME=$PWD/home FACTORY_NOW=2026-10-16T10:00:00Z
    echo '{"issues": [], "prs": [], "closed_prs": []}' >snapshot.json
    out=$(COLUMNS=80 "$FACTORY_TEST_SCRIPTS/overview.sh" snapshot.json)
    assert_contains "$out" "Waiting for David (0)
  nothing"
    assert_contains "$out" "Queued 0 · To triage 0 · Needs info 0"
    [[ $out == *"Several stage labels"* ]] && fail "listed a section without issues"
    true
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

test_an_approved_green_pr_waits_for_a_merge_by_hand() {
    out=$(overview 120)
    assert_contains "$out" "review the PR"
    jq '(.prs[] | select(.number == 90)) += {review: "APPROVED"}' snapshot.json >x && mv x snapshot.json
    out=$(COLUMNS=120 "$FACTORY_TEST_SCRIPTS/overview.sh" snapshot.json)
    assert_contains "$(grep '#81' <<<"$out")" "merge by hand"
    jq '(.prs[] | select(.number == 90)) += {checks: "pending"}' snapshot.json >x && mv x snapshot.json
    out=$(COLUMNS=120 "$FACTORY_TEST_SCRIPTS/overview.sh" snapshot.json)
    assert_contains "$(grep '#81' <<<"$out")" "review the PR"
}
