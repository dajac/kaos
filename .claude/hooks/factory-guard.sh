#!/usr/bin/env bash
# PreToolUse hook of every Claude session in Kaos, jobs and interactive
# sessions alike: refuses the Bash commands that only David may run, and
# the ones that would act as David instead of the bot.
#
# It reads the hook input on stdin and exits 2, with the reason on
# stderr, to refuse a command. It is a guardrail, not a security
# boundary: the skills state the same rules, and the jobs' settings
# repeat the main ones as deny rules.
#
# It cannot tell a message from a command in a shell line (heredocs
# and quoted strings run substitutions; quotes and backslashes hide
# words), so it does not try: it removes quotes and backslashes, takes
# newlines for separators, and checks the whole line, messages and
# heredoc bodies included. A job writes its messages and bodies to files
# (git commit -F, --body-file), which the guard never reads; a command
# that names a David-only command inline is refused, even in a message.
# It checks the line in two ways:
# - anywhere in it: David's gates, and the ways around the bot's
#   identity;
# - at a command position (the start, after a separator, a $( or a
#   backquote, or a then, do, else, !...): the commands that only count
#   as commands, such as security, eval or an environment assignment.
# Every match ignores case: git's configuration keys do.
set -uo pipefail
shopt -s nocasematch

input=$(cat)
[[ $(jq -r '.tool_name // empty' <<<"$input") == Bash ]] || exit 0
raw=$(jq -r '.tool_input.command // empty' <<<"$input")
# The line as the guard reads it: newlines are separators; quotes and
# backslashes are gone, so that gh pr "merge", mer\ge and mer''ge read
# as merge.
line=$(tr '\n' ';' <<<"$raw" | tr '\t' ' ' | tr -d "\"'\\\\" | tr -s ' ')

refuse() {
    echo "factory-guard: refused, $1. See docs/factory/README.md, \"Guardrails\"." >&2
    exit 2
}

# A command position.
at='(^|[;&|({}`]|\$\(|(^| )(then|do|else|elif|if|while|until|!)) *'

# The simple commands of the line, one per element, without the words
# before the command itself: assignments, then, do, !, command, exec...
segments=()
while IFS= read -r segment; do
    segment=${segment#"${segment%%[! ]*}"}
    while [[ $segment =~ ^(command|builtin|exec|time|nohup|then|do|else|elif|if|while|until|!|[A-Za-z_][A-Za-z_0-9]*=[^\ ]*)\ +(.*)$ ]]; do
        segment=${BASH_REMATCH[2]}
    done
    # gh takes its repository flag before the subcommand too.
    while [[ $segment =~ ^(([^\ ]*/)?gh)\ +(-R|--repo)(=|\ +)?[^\ ]+\ +(.*)$ ]]; do
        segment="${BASH_REMATCH[1]} ${BASH_REMATCH[5]}"
    done
    [[ -n $segment ]] && segments+=("$segment")
done < <(sed -E 's/\$\(|`|&&|\|\||[;&|(){}]/\n/g' <<<"$line")

line_has() {
    [[ $line =~ $1 ]]
}

project=${CLAUDE_PROJECT_DIR:-$(jq -r '.cwd // empty' <<<"$input")}
home=$(dirname "$(git -C "${project:-.}" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)" 2>/dev/null)
bot=false
[[ -n $home && -f $home/.factory/bot.env ]] && bot=true

# Strings run as commands, which the guard cannot look into, and git's
# own ways to run a command.
line_has "$at(git)( [^;&|]*)? (rebase|difftool|mergetool|bisect)( [^;&|]*)? (-x|--exec|--extcmd|--tool|run)( |=|\$)|$at(git)( [^;&|]*)? filter-(branch|repo)" &&
    refuse "git commands that run other commands hide them from the guard"

line_has "$at(bash|sh|zsh|dash|ksh) +-[a-z]*c( |\$)|${at}eval( |\$)" &&
    refuse "running a quoted string as a command hides it from the guard"

# David's gates, wherever they appear: merging, approving, closing an
# issue, resolving a thread, the accepted label.
line_has 'pulls/[0-9]+/merge' && refuse "merging a PR is David's"
line_has "$at([^ ]*/)?merge\.sh( |\$)" && refuse "merging a PR is David's"
line_has 'resolveReviewThread' && refuse "resolving a review thread is David's"
line_has 'mergePullRequest|enablePullRequestAutoMerge' && refuse "merging a PR is David's"
line_has 'addPullRequestReview' && line_has 'APPROVE' && refuse "approving a PR is David's"
line_has 'event[^a-z]*APPROVE' && refuse "approving a PR is David's"
line_has '/issues/[0-9]+[^ ]* .*state[^a-z]*closed' && refuse "closing an issue is David's"
line_has '(^|[^a-z-])gh +((-R|--repo)[= ]?[^ ]+ +)*pr +merge' && refuse "merging a PR is David's"
line_has '(^|[^a-z-])gh +((-R|--repo)[= ]?[^ ]+ +)*issue +close' && refuse "closing an issue is David's"
line_has '(^|[^a-z-])gh +((-R|--repo)[= ]?[^ ]+ +)*pr +review[^;&|]*(--approve| -[a-z]*a[a-z]*( |$))' && refuse "approving a PR is David's"
line_has '(^|[^a-z-])factory +merge' && refuse "merging a PR is David's"
line_has '(^|[^a-z-])gh +alias' && refuse "gh aliases hide commands from the guard"
line_has 'settings\.local\.json' && line_has '(>|tee|cp|mv|ln|install|rsync)' &&
    refuse "a local settings file would loosen the session's permissions"

# Labels through the API: the accepted label is David's whatever the
# call; the stage labels change through stage.sh only. Reading labels is
# fine.
line_has '(^|[^a-z-])gh +api' && line_has 'labels' && line_has 'accepted' && refuse "the accepted label is David's"
if line_has '(^|[^a-z-])gh +api' && line_has 'labels' &&
    line_has ' -[fF]| --(field|raw-field|input)| (-X|--method)[ =]*(POST|PUT|PATCH|DELETE)'; then
    line_has 'needs-triage|triaged|needs-info|queued|spec-review|in-progress|reviewing|in-review|changes-requested|blocked' &&
        refuse "stage labels change through scripts/factory/stage.sh only"
fi

# Once the bot exists, the ways around its identity, wherever they
# appear.
if $bot; then
    # An assignment, before a command or exported, of what carries the
    # bot's identity, or of a program git runs (an editor, a pager).
    line_has "$at((env|export)( +-[a-z-]+)* +)?([A-Za-z_][A-Za-z_0-9]*=[^ ]* +)*(GH_TOKEN|GITHUB_TOKEN|GH_CONFIG_DIR|GH_HOST|GIT_CONFIG_[A-Z_0-9]*|GIT_AUTHOR_[A-Z]*|GIT_COMMITTER_[A-Z]*|GIT_SSH[A-Z_]*|GIT_ASKPASS|SSH_ASKPASS|GIT_EDITOR|GIT_SEQUENCE_EDITOR|EDITOR|VISUAL|GIT_PAGER|PAGER|GIT_EXTERNAL_DIFF|PATH)=" &&
        refuse "overriding the bot's identity, or a program git runs"
    line_has '(^|[ ;&|(){}`])(GH_TOKEN|GITHUB_TOKEN|GH_CONFIG_DIR|GH_HOST|GIT_CONFIG_[A-Z_0-9]*|GIT_AUTHOR_[A-Z]*|GIT_COMMITTER_[A-Z]*|GIT_SSH[A-Z_]*|GIT_ASKPASS|SSH_ASKPASS)\+?=' &&
        refuse "overriding the bot's identity"
    line_has 'protocol\.[a-z]*\.allow|ext::' && refuse "git's ext transport runs commands"

    line_has '(^|[;&|( ])(unset|env)( [^;&|]*)? -u|(^|[;&|( ])env [^;&|]*(-i|--ignore-environment|--unset)|(^|[;&|( ])unset [^;&|]*(GH_|GITHUB_TOKEN|GIT_)' &&
        refuse "overriding the bot's identity"
    line_has '(^|[^a-z-])gh +auth' && refuse "gh auth reads or changes David's login"
    line_has "credential-(osxkeychain|store|cache|manager)|(^|[^a-z-])git( [^;&|]*)? credential( |\$)|${at}security( |\$)" &&
        refuse "reading credentials other than the bot's"
    line_has '\.git-credentials|\.config/gh|Library/Keychains|\.netrc|\.ssh/' &&
        refuse "reading credentials other than the bot's"
    line_has '(^|[^a-z-])git( [^;&|]*)? (-c *|--config-env[= ])' && line_has '(-c *|--config-env[= ] *)(alias\.|credential|url\.|core\.(sshCommand|askPass|hooksPath|editor|pager|fsmonitor)|sequence\.editor|diff\.|filter\.|remote\.|pager\.|gpg\.|protocol\.|user\.|author\.|committer\.|include)' &&
        refuse "overriding the bot's git identity"
    line_has '(^|[^a-z-])git( [^;&|]*)? config( [^;&|]*)? (alias\.|include)' && ! line_has ' (--get|--get-all|--list|-l)( |$)' &&
        refuse "git aliases hide commands from the guard"
    line_has "$at(xargs|find [^;&|]*-(exec|execdir|ok|okdir))( |\$)" &&
        refuse "running commands through xargs or find -exec hides them from the guard"
fi

for s in ${segments[@]+"${segments[@]}"}; do
    case $s in
    gh\ * | */gh\ *)
        [[ $s =~ (^|/)gh\ +pr\ +merge ]] && refuse "merging a PR is David's"
        [[ $s =~ (^|/)gh\ +issue\ +close ]] && refuse "closing an issue is David's"
        [[ $s =~ (^|/)gh\ +pr\ +review.*(--approve|\ -[a-z]*a[a-z]*(\ |$)) ]] && refuse "approving a PR is David's"
        [[ $s =~ (^|/)gh\ +alias ]] && refuse "gh aliases hide commands from the guard"
        if [[ $s =~ (^|/)gh\ +(issue|pr)\ +(create|edit)\  ]]; then
            [[ $s =~ (--add-label|--label|-l)[\ =]+([^\ ]*,)?accepted(,|\ |$) ]] && refuse "the accepted label is David's"
            [[ $s =~ (--add-label|--remove-label|--label|-l)[\ =]+([^\ ]*,)?(needs-triage|triaged|needs-info|queued|spec-review|in-progress|reviewing|in-review|changes-requested|blocked)(,|\ |$) ]] &&
                refuse "stage labels change through scripts/factory/stage.sh only"
        fi
        ;;
    esac
    case $s in
    factory\ merge* | */factory\ merge*) refuse "merging a PR is David's" ;;
    esac
    if [[ $s =~ ^git(\ +-[^\ ]+(\ +[^-\ ][^\ ]*)?)*\ +push(\ |$) ]]; then
        [[ $s =~ (\ |:|/|\+)main(\ |$)|refs/heads/main ]] && refuse "pushing to main is David's"
        [[ $s =~ \ (--all|--mirror)(\ |$)|\* ]] && refuse "a push of every branch reaches main, which is David's"
        if [[ $s =~ issue-screenshots ]]; then
            [[ $s =~ \ (-f|--force[^\ ]*|--delete|-d)(\ |$)|\ \+|\ :issue-screenshots ]] &&
                refuse "the issue-screenshots branch is never rewritten or deleted"
        fi
    fi
    [[ $s =~ ^git\ +branch\ +.*-[dD].*issue-screenshots ]] && refuse "the issue-screenshots branch is never deleted"

    # Once the bot exists, nothing may run as David: no way around the
    # bot's gh wrapper, token, credential helper, SSH block or author.
    if $bot; then
        [[ $s =~ ^(unset|export\ +-n)\ .*(GH_|GITHUB_TOKEN|GIT_|PATH) ]] && refuse "overriding the bot's identity"
        [[ $s =~ ^env\ .*(-u|-i|--unset|--ignore-environment) ]] && refuse "overriding the bot's identity"
        [[ $s =~ ^/[^\ ]*/gh(\ |$) && ! $s =~ ^/[^\ ]*/scripts/factory/bin/gh(\ |$) ]] &&
            refuse "bypassing the bot's gh wrapper"
        [[ $s =~ ^gh\ +auth ]] && refuse "gh auth reads or changes David's login"
        if [[ $s =~ ^git(\ |$) ]]; then
            [[ $s =~ \ (--no-verify|-n)(\ |$) && $s =~ ^git(\ +-[^\ ]+(\ +[^-\ ][^\ ]*)?)*\ +(push|commit)(\ |$) ]] &&
                refuse "skipping the bot's git hooks"
            [[ $s =~ \ (-c\ *|--config-env[=\ ]\ *)(alias\.|credential|url\.|core\.(sshCommand|askPass|hooksPath|editor|pager|fsmonitor)|sequence\.editor|diff\.|filter\.|remote\.|pager\.|gpg\.|protocol\.|user\.|author\.|committer\.|include) ]] &&
                refuse "overriding the bot's git identity"
            # git config <key> alone reads; with a value, --add, --unset or
            # --replace-all it writes.
            if [[ $s =~ ^git\ +config\ .*(alias\.|include|credential|url\.|core\.|sequence\.editor|diff\.|remote\.[^\ ]*\.push|user\.|author\.|committer\.) &&
                ! $s =~ \ (--get|--get-all|--get-regexp|--list|-l)(\ |$) ]]; then
                [[ $s =~ ^git\ +config(\ +--(global|local|system|worktree|file\ [^\ ]+))*\ +[^\ -][^\ ]*$ ]] ||
                    refuse "overriding the bot's git identity"
            fi
            [[ $s =~ ^git\ +(commit|merge|rebase|cherry-pick|revert|am)\ .*--author ]] && refuse "commits are authored by the bot"
        fi
        [[ $s =~ ^(ssh|scp|sftp)(\ |$) ]] && refuse "SSH would use David's key"
    fi
done

exit 0
