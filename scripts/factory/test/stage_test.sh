# Tests of stage.sh: the legal transitions and David's gates.

setup_fake() {
    mkdir -p fake/api bin
    export FAKE_GH=$PWD/fake
    cp "$FACTORY_TEST_SCRIPTS/test/fake-gh" bin/gh
    PATH=$PWD/bin:$PATH
    git init -q home
    export FACTORY_HOME=$PWD/home
}

# issue N label...
issue() {
    local n=$1
    shift
    jq -n --argjson n "$n" '{number: $n, state: "OPEN", labels: ($ARGS.positional | map({name: .}))}' --args "$@" >"fake/issue-$n.json"
}

# Who added the accepted label to issue N.
accepted_by() {
    jq -n --arg who "$2" '[{event: "labeled", label: {name: "accepted"}, actor: {login: $who}}]' \
        >"fake/api/repos_dajac_kaos_issues_$1_timeline"
}

stage() {
    "$FACTORY_TEST_SCRIPTS/stage.sh" "$@"
}

edits() {
    grep '^issue edit' fake/calls || true
}

test_moves_the_stage_label() {
    setup_fake
    issue 5 needs-triage bug
    assert_eq "$(stage 5 triaged)" "#5: needs-triage -> triaged"
    assert_eq "$(edits)" "issue edit 5 --repo dajac/kaos --add-label triaged --remove-label needs-triage"
}

test_an_issue_without_a_stage_goes_to_triage_only() {
    setup_fake
    issue 5 bug
    stage 5 needs-triage >/dev/null
    issue 6 bug
    out=$(stage 6 queued 2>&1) && fail "jumped to queued"
    assert_contains "$out" "none -> queued is not a legal transition"
}

test_illegal_transitions_are_refused() {
    setup_fake
    issue 5 triaged
    out=$(stage 5 in-review 2>&1) && fail "moved"
    assert_contains "$out" "triaged -> in-review is not a legal transition"
    assert_eq "$(edits)" ""
}

test_accepting_needs_david() {
    setup_fake
    issue 5 triaged
    out=$(stage 5 queued 2>&1) && fail "queued without accepted"
    assert_contains "$out" "needs the accepted label"
    issue 5 triaged accepted
    accepted_by 5 'kaos-agent[bot]'
    out=$(stage 5 queued 2>&1) && fail "queued with the bot's accepted"
    assert_contains "$out" "added by dajac"
    accepted_by 5 dajac
    assert_eq "$(stage 5 queued)" "#5: triaged -> queued"
}

test_building_needs_no_spec() {
    setup_fake
    issue 5 queued accepted needs-spec
    accepted_by 5 dajac
    out=$(stage 5 in-progress 2>&1) && fail "built without a spec"
    assert_contains "$out" "needs an approved spec first"
    assert_eq "$(stage 5 spec-review)" "#5: queued -> spec-review"
}

test_approved_spec_removes_needs_spec() {
    setup_fake
    issue 5 spec-review accepted needs-spec
    echo '{"headRefOid": "abc"}' >fake/pr-88.json
    out=$(stage 5 queued 2>&1) && fail "queued without the spec PR"
    echo '[{"user": {"login": "dajac"}, "state": "APPROVED", "commit_id": "old"}]' >fake/api/repos_dajac_kaos_pulls_88_reviews
    out=$(stage 5 queued --spec-approved 88 2>&1) && fail "approval of an older head accepted"
    assert_contains "$out" "not approved by dajac on its head"
    echo '[{"user": {"login": "dajac"}, "state": "APPROVED", "commit_id": "abc"}, {"user": {"login": "dajac"}, "state": "COMMENTED", "commit_id": "abc"}]' >fake/api/repos_dajac_kaos_pulls_88_reviews
    assert_eq "$(stage 5 queued --spec-approved 88)" "#5: spec-review -> queued"
    assert_eq "$(edits)" "issue edit 5 --repo dajac/kaos --add-label queued --remove-label spec-review --remove-label needs-spec"
}

test_blocked_comes_from_and_returns_to_any_stage() {
    setup_fake
    issue 5 reviewing
    stage 5 blocked >/dev/null
    issue 5 blocked
    assert_eq "$(stage 5 reviewing)" "#5: blocked -> reviewing"
}

test_several_stage_labels_are_refused() {
    setup_fake
    issue 5 queued in-progress
    out=$(stage 5 reviewing 2>&1) && fail "moved"
    assert_contains "$out" "several stage labels"
}
