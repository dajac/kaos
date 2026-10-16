# Gives the current shell the identity of the bot. Sourced, never run:
# before every Bash command of a Claude session in Kaos (through the
# SessionStart hook and CLAUDE_ENV_FILE), and by the factory's scripts.
#
# Once the bot is configured, nothing run with this environment can act
# as David:
# - GH_TOKEN is the bot's installation token, or an invalid one when it
#   cannot be minted, so that gh, whatever binary runs, never falls back
#   to David's login in the Keychain; GH_CONFIG_DIR points to an empty
#   directory; gh itself goes through a wrapper that refreshes the token
#   for long-running scripts;
# - git forgets every configured credential helper and asks the bot's;
#   the SSH remotes are rewritten to HTTPS and SSH itself is disabled,
#   so David's SSH key is never used; a pre-push hook refuses commits
#   not authored and committed by the bot;
# - commits are authored and committed by the bot.
#
# Before the bot exists ("factory setup" has not installed it), it
# changes nothing. It must stay fast and silent: it runs before every
# command.

_factory_home=${FACTORY_HOME:-$(dirname "$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)" 2>/dev/null)}
if [[ -n $_factory_home && -f $_factory_home/.factory/bot.env ]] &&
    grep -q '^FACTORY_INSTALLATION_ID=.' "$_factory_home/.factory/bot.env"; then
    . "$_factory_home/.factory/bot.env"
    _factory_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
    export FACTORY_HOME=$_factory_home
    export FACTORY_IDENTITY=$FACTORY_BOT_LOGIN
    case ":$PATH:" in
    *":$_factory_dir/bin:"*) ;;
    *) export PATH=$_factory_dir/bin:$PATH ;;
    esac

    export GH_CONFIG_DIR=$_factory_home/.factory/gh-config
    [[ -d $GH_CONFIG_DIR ]] || mkdir -p "$GH_CONFIG_DIR"
    unset GITHUB_TOKEN GH_ENTERPRISE_TOKEN GITHUB_ENTERPRISE_TOKEN GH_HOST
    GH_TOKEN=$(FACTORY_HOME=$_factory_home "$_factory_dir/token.sh" 2>/dev/null) || GH_TOKEN=
    export GH_TOKEN=${GH_TOKEN:-invalid-no-bot-token}

    export GIT_AUTHOR_NAME=$FACTORY_BOT_LOGIN
    export GIT_AUTHOR_EMAIL=$FACTORY_BOT_EMAIL
    export GIT_COMMITTER_NAME=$FACTORY_BOT_LOGIN
    export GIT_COMMITTER_EMAIL=$FACTORY_BOT_EMAIL
    # Appended after the entries the environment may already carry, so
    # that they come last and win.
    _factory_n=${FACTORY_GIT_CONFIG_BASE:-${GIT_CONFIG_COUNT:-0}}
    export FACTORY_GIT_CONFIG_BASE=$_factory_n
    _factory_git_config() {
        export "GIT_CONFIG_KEY_$_factory_n=$1" "GIT_CONFIG_VALUE_$_factory_n=$2"
        _factory_n=$((_factory_n + 1))
    }
    _factory_git_config credential.helper ''
    _factory_git_config credential.helper "!$_factory_dir/bin/git-credential-kaos-agent"
    _factory_git_config url.https://github.com/.insteadOf git@github.com:
    _factory_git_config url.https://github.com/.insteadOf ssh://git@github.com/
    _factory_git_config core.hooksPath "$_factory_dir/git-hooks"
    export GIT_CONFIG_COUNT=$_factory_n
    export GIT_SSH_COMMAND=$_factory_dir/bin/no-ssh
    export GIT_TERMINAL_PROMPT=0
    unset -f _factory_git_config
    unset _factory_dir _factory_n
fi
unset _factory_home
