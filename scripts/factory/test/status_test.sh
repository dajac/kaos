# Tests of status.sh (the snapshot) and cleanup.sh.

setup_fake() {
    mkdir -p fake/api bin
    export FAKE_GH=$PWD/fake
    cp "$FACTORY_TEST_SCRIPTS/test/fake-gh" bin/gh
    PATH=$PWD/bin:$PATH
    git init -q home
    export FACTORY_HOME=$PWD/home
}

test_snapshot_classifies_ci_and_stages() {
    setup_fake
    cat >fake/issues.json <<'JSON'
[
  {"number": 5, "title": "A", "author": {"login": "dajac"}, "labels": [{"name": "queued"}, {"name": "accepted"}], "updatedAt": "u", "createdAt": "c"},
  {"number": 6, "title": "B", "author": {"login": "dajac"}, "labels": [{"name": "queued"}, {"name": "blocked"}], "updatedAt": "u", "createdAt": "c"},
  {"number": 7, "title": "Pipeline", "author": {"login": "kaos-agent"}, "labels": [{"name": "pipeline"}], "updatedAt": "u", "createdAt": "c"}
]
JSON
    cat >fake/prs.json <<'JSON'
[
  {"number": 81, "title": "x", "author": {"login": "app/kaos-agent"}, "headRefName": "5-a", "baseRefName": "main", "isDraft": true, "updatedAt": "u",
   "closingIssuesReferences": [{"number": 5}], "reviewDecision": null,
   "statusCheckRollup": [{"__typename": "CheckRun", "status": "COMPLETED", "conclusion": "SUCCESS"}, {"__typename": "CheckRun", "status": "IN_PROGRESS", "conclusion": ""}]},
  {"number": 82, "title": "y", "author": {"login": "app/kaos-agent"}, "headRefName": "6-b", "baseRefName": "main", "isDraft": false, "updatedAt": "u",
   "closingIssuesReferences": [], "reviewDecision": null,
   "statusCheckRollup": [{"__typename": "CheckRun", "status": "COMPLETED", "conclusion": "STARTUP_FAILURE"}]},
  {"number": 83, "title": "z", "author": {"login": "dajac"}, "headRefName": "x", "baseRefName": "main", "isDraft": false, "updatedAt": "u",
   "closingIssuesReferences": [], "reviewDecision": null,
   "statusCheckRollup": [{"__typename": "StatusContext", "state": "SUCCESS"}]}
]
JSON
    out=$("$FACTORY_TEST_SCRIPTS/status.sh")
    assert_eq "$(jq -r '[.issues[] | "\(.number):\(.stage)"] | join(",")' <<<"$out")" "5:queued,6:invalid"
    assert_eq "$(jq -r '[.prs[] | "\(.number):\(.checks)"] | join(",")' <<<"$out")" "81:pending,82:fail,83:pass"
    assert_eq "$(jq -r '.prs[0].closes | join(",")' <<<"$out")" 5
}

test_cleanup_touches_only_factory_branches() {
    setup_fake
    git -C home commit -q --allow-empty -m init
    out=$("$FACTORY_TEST_SCRIPTS/cleanup.sh" factory-3-loop 2>&1) && fail "cleaned a branch of David's"
    assert_contains "$out" "not a factory branch"
    git -C home worktree add -q -b 75-fix-cells "$FACTORY_HOME/.claude/worktrees/75-fix-cells"
    touch "$FACTORY_HOME/.claude/worktrees/75-fix-cells/wip"
    out=$("$FACTORY_TEST_SCRIPTS/cleanup.sh" 75-fix-cells 2>&1) && fail "removed a dirty worktree"
    assert_contains "$out" "uncommitted changes"
    rm "$FACTORY_HOME/.claude/worktrees/75-fix-cells/wip"
    "$FACTORY_TEST_SCRIPTS/cleanup.sh" 75-fix-cells >/dev/null
    [[ ! -d $FACTORY_HOME/.claude/worktrees/75-fix-cells ]] || fail "the worktree is still there"
    ! git -C home show-ref --quiet refs/heads/75-fix-cells || fail "the branch is still there"
}
