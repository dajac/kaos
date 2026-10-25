# Helpers shared by the factory scripts. Sourced, never run.
#
# FACTORY_HOME is the main checkout of the repository, whichever worktree
# the caller runs in: the factory's state (.factory/) and its trusted
# scripts live there.

FACTORY_REPO=dajac/kaos
# David's GitHub login: his reviews, labels and merges are the gates.
FACTORY_DAVID=dajac
FACTORY_KEYCHAIN_SERVICE=kaos-agent-private-key

if [[ -z ${FACTORY_HOME:-} ]]; then
    FACTORY_HOME=$(dirname "$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --path-format=absolute --git-common-dir)")
fi
FACTORY_STATE=$FACTORY_HOME/.factory
# The trusted scripts: those of the factory's checkout of main for the
# jobs (FACTORY_TRUSTED), of the main checkout otherwise. Until the
# factory is merged, the main checkout has none, so the caller's own
# copy stands in.
if [[ -z ${FACTORY_SCRIPTS:-} ]]; then
    if [[ -n ${FACTORY_TRUSTED:-} && -f $FACTORY_TRUSTED/scripts/factory/lib.sh ]]; then
        FACTORY_SCRIPTS=$FACTORY_TRUSTED/scripts/factory
    elif [[ -f $FACTORY_HOME/scripts/factory/lib.sh ]]; then
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

# Whether origin, as seen from the repository <git-dir>, has the branch
# <branch>. The ref is matched in full: a pattern given to ls-remote
# also matches the end of longer refs, so "101-x" would match
# "spec/101-x".
remote_has_branch() {
    git -C "$1" ls-remote --exit-code origin "refs/heads/$2" >/dev/null 2>&1
}

# Whether the issue <N> changes the factory itself: its triage gave it
# the label area:factory. Fails when the labels cannot be read.
factory_issue() {
    local labels
    labels=$(gh issue view "$1" --repo "$FACTORY_REPO" --json labels --jq '.labels[].name') || return 2
    grep -qx 'area:factory' <<<"$labels"
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

# Reads a PR body on stdin and prints the squash commit message: the part
# above the first "---" line, without HTML comments or trailing blank
# lines.
commit_message() {
    tr -d '\r' | awk '/^---[[:space:]]*$/ {exit} {print}' | perl -0pe 's/<!--.*?-->\n?//gs; s/\s+\z/\n/'
}
