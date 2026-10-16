#!/usr/bin/env bash
# Prints an installation token of the bot, valid ten more minutes at
# least. Tokens live one hour; the current one is cached in
# .factory/token and replaced when it gets close to expiring.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
load_bot

cache=$FACTORY_STATE/token
if [[ -f $cache ]]; then
    read -r expires token <"$cache"
    if ((expires - $(date +%s) > 600)); then
        echo "$token"
        exit 0
    fi
fi

response=$(app_api POST "/app/installations/$FACTORY_INSTALLATION_ID/access_tokens")
token=$(jq -r .token <<<"$response")
expires=$(jq -r '.expires_at | fromdateiso8601' <<<"$response")

umask 077
tmp=$(mktemp "$cache.XXXXXX")
echo "$expires $token" >"$tmp"
mv "$tmp" "$cache"
echo "$token"
