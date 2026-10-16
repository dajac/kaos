# Tests of the gates' verdict line and of the jobs' settings.

settings=$FACTORY_TEST_SCRIPTS/../../.claude/factory-job.json

test_gates_end_with_a_verdict_on_a_bad_level() {
    git init -q repo && cd repo
    out=$("$FACTORY_TEST_SCRIPTS/gates.sh" fast) && fail "gates passed"
    assert_eq "$(tail -n 1 <<<"$out")" "GATES: MISCONFIGURED unknown level 'fast', expected quick or full"
}

test_gates_outside_a_worktree_are_misconfigured() {
    out=$("$FACTORY_TEST_SCRIPTS/gates.sh" quick) && fail "gates passed"
    assert_eq "$(tail -n 1 <<<"$out")" "GATES: MISCONFIGURED not in a git worktree"
}

test_job_settings_keep_the_hooks_and_deny_davids_commands() {
    assert_eq "$(jq .disableAllHooks "$settings")" false
    assert_contains "$(jq -r '.hooks.PreToolUse[].hooks[].command' "$settings")" '"$FACTORY_HOME"/.claude/hooks/factory-guard.sh'
    assert_contains "$(jq -r '.hooks.SessionStart[].hooks[].command' "$settings")" '"$FACTORY_HOME"/.claude/hooks/session-identity.sh'
    deny=$(jq -r '.permissions.deny[]' "$settings")
    assert_contains "$deny" 'Bash(gh pr merge *)'
    assert_contains "$deny" 'Bash(git push * main)'
    assert_contains "$deny" 'Bash(./factory merge *)'
    if jq -e '.permissions.allow[] | select(. == "Bash" or . == "Bash(*)")' "$settings" >/dev/null; then
        fail "the jobs may run any command"
    fi
}
