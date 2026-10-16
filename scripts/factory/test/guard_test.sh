# Tests of the guard hook, .claude/hooks/factory-guard.sh: which Bash
# commands it refuses, with and without the bot.

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

without_bot() {
    git init -q home
}

with_bot() {
    git init -q home
    mkdir -p home/.factory
    echo "FACTORY_BOT_LOGIN='kaos-agent[bot]'" >home/.factory/bot.env
}

test_davids_gates_are_refused() {
    without_bot
    refused 'gh pr merge 81 --squash' \
        'cd x && gh pr merge 81' \
        'gh api -X PUT repos/dajac/kaos/pulls/81/merge' \
        './factory merge 81' \
        'scripts/factory/merge.sh 81' \
        'gh issue close 75' \
        'gh api -X PATCH repos/dajac/kaos/issues/75 -f state=closed' \
        'gh issue edit 75 --add-label accepted' \
        'gh issue edit 75 --add-label bug,accepted' \
        'gh issue create --title x --label accepted' \
        'gh api repos/dajac/kaos/issues/75/labels -f "labels[]=accepted"' \
        'gh pr review 81 --approve' \
        'gh pr review 81 -a -b ok' \
        'gh api repos/dajac/kaos/pulls/81/reviews -f event=APPROVE' \
        "gh api graphql -f query='mutation { mergePullRequest(input: {pullRequestId: \"x\"}) { clientMutationId } }'" \
        "gh api graphql -f query='mutation { enablePullRequestAutoMerge(input: {pullRequestId: \"x\"}) { clientMutationId } }'" \
        "gh api graphql -f query='mutation { addPullRequestReview(input: {pullRequestId: \"x\", event: APPROVE}) { clientMutationId } }'" \
        'echo {} > .claude/settings.local.json' \
        'cp /tmp/x .claude/settings.local.json' \
        "gh api graphql -f query='mutation { resolveReviewThread(input: {threadId: \"x\"}) { thread { id } } }'"
}

test_pushes_to_main_and_screenshot_rewrites_are_refused() {
    without_bot
    refused 'git push origin main' \
        'git push origin HEAD:main' \
        'git push -f origin main' \
        'git push origin +75-fix:main' \
        'git push origin refs/heads/main' \
        'git -C /x push origin main' \
        'git push --force origin issue-screenshots' \
        'git push origin --delete issue-screenshots' \
        'git push origin :issue-screenshots' \
        'git branch -D issue-screenshots'
}

test_ordinary_work_is_allowed() {
    without_bot
    allowed 'git push -u origin 75-fix' \
        'git push origin 75-fix && git checkout main' \
        'git push origin issue-screenshots' \
        'git log main..HEAD' \
        'gh pr create --draft --base main --title x --body y' \
        'gh pr close 88' \
        'gh pr review 81 --comment -b "looks good"' \
        'gh issue edit 75 --add-label in-progress --remove-label queued' \
        'gh issue edit 75 --remove-label needs-spec' \
        'gh pr list --label accepted' \
        './mvnw -q verify' \
        'git commit -m "Closes #75"'
}

test_identity_escapes_are_refused_once_the_bot_exists() {
    with_bot
    refused 'GH_TOKEN=x gh pr list' \
        'unset GH_CONFIG_DIR; gh pr list' \
        'env -u GH_TOKEN gh pr list' \
        '/opt/homebrew/bin/gh pr list' \
        'command /opt/homebrew/bin/gh pr list' \
        '\gh pr list' \
        'gh auth status' \
        'gh auth token' \
        'git -c credential.helper=osxkeychain push origin x' \
        'git -c user.name=David commit -m x' \
        'git config user.email david@example.com' \
        'git config --global credential.helper osxkeychain' \
        'git commit --author="David <d@example.com>" -m x' \
        'GIT_AUTHOR_NAME=David git commit -m x' \
        'GIT_SSH_COMMAND=ssh git push origin x' \
        'ssh -T git@github.com' \
        'PATH=/opt/homebrew/bin git push' \
        'git push --no-verify origin 75-fix' \
        'git commit -n -m x' \
        'git -c core.hooksPath=/dev/null push origin 75-fix' \
        'git config core.hooksPath .git/hooks'
    allowed 'gh pr list' \
        "$FACTORY_TEST_SCRIPTS/bin/gh pr list" \
        'git config --get user.name' \
        'git log --author=kaos-agent' \
        'git commit -m x'
}

test_identity_escapes_are_allowed_before_the_bot_exists() {
    without_bot
    allowed 'gh auth status' 'git commit --author="David <d@example.com>" -m x'
}

test_other_tools_pass() {
    without_bot
    jq -n '{tool_name: "Edit", tool_input: {file_path: "x"}}' |
        "$FACTORY_TEST_SCRIPTS/../../.claude/hooks/factory-guard.sh" || fail "Edit refused"
}
