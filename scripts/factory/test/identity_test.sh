# Tests of the bot's identity: the JWT, the token cache, identity.sh,
# the git credential helper and the gh wrapper. No network: the token is
# taken from a cache that does not expire.

# A main checkout with a configured bot and a cached token.
fake_home() {
    git init -q home
    git -C home remote add origin git@github.com:dajac/kaos.git
    mkdir -p home/.factory
    cat >home/.factory/bot.env <<'EOF'
FACTORY_APP_ID=42
FACTORY_APP_SLUG=kaos-agent
FACTORY_INSTALLATION_ID=7
FACTORY_BOT_LOGIN='kaos-agent[bot]'
FACTORY_BOT_ID=1234
FACTORY_BOT_EMAIL='1234+kaos-agent[bot]@users.noreply.github.com'
EOF
    echo "$(($(date +%s) + 3600)) ghs_cached" >home/.factory/token
    export FACTORY_HOME=$PWD/home
}

test_jwt_is_signed_with_the_app_key() {
    openssl genrsa -out key.pem 2048 2>/dev/null
    openssl rsa -in key.pem -pubout -out pub.pem 2>/dev/null
    FACTORY_HOME=$PWD . "$FACTORY_TEST_SCRIPTS/lib.sh"
    private_key() { cat key.pem; }
    FACTORY_APP_ID=42
    jwt=$(app_jwt)
    IFS=. read -r header payload sig <<<"$jwt"
    unb64() { local s=$1; while ((${#s} % 4)); do s+==; done; tr -- '-_' '+/' <<<"$s" | openssl base64 -d -A; }
    assert_eq "$(unb64 "$header" | jq -r .alg)" RS256
    assert_eq "$(unb64 "$payload" | jq -r .iss)" 42
    unb64 "$sig" >sig.bin
    printf '%s' "$header.$payload" | openssl dgst -sha256 -verify pub.pem -signature sig.bin >/dev/null ||
        fail "signature does not verify"
}

test_token_comes_from_the_cache_while_fresh() {
    fake_home
    assert_eq "$("$FACTORY_TEST_SCRIPTS/token.sh")" ghs_cached
}

test_identity_changes_nothing_without_a_bot() {
    git init -q home
    export FACTORY_HOME=$PWD/home
    path_before=$PATH
    . "$FACTORY_TEST_SCRIPTS/identity.sh"
    assert_eq "${FACTORY_IDENTITY:-}" ""
    assert_eq "${GIT_CONFIG_COUNT:-}" ""
    assert_eq "$PATH" "$path_before"
}

test_identity_makes_git_commit_as_the_bot() {
    fake_home
    . "$FACTORY_TEST_SCRIPTS/identity.sh"
    assert_eq "$FACTORY_IDENTITY" 'kaos-agent[bot]'
    cd home
    assert_contains "$(git var GIT_AUTHOR_IDENT)" 'kaos-agent[bot] <1234+kaos-agent[bot]@users.noreply.github.com>'
    assert_contains "$(git var GIT_COMMITTER_IDENT)" 'kaos-agent[bot] <1234+kaos-agent[bot]@users.noreply.github.com>'
    git commit -q --allow-empty -m test
    assert_eq "$(git log -1 --format='%an|%cn')" 'kaos-agent[bot]|kaos-agent[bot]'
}

test_identity_sends_origin_over_https_and_disables_ssh() {
    fake_home
    . "$FACTORY_TEST_SCRIPTS/identity.sh"
    cd home
    assert_eq "$(git remote get-url --push origin)" https://github.com/dajac/kaos.git
    # The empty helper resets the list: the system and global helpers
    # (osxkeychain, holding David's credentials) are listed but never run.
    filled=$(printf 'protocol=https\nhost=github.com\n\n' | GIT_TRACE=$PWD/../trace git credential fill)
    assert_contains "$filled" 'username=x-access-token'
    assert_contains "$filled" 'password=ghs_cached'
    ! grep -q osxkeychain ../trace || fail "osxkeychain was run: $(grep osxkeychain ../trace)"
    grep -q git-credential-kaos-agent ../trace || fail "the bot's helper was not run"
    ! "$GIT_SSH_COMMAND" git@github.com true 2>/dev/null || fail "ssh is not disabled"
}

test_credential_helper_answers_for_github_only() {
    fake_home
    . "$FACTORY_TEST_SCRIPTS/identity.sh"
    helper=$FACTORY_TEST_SCRIPTS/bin/git-credential-kaos-agent
    out=$(printf 'protocol=https\nhost=github.com\n\n' | "$helper" get)
    assert_eq "$out" $'username=x-access-token\npassword=ghs_cached'
    assert_eq "$(printf 'protocol=https\nhost=example.com\n\n' | "$helper" get)" ""
    assert_eq "$(printf 'protocol=https\nhost=github.com\n\n' | "$helper" store)" ""
}

test_gh_wrapper_runs_the_real_gh_with_the_bot_token() {
    fake_home
    mkdir realbin
    printf '#!/usr/bin/env bash\necho "token=$GH_TOKEN args=$*"\n' >realbin/gh
    chmod +x realbin/gh
    PATH=$PWD/realbin:$PATH
    . "$FACTORY_TEST_SCRIPTS/identity.sh"
    assert_eq "$(command -v gh)" "$FACTORY_TEST_SCRIPTS/bin/gh"
    assert_eq "$(gh pr list)" "token=ghs_cached args=pr list"
    assert_eq "$GH_CONFIG_DIR" "$FACTORY_HOME/.factory/gh-config"
}
