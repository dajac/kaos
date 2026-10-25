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

test_keeps_the_co_authors_and_retargets_the_children_first() {
    ready_pr
    printf '%s\n' '[{"commit": {"message": "Fix\n\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"}},' \
        '{"commit": {"message": "Test\n\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"}}]' \
        >fake/api/repos_dajac_kaos_pulls_81_commits
    echo '[{"number": 82}]' >fake/prs.json
    merge 81 >/dev/null
    [[ $(grep -c 'Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>' fake/calls) == 1 ]] ||
        fail "the co-author is missing or repeated: $(grep -c Co-Authored-By fake/calls)"
    edit=$(grep -n 'pr edit 82' fake/calls | cut -d: -f1)
    delete=$(grep -n -- '-X DELETE' fake/calls | cut -d: -f1)
    [[ -n $edit && $edit -lt $delete ]] || fail "#82 was not retargeted before the branch was deleted"
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

# A ready PR #81 of the bot for the loop: #75 is in-review, the
# approval has no note, GitHub sees no conflict.
auto_pr() {
    rm -rf fake home
    ready_pr
    jq '.author = {login: "app/kaos-agent", is_bot: true} | .closingIssuesReferences = [{number: 75}]' fake/pr-81.json >x && mv x fake/pr-81.json
    echo '{"number": 75, "state": "OPEN", "labels": [{"name": "in-review"}, {"name": "accepted"}]}' >fake/issue-75.json
    echo '[{"id": 7, "user": {"login": "dajac"}, "state": "APPROVED", "commit_id": "abc", "body": "", "submitted_at": "2026-10-20T10:00:00Z"}]' \
        >fake/api/repos_dajac_kaos_pulls_81_reviews
    echo '{"data": {"repository": {"pullRequest": {"mergeable": "MERGEABLE", "reviewThreads": {"totalCount": 0, "nodes": []}}}}}' >fake/api/graphql
}

auto() {
    FACTORY_IDENTITY='kaos-agent[bot]' "$FACTORY_TEST_SCRIPTS/merge.sh" --auto "$@" 2>&1
}

# auto 81, expecting it held with <reason>: exit 3 and no merge.
held() {
    local rc=0 out
    out=$(auto 81) || rc=$?
    assert_eq "$rc" 3 "$out"
    assert_contains "$out" "$1"
    ! grep -q -- '-X PUT' fake/calls || fail "a merge was attempted"
}

test_auto_merges_a_ready_bot_pr() {
    auto_pr
    auto 81 >/dev/null
    call=$(grep -- '-X PUT' fake/calls)
    assert_contains "$call" "commit_title=MINOR: Fix the cells (#81)"
    assert_contains "$call" "sha=abc"
    ! grep -q -- '-f body=' fake/calls || fail "a comment was posted"
}

test_auto_merges_a_bare_approval() {
    for body in 'LGTM.' ' lgtm ' 'Looks good to me!' 'Ship it'; do
        auto_pr
        jq --arg b "$body" '.[0].body = $b' fake/api/repos_dajac_kaos_pulls_81_reviews >x && mv x fake/api/repos_dajac_kaos_pulls_81_reviews
        auto 81 >/dev/null || fail "'$body' was held"
        rm -f fake/calls
    done
}

test_auto_holds_an_approval_with_a_note_and_says_so_once() {
    auto_pr
    jq '.[0].body = "LGTM. Let'"'"'s file an issue to remove it from the API too."' fake/api/repos_dajac_kaos_pulls_81_reviews >x &&
        mv x fake/api/repos_dajac_kaos_pulls_81_reviews
    held "the approval has a note"
    comment=$(grep -A2 -- '-f body=' fake/calls)
    assert_contains "$comment" "./factory merge 81"
    assert_contains "$comment" "<!-- factory-merge-held: 7 -->"
    jq -n '[{user: {login: "kaos-agent[bot]"}, body: "Approved, but...\n\n<!-- factory-merge-held: 7 -->"}]' >fake/api/repos_dajac_kaos_issues_81_comments
    rm fake/calls
    held "the approval has a note"
    ! grep -q -- '-f body=' fake/calls || fail "the comment was posted again"
}

test_auto_holds_when_david_wrote_after_the_approval() {
    auto_pr
    echo '[{"user": {"login": "dajac"}, "body": "Wait", "created_at": "2026-10-20T11:00:00Z"}]' >fake/api/repos_dajac_kaos_issues_75_comments
    held "dajac wrote after the approval"
    auto_pr
    echo '[{"user": {"login": "dajac"}, "body": "Rename it", "created_at": "2026-10-20T09:59:59Z", "pull_request_review_id": 7}]' \
        >fake/api/repos_dajac_kaos_pulls_81_comments
    held "dajac wrote after the approval"
    auto_pr
    echo '[{"user": {"login": "dajac"}, "body": "Old", "created_at": "2026-10-19T10:00:00Z"}, {"user": {"login": "someone"}, "body": "Hi", "created_at": "2026-10-21T10:00:00Z"}]' \
        >fake/api/repos_dajac_kaos_issues_81_comments
    auto 81 >/dev/null || fail "held for an older comment or someone else's"
}

test_auto_holds_what_does_not_pass_the_gate() {
    auto_pr
    echo '[{"id": 7, "user": {"login": "dajac"}, "state": "APPROVED", "commit_id": "old", "body": "", "submitted_at": "2026-10-20T10:00:00Z"}]' \
        >fake/api/repos_dajac_kaos_pulls_81_reviews
    held "commits after the approval"
    ! grep -q -- '-f body=' fake/calls || fail "a push after the approval got a comment"
    auto_pr
    echo '[{"name": "build", "bucket": "fail"}]' >fake/checks-81.json
    held "CI is not green"
    auto_pr
    echo '[]' >fake/checks-81.json
    held "no CI checks"
    auto_pr
    jq '.headRefName = "spec/75-fix-cells"' fake/pr-81.json >x && mv x fake/pr-81.json
    held "is a spec PR"
    auto_pr
    jq '.author.login = "dajac"' fake/pr-81.json >x && mv x fake/pr-81.json
    held "is not the bot's"
    auto_pr
    jq '.baseRefName = "74-parent"' fake/pr-81.json >x && mv x fake/pr-81.json
    held "not main"
    auto_pr
    echo '{"number": 75, "state": "OPEN", "labels": [{"name": "reviewing"}]}' >fake/issue-75.json
    held "#75 is not in-review"
    auto_pr
    echo '{"data": {"repository": {"pullRequest": {"mergeable": "MERGEABLE", "reviewThreads": {"totalCount": 1, "nodes": [{"isResolved": false}]}}}}}' >fake/api/graphql
    held "1 unresolved review threads"
}

test_auto_waits_for_the_mergeability() {
    auto_pr
    echo '{"data": {"repository": {"pullRequest": {"mergeable": "UNKNOWN", "reviewThreads": {"totalCount": 0, "nodes": []}}}}}' >fake/api/graphql
    held "not computed yet"
    ! grep -q -- '-f body=\|issue edit' fake/calls || fail "it commented or moved the issue"
}

test_auto_sends_a_conflict_back_to_in_progress_once_per_head() {
    auto_pr
    echo '{"data": {"repository": {"pullRequest": {"mergeable": "CONFLICTING", "reviewThreads": {"totalCount": 0, "nodes": []}}}}}' >fake/api/graphql
    held "conflicts with main"
    grep -q 'issue edit 75.*--add-label in-progress' fake/calls || fail "#75 was not moved to in-progress: $(cat fake/calls)"
    assert_contains "$(grep -A2 -- '-f body=' fake/calls)" "<!-- factory-merge-conflict: abc -->"
    jq -n '[{user: {login: "kaos-agent[bot]"}, body: "x <!-- factory-merge-conflict: abc -->"}]' >fake/api/repos_dajac_kaos_issues_81_comments
    rm fake/calls
    held "conflicts with main"
    ! grep -q -- '-f body=' fake/calls || fail "the comment was posted again for the same head"
}

test_auto_needs_the_bots_identity() {
    auto_pr
    out=$("$FACTORY_TEST_SCRIPTS/merge.sh" --auto 81 2>&1) && fail "merged without the bot"
    assert_contains "$out" "runs with the bot's identity"
}

test_davids_merge_ignores_the_note() {
    auto_pr
    jq '.[0].body = "LGTM once the docs are fixed"' fake/api/repos_dajac_kaos_pulls_81_reviews >x && mv x fake/api/repos_dajac_kaos_pulls_81_reviews
    merge 81 >/dev/null
    grep -q -- '-X PUT' fake/calls || fail "David's merge did not merge"
}

test_auto_holds_a_change_request_and_a_draft() {
    auto_pr
    echo '[{"id": 7, "user": {"login": "dajac"}, "state": "APPROVED", "commit_id": "abc", "body": "", "submitted_at": "2026-10-20T10:00:00Z"}, {"id": 8, "user": {"login": "dajac"}, "state": "CHANGES_REQUESTED", "commit_id": "abc", "body": "No", "submitted_at": "2026-10-20T11:00:00Z"}]' \
        >fake/api/repos_dajac_kaos_pulls_81_reviews
    held "not approved by dajac"
    auto_pr
    jq '.isDraft = true' fake/pr-81.json >x && mv x fake/pr-81.json
    held "is a draft"
}

test_auto_fails_without_moving_the_issue_when_github_refuses_the_merge() {
    auto_pr
    rm fake/api/PUT_repos_dajac_kaos_pulls_81_merge
    mkdir -p bin2
    cat >bin2/gh <<'SH'
#!/usr/bin/env bash
[[ " $* " == *" -X PUT "* ]] && { echo "gh: Pull Request is not mergeable (HTTP 405)" >&2; exit 1; }
exec "$(dirname "$0")/../bin/gh" "$@"
SH
    chmod +x bin2/gh
    rc=0
    out=$(PATH=$PWD/bin2:$PATH auto 81) || rc=$?
    assert_eq "$rc" 1 "$out"
    assert_contains "$out" "HTTP 405"
    ! grep -q 'issue edit\|-f body=' fake/calls || fail "it moved the issue or commented"
}

test_auto_comments_a_conflict_again_on_a_new_head() {
    auto_pr
    echo '{"data": {"repository": {"pullRequest": {"mergeable": "CONFLICTING", "reviewThreads": {"totalCount": 0, "nodes": []}}}}}' >fake/api/graphql
    jq -n '[{user: {login: "kaos-agent[bot]"}, body: "x <!-- factory-merge-conflict: old -->"}]' >fake/api/repos_dajac_kaos_issues_81_comments
    held "conflicts with main"
    assert_contains "$(grep -A2 -- '-f body=' fake/calls)" "<!-- factory-merge-conflict: abc -->"
}

test_auto_ignores_a_marker_written_by_someone_else() {
    auto_pr
    jq '.[0].body = "LGTM once renamed"' fake/api/repos_dajac_kaos_pulls_81_reviews >x && mv x fake/api/repos_dajac_kaos_pulls_81_reviews
    jq -n '[{user: {login: "someone"}, body: "<!-- factory-merge-held: 7 -->"}]' >fake/api/repos_dajac_kaos_issues_81_comments
    held "the approval has a note"
    grep -q -- '-f body=' fake/calls || fail "the held comment was suppressed by someone else's marker"
}

# The loop's merge pass, loaded from ./factory, with a fake merge.sh
# that exits with the code in fake/rc-<PR>.
merge_pass_setup() {
    mkdir -p fake/api bin scripts state
    export FAKE_GH=$PWD/fake FACTORY_STATE=$PWD/state FACTORY_REPO=dajac/kaos
    cp "$FACTORY_TEST_SCRIPTS/test/fake-gh" bin/gh
    PATH=$PWD/bin:$PATH
    cp "$FACTORY_TEST_SCRIPTS/merge-candidates.jq" scripts/
    cat >scripts/merge.sh <<'SH'
#!/usr/bin/env bash
echo "factory: merge.sh $*"
exit "$(cat "$FAKE_GH/rc-$2")"
SH
    chmod +x scripts/merge.sh
    echo '{"issues": [{"number": 75, "stage": "in-review"}], "prs": [{"number": 81, "author": "app/kaos-agent", "head": "75-a", "base": "main", "draft": false, "review": "APPROVED", "checks": "pass", "closes": [75]}], "closed_prs": []}' >snapshot.json
    log() { echo "$*" >>loop.log; }
    eval "$(sed -n '/^merge_pass() {/,/^}/p' "$FACTORY_TEST_SCRIPTS/../../factory")"
}

test_merge_pass_comments_once_after_three_failures_in_a_row() {
    merge_pass_setup
    echo 1 >fake/rc-81
    merge_pass snapshot.json scripts || fail "a tried candidate must return true"
    merge_pass snapshot.json scripts
    ! grep -q -- '-f body=' fake/calls 2>/dev/null || fail "commented before the third failure"
    merge_pass snapshot.json scripts
    assert_eq "$(grep -c -- '-f body=' fake/calls)" 1
    merge_pass snapshot.json scripts
    assert_eq "$(grep -c -- '-f body=' fake/calls)" 1
    assert_contains "$(cat loop.log)" "merge: #81: factory: merge.sh --auto 81"
}

test_merge_pass_resets_the_count_on_a_hold_and_for_a_non_candidate() {
    merge_pass_setup
    echo 1 >fake/rc-81
    merge_pass snapshot.json scripts
    merge_pass snapshot.json scripts
    echo 3 >fake/rc-81
    merge_pass snapshot.json scripts
    [[ ! -f state/merge-failures/81 ]] || fail "a hold kept the count"
    echo 1 >fake/rc-81
    merge_pass snapshot.json scripts
    echo 2 >state/merge-failures/81
    echo '{"issues": [], "prs": [], "closed_prs": []}' >snapshot.json
    merge_pass snapshot.json scripts && fail "no candidate must return false"
    [[ ! -f state/merge-failures/81 ]] || fail "the count of a non-candidate was kept"
}
