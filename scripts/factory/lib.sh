# Helpers shared by the factory scripts. Sourced, never run.
#
# FACTORY_HOME is the main checkout of the repository, whichever worktree
# the caller runs in: the factory's state (.factory/) and its trusted
# scripts live there.

FACTORY_REPO=dajac/kaos
FACTORY_KEYCHAIN_SERVICE=kaos-agent-private-key

if [[ -z ${FACTORY_HOME:-} ]]; then
    FACTORY_HOME=$(dirname "$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --path-format=absolute --git-common-dir)")
fi
FACTORY_STATE=$FACTORY_HOME/.factory
# The scripts of the main checkout are the trusted ones. Until the
# factory is merged, the main checkout has none, so the caller's own
# copy stands in.
if [[ -z ${FACTORY_SCRIPTS:-} ]]; then
    if [[ -f $FACTORY_HOME/scripts/factory/lib.sh ]]; then
        FACTORY_SCRIPTS=$FACTORY_HOME/scripts/factory
    else
        FACTORY_SCRIPTS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
    fi
fi

die() {
    echo "factory: $*" >&2
    exit 1
}

info() {
    echo "factory: $*" >&2
}

# Loads the bot's configuration written by "factory setup".
load_bot() {
    [[ -f $FACTORY_STATE/bot.env ]] || die "no bot configured, run ./factory setup"
    # shellcheck source=/dev/null
    . "$FACTORY_STATE/bot.env"
}

b64url() {
    openssl base64 -A | tr '+/' '-_' | tr -d '='
}

private_key() {
    security find-generic-password -s "$FACTORY_KEYCHAIN_SERVICE" -a "$FACTORY_REPO" -w | openssl base64 -d -A
}

# A JWT signed with the App's private key, valid nine minutes. It
# authenticates as the App itself, to mint installation tokens.
app_jwt() {
    local now header payload unsigned key sig
    now=$(date +%s)
    header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
    payload=$(printf '{"iat":%d,"exp":%d,"iss":"%s"}' $((now - 60)) $((now + 540)) "$FACTORY_APP_ID" | b64url)
    unsigned=$header.$payload
    key=$(mktemp)
    # shellcheck disable=SC2064
    trap "rm -f '$key'" RETURN
    chmod 600 "$key"
    private_key >"$key"
    sig=$(printf '%s' "$unsigned" | openssl dgst -sha256 -sign "$key" -binary | b64url)
    echo "$unsigned.$sig"
}

# Calls the GitHub API as the App (not as an installation).
app_api() {
    local method=$1 path=$2
    shift 2
    curl -fsS -X "$method" \
        -H "Authorization: Bearer $(app_jwt)" \
        -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        "$@" "https://api.github.com$path"
}
