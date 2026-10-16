# Tests of activity.sh: the last handoff, David's new activity after
# its watermark, and the issue's PRs.

setup_fake() {
    mkdir -p fake/api bin
    export FAKE_GH=$PWD/fake
    cp "$FACTORY_TEST_SCRIPTS/test/fake-gh" bin/gh
    PATH=$PWD/bin:$PATH
    git init -q home
    export FACTORY_HOME=$PWD/home
}

handoff_comment() {
    jq -n --arg at "$1" --arg seen "$2" '{
        user: {login: "kaos-agent[bot]"}, created_at: $at, html_url: "https://x/c1",
        body: ("Triage of the issue.\n\n```yaml\nfactory:\n  job: triage\n  route: build\n  seen_until: " + $seen + "\n```\n")
    }'
}

comment() {
    jq -n --arg who "$1" --arg at "$2" --arg body "$3" '{user: {login: $who}, created_at: $at, html_url: "https://x/c", body: $body}'
}

test_finds_the_handoff_and_davids_comments_after_it() {
    setup_fake
    jq -s . <(comment dajac 2026-10-01T10:00:00Z "before") \
        <(handoff_comment 2026-10-01T12:00:00Z 2026-10-01T11:00:00Z) \
        <(comment dajac 2026-10-01T11:30:00Z "while the job ran") \
        <(comment someone 2026-10-01T13:00:00Z "not David") >fake/api/repos_dajac_kaos_issues_75_comments
    echo '[{"event": "labeled", "label": {"name": "accepted"}, "actor": {"login": "dajac"}, "created_at": "2026-10-02T09:00:00Z"}]' >fake/api/repos_dajac_kaos_issues_75_timeline
    out=$("$FACTORY_TEST_SCRIPTS/activity.sh" 75)
    assert_eq "$(jq -r .handoff.job <<<"$out")" triage
    assert_eq "$(jq -r .handoff.seen_until <<<"$out")" 2026-10-01T11:00:00Z
    assert_eq "$(jq -r .handoff.fields.route <<<"$out")" build
    assert_eq "$(jq -r .labeled.accepted <<<"$out")" 2026-10-02T09:00:00Z
    assert_eq "$(jq -r '[.david[].body] | join("|")' <<<"$out")" "while the job ran"
    assert_eq "$(jq -r .accepted_by <<<"$out")" dajac
    assert_eq "$(jq -r '[.others[] | .author + ":" + .body] | join("|")' <<<"$out")" "someone:not David"
}

test_without_a_handoff_every_comment_of_david_is_new() {
    setup_fake
    jq -s . <(comment dajac 2026-10-01T10:00:00Z "hello") >fake/api/repos_dajac_kaos_issues_75_comments
    out=$("$FACTORY_TEST_SCRIPTS/activity.sh" 75)
    assert_eq "$(jq -r .handoff <<<"$out")" null
    assert_eq "$(jq -r '.david | length' <<<"$out")" 1
}

test_reports_the_prs_of_the_issue_with_davids_reviews() {
    setup_fake
    jq -s . <(handoff_comment 2026-10-01T12:00:00Z 2026-10-01T12:00:00Z) >fake/api/repos_dajac_kaos_issues_75_comments
    cat >fake/prs.json <<'JSON'
[
  {"number": 81, "title": "Fix", "state": "OPEN", "isDraft": false, "headRefName": "75-fix-cells", "baseRefName": "main",
   "headRefOid": "abc", "mergedAt": null, "statusCheckRollup": [{"status": "COMPLETED", "conclusion": "SUCCESS"}]},
  {"number": 90, "title": "Other", "state": "OPEN", "isDraft": false, "headRefName": "750-other", "baseRefName": "main",
   "headRefOid": "def", "mergedAt": null, "statusCheckRollup": []}
]
JSON
    echo '[{"user": {"login": "dajac"}, "state": "CHANGES_REQUESTED", "commit_id": "abc", "submitted_at": "2026-10-01T13:00:00Z", "html_url": "r", "body": "please"}]' \
        >fake/api/repos_dajac_kaos_pulls_81_reviews
    echo '{"data": {"repository": {"pullRequest": {"reviewThreads": {"nodes": [{"isResolved": false}, {"isResolved": true}]}}}}}' >fake/api/graphql
    out=$("$FACTORY_TEST_SCRIPTS/activity.sh" 75)
    assert_eq "$(jq -r '[.prs[].number] | join(",")' <<<"$out")" 81
    assert_eq "$(jq -r '.prs[0] | "\(.checks) \(.david_review.state) \(.unresolved)"' <<<"$out")" "pass CHANGES_REQUESTED 1"
    assert_eq "$(jq -r '.david[0] | "\(.kind) \(.pr) \(.state)"' <<<"$out")" "review 81 CHANGES_REQUESTED"
}
