# Gives the current shell the identity of the bot. Sourced, never run:
# before every command of a Claude session in Kaos (through the
# SessionStart hook and CLAUDE_ENV_FILE), and by the factory's jobs.
#
# Once the bot is configured, nothing run with this environment can act
# as David:
# - gh goes through a wrapper that sets GH_TOKEN to the bot's token, and
#   GH_CONFIG_DIR points to an empty directory, so David's gh login is
#   never read;
# - git forgets every configured credential helper and asks the bot's;
#   the SSH remotes are rewritten to HTTPS and SSH itself is disabled,
#   so David's SSH key is never used;
# - commits are authored and committed by the bot.
#
# Before the bot exists ("factory setup" has not run), it changes
# nothing. It must stay fast and silent: it runs before every command.

_factory_home=${FACTORY_HOME:-$(dirname "$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)" 2>/dev/null)}
if [[ -n $_factory_home && -f $_factory_home/.factory/bot.env ]]; then
    . "$_factory_home/.factory/bot.env"
    _factory_bin=$(cd "$(dirname "${BASH_SOURCE[0]}")/bin" && pwd)
    export FACTORY_HOME=$_factory_home
    export FACTORY_IDENTITY=$FACTORY_BOT_LOGIN
    case ":$PATH:" in
    *":$_factory_bin:"*) ;;
    *) export PATH=$_factory_bin:$PATH ;;
    esac
    export GH_CONFIG_DIR=$_factory_home/.factory/gh-config
    mkdir -p "$GH_CONFIG_DIR"
    unset GITHUB_TOKEN GH_ENTERPRISE_TOKEN
    export GIT_AUTHOR_NAME=$FACTORY_BOT_LOGIN
    export GIT_AUTHOR_EMAIL=$FACTORY_BOT_EMAIL
    export GIT_COMMITTER_NAME=$FACTORY_BOT_LOGIN
    export GIT_COMMITTER_EMAIL=$FACTORY_BOT_EMAIL
    export GIT_CONFIG_COUNT=4
    export GIT_CONFIG_KEY_0=credential.helper GIT_CONFIG_VALUE_0=
    export GIT_CONFIG_KEY_1=credential.helper GIT_CONFIG_VALUE_1="!$_factory_bin/git-credential-kaos-agent"
    export GIT_CONFIG_KEY_2=url.https://github.com/.insteadOf GIT_CONFIG_VALUE_2=git@github.com:
    export GIT_CONFIG_KEY_3=url.https://github.com/.insteadOf GIT_CONFIG_VALUE_3=ssh://git@github.com/
    export GIT_SSH_COMMAND="$_factory_bin/no-ssh"
    export GIT_TERMINAL_PROMPT=0
    unset _factory_bin
fi
unset _factory_home
