# Tests of the guard hook on ordinary job work, on messages (refused
# when they name a command only David runs: jobs write messages to
# files), and on quote, case, alias, heredoc and substitution tricks.

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
        'git commit -m "Drop xargs from the allow list"' \
        'git commit -F .factory/commit-msg.txt' \
        'gh pr create --draft --base main --title x --body-file .factory/pr-body.md' \
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
    heredoc=$(printf 'git commit -F - <<%s\nHarden the guard against aliases.\n%s' EOF EOF)
    allowed "$heredoc"
}

test_inline_messages_naming_davids_commands_are_refused() {
    with_bot
    refused 'git commit -m "Drop xargs; gh pr merge stays refused"' \
        "git commit -m 'Refuse env -u GH_TOKEN and gh auth'"
}

test_heredocs_substitutions_and_compound_commands_are_refused() {
    with_bot
    local c
    for c in "$(printf 'cat <<EOF\n$(gh pr merge 81)\nEOF')" \
        "$(printf 'cat <<EOF\n`gh pr merge 81`\nEOF')" \
        "$(printf 'git commit -F- <<EOF\nx ${x:-$(git push origin main)}\nEOF')" \
        "echo \"\$(echo ')' ; gh pr merge 81)\"" \
        'echo "$(case a in a) gh pr merge 81;; esac)"' \
        "$(printf 'echo $((1<<2))\ngh pr merge 81')" \
        "$(printf '# do not worry\ngh pr merge 81')" \
        "git -c protocol.ext.allow=always ls-remote 'ext::gh pr merge 81'" \
        "GIT_EDITOR='sh x' git commit --amend" \
        "GIT_SEQUENCE_EDITOR='sh x' git rebase -i HEAD~2" \
        'FOO=1 git push origin main' \
        '{ git push origin main; }' \
        'if true; then git push origin main; fi' \
        '! git push origin main' \
        '{ git branch -D issue-screenshots; }' \
        "gh api -X PUT repos/dajac/kaos/pulls/81/mer''ge" \
        "gh api repos/dajac/kaos/pulls/81/reviews -f event=APP''ROVE"; do
        refused "$c"
    done
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

test_substitutions_and_escapes_are_refused() {
    with_bot
    refused 'git commit -m "fix $(gh issue close 75)"' \
        'echo "$(gh pr merge 81)"' \
        '"$(gh pr review 81 --approve)"' \
        'echo "`gh pr merge 81`"' \
        'echo "$(GH_TOKEN=x gh pr list)"' \
        "echo \"it's\"; gh pr merge 81; echo \"ok's\"" \
        'gh pr mer\ge 81' \
        'gh issue clo\se 75' \
        'git credential-osx\keychain get' \
        'git push origin HEAD:mai\n' \
        "git rebase -x 'gh pr merge 81' main" \
        "git difftool -x 'sh x' HEAD" \
        'git filter-branch --tree-filter x' \
        "git -c core.editor='sh x' commit" \
        'gh -R dajac/kaos pr merge 81' \
        'gh --repo dajac/kaos issue close 75' \
        'gh pr review 81 -ab ok' \
        'git push origin --all' \
        'git push origin --mirror' \
        "git push origin 'refs/heads/*:refs/heads/*'" \
        'git config remote.origin.push HEAD:main'
    local hidden
    hidden=$(printf 'echo "<<EOF"\ngh pr merge 81\nEOF' )
    refused "$hidden"
    hidden=$(printf 'cat <<<word\ngh issue close 75')
    refused "$hidden"
}

test_reads_and_patterns_pass() {
    with_bot
    allowed "grep -rn 'GH_TOKEN=' scripts" \
        'grep -rn "PATH=" scripts/factory' \
        'git config user.name' \
        'git config --get remote.origin.url' \
        "jq -r '.[] | select(.name == \"x\")' f.json" \
        'git push -u origin 75-fix-cells'
}
