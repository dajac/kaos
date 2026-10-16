# Tests of David's merge: its gates and the squash commit message.

test_commit_message_is_the_body_above_the_rule() {
    FACTORY_HOME=$PWD . "$FACTORY_TEST_SCRIPTS/lib.sh"
    body=$'<!--\nA comment.\n-->\nThis patch fixes x.\r\n\n- One\n\nCloses #75\n\n---\n\n## How to test\n\n    ./factory try 81\n'
    assert_eq "$(commit_message <<<"$body")" $'This patch fixes x.\n\n- One\n\nCloses #75'
    assert_eq "$(commit_message <<<$'No rule at all.\n')" 'No rule at all.'
}

# A ready PR #81: approved by David on its head, threads resolved, CI
# green.
ready_pr() {
    mkdir -p fake/api bin
    export FAKE_GH=$PWD/fake
    cp "$FACTORY_TEST_SCRIPTS/test/fake-gh" bin/gh
    PATH=$PWD/bin:$PATH
    git init -q home
    export FACTORY_HOME=$PWD/home
    echo '{"login": "dajac"}' >fake/api/user
    jq -n '{number: 81, title: "MINOR: Fix the cells", body: "This patch fixes the cells.\n\nCloses #75\n\n---\n\n## How to test\n",
        state: "OPEN", isDraft: false, baseRefName: "main", headRefName: "75-fix-cells", headRefOid: "abc"}' >fake/pr-81.json
    echo '[{"user": {"login": "dajac"}, "state": "APPROVED", "commit_id": "abc"}]' >fake/api/repos_dajac_kaos_pulls_81_reviews
    echo '{"data": {"repository": {"pullRequest": {"reviewThreads": {"totalCount": 1, "nodes": [{"isResolved": true}]}}}}}' >fake/api/graphql
    echo '[{"name": "build", "bucket": "pass"}]' >fake/checks-81.json
    echo '{"sha": "m3rg3d"}' >fake/api/PUT_repos_dajac_kaos_pulls_81_merge
    echo '{"commit": {"author": {"name": "kaos-agent[bot]", "email": "1+kaos-agent[bot]@users.noreply.github.com"}}}' >fake/api/repos_dajac_kaos_commits_m3rg3d
}

merge() {
    "$FACTORY_TEST_SCRIPTS/merge.sh" "$@" 2>&1
}

test_merges_a_ready_pr_with_the_message_above_the_rule() {
    ready_pr
    merge 81 >/dev/null
    call=$(grep -- '-X PUT' fake/calls)
    assert_contains "$call" "commit_title=MINOR: Fix the cells (#81)"
    assert_contains "$call" "sha=abc"
    assert_contains "$(grep -A3 -- '-X PUT' fake/calls)" "Closes #75"
    ! grep -q "How to test" fake/calls || fail "the review material went into the message"
    grep -q -- '-X DELETE repos/dajac/kaos/git/refs/heads/75-fix-cells' fake/calls || fail "the branch was not deleted"
}

test_refuses_without_davids_approval_of_the_head() {
    ready_pr
    echo '[{"user": {"login": "dajac"}, "state": "APPROVED", "commit_id": "old"}]' >fake/api/repos_dajac_kaos_pulls_81_reviews
    out=$(merge 81) && fail "merged"
    assert_contains "$out" "commits after the approval"
    echo '[{"user": {"login": "dajac"}, "state": "APPROVED", "commit_id": "abc"}, {"user": {"login": "dajac"}, "state": "CHANGES_REQUESTED", "commit_id": "abc"}]' \
        >fake/api/repos_dajac_kaos_pulls_81_reviews
    out=$(merge 81) && fail "merged"
    assert_contains "$out" "not approved by dajac"
}

test_refuses_unresolved_threads_failing_checks_and_drafts() {
    ready_pr
    echo '{"data": {"repository": {"pullRequest": {"reviewThreads": {"totalCount": 1, "nodes": [{"isResolved": false}]}}}}}' >fake/api/graphql
    out=$(merge 81) && fail "merged with a thread"
    assert_contains "$out" "1 unresolved review threads"
    ready_pr
    echo '[{"name": "build", "bucket": "pending"}]' >fake/checks-81.json
    out=$(merge 81) && fail "merged while pending"
    assert_contains "$out" "CI is not green: build: pending"
    ready_pr
    jq '.isDraft = true' fake/pr-81.json >x && mv x fake/pr-81.json
    out=$(merge 81) && fail "merged a draft"
    assert_contains "$out" "is a draft"
    ! grep -q -- '-X PUT' fake/calls || fail "a merge was attempted"
}

test_refuses_the_bots_identity_and_other_logins() {
    ready_pr
    out=$(FACTORY_IDENTITY='kaos-agent[bot]' merge 81) && fail "merged as the bot"
    assert_contains "$out" "run ./factory merge in your own terminal"
    echo '{"login": "someone"}' >fake/api/user
    out=$(merge 81) && fail "merged as someone"
    assert_contains "$out" "not dajac"
}

test_does_not_repeat_the_pr_number_in_the_title() {
    ready_pr
    jq '.title = "MINOR: Fix the cells (#81)"' fake/pr-81.json >x && mv x fake/pr-81.json
    merge 81 >/dev/null
    assert_contains "$(grep -- '-X PUT' fake/calls)" "commit_title=MINOR: Fix the cells (#81) -f"
}
