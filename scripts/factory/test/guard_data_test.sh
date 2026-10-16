# Tests of the guard hook against the false positives of whole-line
# checks (messages, bodies and patterns are data) and against quote,
# case and alias tricks.

guard() {
    jq -n --arg c "$1" --arg cwd "$PWD/home" '{tool_name: "Bash", tool_input: {command: $c}, cwd: $cwd}' |
        CLAUDE_PROJECT_DIR=$PWD/home "$FACTORY_TEST_SCRIPTS/../../.claude/hooks/factory-guard.sh" 2>/dev/null
}

refused() {
    local c
    for c in "$@"; do
        guard "$c" && fail "allowed: $c"
        [[ $? == 2 ]] || fail "exit code $? for: $c"
    done
    return 0
}

allowed() {
    local c
    for c in "$@"; do
        guard "$c" || fail "refused: $c"
    done
}

with_bot() {
    git init -q home
    mkdir -p home/.factory
    echo "FACTORY_BOT_LOGIN='kaos-agent[bot]'" >home/.factory/bot.env
}

test_messages_bodies_and_patterns_are_data() {
    with_bot
    allowed 'git commit -m "Harden security of the guard"' \
        'git commit -m "Drop xargs from the allow list; gh pr merge stays refused"' \
        "git commit -m 'Refuse env -u GH_TOKEN and gh auth'" \
        'grep -rn security docs' \
        'grep -c user. file' \
        'wc -c alias.txt' \
        'grep -c credential x' \
        'gh pr list --label in-review' \
        "gh api repos/dajac/kaos/issues/75/labels --jq '.[].name'" \
        'git log --author=kaos-agent' \
        'git config --get user.name' \
        'gh pr comment 81 --body "Fixed: the merge script now retargets the children"' \
        'bash -n scripts/factory/merge.sh' \
        'git diff -- scripts/factory/merge.sh'
    local heredoc
    heredoc=$(printf 'git commit -F - <<%s\nNever run gh pr merge or gh issue close.\n%s' EOF EOF)
    allowed "$heredoc"
}

test_quote_and_case_tricks_and_aliases_are_refused() {
    with_bot
    refused 'gh pr "merge" 81' \
        'scripts/factory/merge.sh 81' \
        'cd x && ./scripts/factory/merge.sh 81' \
        "gh 'issue' close 75" \
        'gh alias set m "pr merge"' \
        'git -c Alias.x=y x' \
        'git --config-env=credential.helper=H push origin x' \
        "git config alias.x '!sh x.sh'" \
        "bash -c 'echo hi'" \
        'eval echo' \
        'cat ~/.git-credentials' \
        'cat ~/.config/gh/hosts.yml' \
        'gh issue edit 75 --add-label In-Review' \
        "gh api -X PATCH repos/dajac/kaos/issues/75 -f 'labels[]=in-review'" \
        'scripts/factory/stage.sh 75 queued; gh issue edit 75 --add-label in-review'
}
