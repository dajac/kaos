#!/usr/bin/env bash
# Creates the bot, the GitHub App kaos-agent, with GitHub's App manifest
# flow, and installs it on the repository. Run by "factory setup", by
# David: GitHub asks him to confirm in the browser twice, once to create
# the App and once to install it.
#
# The App's private key goes to the macOS Keychain; its ids go to
# .factory/bot.env, which the other scripts load. The script is
# resumable: an App created by an interrupted run is reused.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

name=${1:-kaos-agent}
port=${FACTORY_SETUP_PORT:-8765}
mkdir -p "$FACTORY_STATE"
bot_env=$FACTORY_STATE/bot.env

# Contents, issues and pull requests to work; checks, statuses and
# actions read-only to follow CI. No administration and no workflows:
# the bot can neither change the repository's settings nor push a change
# under .github/workflows/.
manifest() {
    jq -cn --arg name "$name" --arg redirect "http://localhost:$port/created" '{
        name: $name,
        url: "https://github.com/dajac/kaos",
        description: "The software factory of Kaos. Acts on dajac/kaos only.",
        public: false,
        redirect_url: $redirect,
        hook_attributes: {url: "https://github.com/dajac/kaos", active: false},
        default_events: [],
        default_permissions: {
            contents: "write",
            issues: "write",
            pull_requests: "write",
            checks: "read",
            statuses: "read",
            actions: "read",
            metadata: "read"
        }
    }'
}

# Waits for GitHub to redirect the browser to localhost with ?code=...
# and prints the request line that carries the code.
wait_for_redirect() {
    local line
    while true; do
        line=$(printf 'HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nDone, you can close this tab and go back to the terminal.\n' |
            nc -l localhost "$port" | head -n 1 || true)
        if [[ $line == *"code="* ]]; then
            echo "$line"
            return
        fi
    done
}

write_bot_env() {
    {
        echo "# Written by factory setup. Not secret: the private key is in the Keychain."
        echo "FACTORY_APP_ID=$FACTORY_APP_ID"
        echo "FACTORY_APP_SLUG=$FACTORY_APP_SLUG"
        if [[ -n ${FACTORY_INSTALLATION_ID:-} ]]; then
            echo "FACTORY_INSTALLATION_ID=$FACTORY_INSTALLATION_ID"
            echo "FACTORY_BOT_LOGIN='$FACTORY_BOT_LOGIN'"
            echo "FACTORY_BOT_ID=$FACTORY_BOT_ID"
            echo "FACTORY_BOT_EMAIL='$FACTORY_BOT_EMAIL'"
        fi
    } >"$bot_env"
}

create_app() {
    local state page escaped request code got_state app
    state=$(openssl rand -hex 16)
    page=$FACTORY_STATE/create-app.html
    escaped=$(manifest | sed -e 's/&/\&amp;/g' -e 's/"/\&quot;/g' -e 's/</\&lt;/g')
    {
        echo '<!doctype html>'
        echo '<meta charset="utf-8">'
        echo "<title>Create $name</title>"
        echo "<form id=\"f\" method=\"post\" action=\"https://github.com/settings/apps/new?state=$state\">"
        echo "  <input type=\"hidden\" name=\"manifest\" value=\"$escaped\">"
        echo "  <p>Redirecting to GitHub to create the App <b>$name</b>...</p>"
        echo '  <button type="submit">Continue</button>'
        echo '</form>'
        echo '<script>document.getElementById("f").submit()</script>'
    } >"$page"

    info "opening the browser: confirm the creation of the App $name on GitHub"
    open "$page"
    request=$(wait_for_redirect)
    rm -f "$page"
    code=$(sed -E 's/.*[?&]code=([^& ]+).*/\1/' <<<"$request")
    got_state=$(sed -E 's/.*[?&]state=([^& ]+).*/\1/' <<<"$request")
    [[ $got_state == "$state" ]] || die "the redirect from GitHub carries the wrong state"

    app=$(curl -fsS -X POST -H "Accept: application/vnd.github+json" \
        "https://api.github.com/app-manifests/$code/conversions")
    FACTORY_APP_ID=$(jq -r .id <<<"$app")
    FACTORY_APP_SLUG=$(jq -r .slug <<<"$app")
    security add-generic-password -U -s "$FACTORY_KEYCHAIN_SERVICE" -a "$FACTORY_REPO" \
        -l "$FACTORY_APP_SLUG private key" -w "$(jq -r .pem <<<"$app" | openssl base64 -A)"
    write_bot_env
    info "created the App $FACTORY_APP_SLUG (id $FACTORY_APP_ID), private key in the Keychain"
}

install_app() {
    info "opening the browser: install $FACTORY_APP_SLUG on ${FACTORY_REPO%%/*}, choosing \"Only select repositories\" and ${FACTORY_REPO#*/}"
    open "https://github.com/apps/$FACTORY_APP_SLUG/installations/new"
    FACTORY_INSTALLATION_ID=
    while [[ -z $FACTORY_INSTALLATION_ID ]]; do
        sleep 3
        FACTORY_INSTALLATION_ID=$(app_api GET /app/installations |
            jq -r --arg owner "${FACTORY_REPO%%/*}" '.[] | select(.account.login == $owner) | .id' | head -n 1)
    done
    FACTORY_BOT_LOGIN="$FACTORY_APP_SLUG[bot]"
    FACTORY_BOT_ID=$(curl -fsS "https://api.github.com/users/$(jq -rn --arg l "$FACTORY_BOT_LOGIN" '$l|@uri')" | jq -r .id)
    FACTORY_BOT_EMAIL="$FACTORY_BOT_ID+$FACTORY_BOT_LOGIN@users.noreply.github.com"
    write_bot_env
    info "installed $FACTORY_APP_SLUG (installation $FACTORY_INSTALLATION_ID)"
}

if [[ -f $bot_env ]]; then
    . "$bot_env"
fi
[[ -n ${FACTORY_APP_ID:-} ]] || create_app
[[ -n ${FACTORY_INSTALLATION_ID:-} ]] || install_app
