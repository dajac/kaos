#!/usr/bin/env bash
# PreToolUse hook of every Claude session in Kaos, jobs and interactive
# sessions alike: refuses the Bash commands that only David may run, and
# the ones that would act as David instead of the bot.
#
# It reads the hook input on stdin and exits 2, with the reason on
# stderr, to refuse a command. It is a guardrail, not a security
# boundary: the skills state the same rules, and the jobs' settings
# repeat the main ones as deny rules.
set -uo pipefail

input=$(cat)
[[ $(jq -r '.tool_name // empty' <<<"$input") == Bash ]] || exit 0
command=$(jq -r '.tool_input.command // empty' <<<"$input" | tr '\n\t' '  ' | tr -s ' ')

refuse() {
    echo "factory-guard: refused, $1. See docs/factory/README.md, \"Guardrails\"." >&2
    exit 2
}

# The simple commands of the line, one per element: split on ;, &, |,
# && and ||, and on $( and backquotes. Leading spaces and env-free
# prefixes ("command", "builtin", "exec") are dropped.
segments=()
while IFS= read -r segment; do
    segment=${segment#"${segment%%[! ]*}"}
    while [[ $segment =~ ^(command|builtin|exec|time|nohup)\ +(.*)$ ]]; do
        segment=${BASH_REMATCH[2]}
    done
    [[ -n $segment ]] && segments+=("$segment")
done < <(sed -E 's/\$\(|`|&&|\|\||[;&|()]/\n/g' <<<"$command")

# True when the whole line matches the regex.
line_has() {
    [[ $command =~ $1 ]]
}

project=${CLAUDE_PROJECT_DIR:-$(jq -r '.cwd // empty' <<<"$input")}
home=$(dirname "$(git -C "${project:-.}" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)" 2>/dev/null)
bot=false
[[ -n $home && -f $home/.factory/bot.env ]] && bot=true

# Anywhere in the line: the merge API, the review-thread resolution and
# the approval of a PR through GraphQL or REST.
line_has 'pulls/[0-9]+/merge' && refuse "merging a PR is David's"
line_has 'merge\.sh' && refuse "merging a PR is David's"
line_has 'resolveReviewThread' && refuse "resolving a review thread is David's"
line_has 'mergePullRequest|enablePullRequestAutoMerge' && refuse "merging a PR is David's"
line_has 'addPullRequestReview' && line_has 'APPROVE' && refuse "approving a PR is David's"
line_has '\.claude/settings\.local\.json' && line_has '(>|tee|cp|mv|ln|install|rsync)' &&
    refuse "a local settings file would loosen the session's permissions"
line_has 'event[^a-zA-Z]*APPROVE' && refuse "approving a PR is David's"
line_has '/issues/[0-9]+[^ ]* .*state[^a-z]*closed' && refuse "closing an issue is David's"
line_has '/labels' && line_has 'accepted' && refuse "the accepted label is David's"

for s in ${segments[@]+"${segments[@]}"}; do
    case $s in
    gh\ * | */gh\ *)
        [[ $s =~ (^|/)gh\ +pr\ +merge ]] && refuse "merging a PR is David's"
        [[ $s =~ (^|/)gh\ +issue\ +close ]] && refuse "closing an issue is David's"
        [[ $s =~ (^|/)gh\ +pr\ +review.*(--approve|\ -a(\ |$)) ]] && refuse "approving a PR is David's"
        [[ $s =~ (^|/)gh\ +(issue|pr)\ +(create|edit)\ .*(--add-label|--label|-l)[\ =]+([^\ ]*,)?[\"\']?accepted ]] &&
            refuse "the accepted label is David's"
        ;;
    esac
    case $s in
    factory\ merge* | */factory\ merge*) refuse "merging a PR is David's" ;;
    esac
    if [[ $s =~ ^git(\ +-[^\ ]+(\ +[^-\ ][^\ ]*)?)*\ +push(\ |$) ]]; then
        [[ $s =~ (\ |:|/|\+)main(\ |$)|refs/heads/main ]] && refuse "pushing to main is David's"
        if [[ $s =~ issue-screenshots ]]; then
            [[ $s =~ \ (-f|--force[^\ ]*|--delete|-d)(\ |$)|\ \+|\ :issue-screenshots ]] &&
                refuse "the issue-screenshots branch is never rewritten or deleted"
        fi
    fi
    [[ $s =~ ^git\ +branch\ +.*-[dD].*issue-screenshots ]] && refuse "the issue-screenshots branch is never deleted"

    # Once the bot exists, nothing may run as David: no way around the
    # bot's gh wrapper, token, credential helper, SSH block or author.
    if $bot; then
        [[ $s =~ (^|\ )(GH_TOKEN|GITHUB_TOKEN|GH_CONFIG_DIR|GH_HOST|GIT_CONFIG_[A-Z_0-9]*|GIT_AUTHOR_[A-Z]*|GIT_COMMITTER_[A-Z]*|GIT_SSH[A-Z_]*|GIT_ASKPASS|SSH_ASKPASS|PATH)= ]] &&
            refuse "overriding the bot's identity"
        [[ $s =~ ^(unset|export\ +-n)\ .*(GH_|GITHUB_TOKEN|GIT_|PATH) ]] && refuse "overriding the bot's identity"
        [[ $s =~ ^env\ .*(-u|-i|--unset|--ignore-environment) ]] && refuse "overriding the bot's identity"
        [[ $s =~ ^(\\gh|/[^\ ]*/gh)(\ |$) && ! $s =~ ^/[^\ ]*/scripts/factory/bin/gh(\ |$) ]] &&
            refuse "bypassing the bot's gh wrapper"
        [[ $s =~ ^gh\ +auth ]] && refuse "gh auth reads or changes David's login"
        if [[ $s =~ ^git(\ |$) ]]; then
            [[ $s =~ \ (--no-verify|-n)(\ |$) && $s =~ ^git(\ +-[^\ ]+(\ +[^-\ ][^\ ]*)?)*\ +(push|commit)(\ |$) ]] &&
                refuse "skipping the bot's git hooks"
            [[ $s =~ \ -c\ *(credential|url\.|core\.sshCommand|core\.askPass|core\.hooksPath|user\.|author\.|committer\.) ]] &&
                refuse "overriding the bot's git identity"
            [[ $s =~ ^git\ +config\ .*(credential|url\.|core\.sshCommand|core\.askPass|core\.hooksPath|user\.|author\.|committer\.) && ! $s =~ \ (--get|--get-all|--list|-l)(\ |$) ]] &&
                refuse "overriding the bot's git identity"
            [[ $s =~ ^git\ +(commit|merge|rebase|cherry-pick|revert|am)\ .*--author ]] && refuse "commits are authored by the bot"
        fi
        [[ $s =~ ^(ssh|scp|sftp)(\ |$) ]] && refuse "SSH would use David's key"
    fi
done

exit 0
