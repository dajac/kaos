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

test_identity_stays_off_until_the_bot_is_installed() {
    git init -q home
    mkdir -p home/.factory
    printf 'FACTORY_APP_ID=42\nFACTORY_APP_SLUG=kaos-agent\n' >home/.factory/bot.env
    export FACTORY_HOME=$PWD/home
    . "$FACTORY_TEST_SCRIPTS/identity.sh"
    assert_eq "${FACTORY_IDENTITY:-}" ""
    assert_eq "${GH_TOKEN:-}" ""
}

test_gh_token_is_exported_so_no_gh_binary_falls_back_to_davids_login() {
    fake_home
    . "$FACTORY_TEST_SCRIPTS/identity.sh"
    assert_eq "$GH_TOKEN" ghs_cached
}

test_gh_token_is_invalid_when_no_bot_token_can_be_minted() {
    fake_home
    rm home/.factory/token
    mkdir fakebin
    printf '#!/bin/sh\nexit 1\n' >fakebin/curl
    printf '#!/bin/sh\nexit 1\n' >fakebin/security
    chmod +x fakebin/*
    PATH=$PWD/fakebin:$PATH
    . "$FACTORY_TEST_SCRIPTS/identity.sh"
    assert_eq "$GH_TOKEN" invalid-no-bot-token
}

test_token_is_minted_when_the_cache_is_about_to_expire() {
    fake_home
    echo "$(($(date +%s) + 300)) ghs_old" >home/.factory/token
    openssl genrsa -out key.pem 2048 2>/dev/null
    mkdir fakebin
    printf '#!/bin/sh\nopenssl base64 -A -in %s\n' "$PWD/key.pem" >fakebin/security
    cat >fakebin/curl <<'CURL'
#!/bin/sh
echo "$*" >>"$CURL_LOG"
echo '{"token": "ghs_new", "expires_at": "2099-01-01T00:00:00Z"}'
CURL
    chmod +x fakebin/*
    export CURL_LOG=$PWD/curl.log
    PATH=$PWD/fakebin:$PATH
    assert_eq "$("$FACTORY_TEST_SCRIPTS/token.sh")" ghs_new
    assert_contains "$(cat curl.log)" "/app/installations/7/access_tokens"
    assert_eq "$(cut -d' ' -f2 home/.factory/token)" ghs_new
    assert_eq "$(ls -l home/.factory/token | cut -c1-10)" -rw-------
}

test_existing_git_config_entries_are_kept() {
    fake_home
    export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=foo.bar GIT_CONFIG_VALUE_0=baz
    . "$FACTORY_TEST_SCRIPTS/identity.sh"
    cd home
    assert_eq "$(git config foo.bar)" baz
    assert_eq "$(git config core.hooksPath)" "$FACTORY_TEST_SCRIPTS/git-hooks"
}

test_two_wrappers_in_path_do_not_loop() {
    fake_home
    mkdir -p realbin other/scripts/factory/bin
    printf '#!/usr/bin/env bash\necho "real $GH_TOKEN"\n' >realbin/gh
    cp "$FACTORY_TEST_SCRIPTS/bin/gh" other/scripts/factory/bin/gh
    chmod +x realbin/gh
    PATH=$PWD/other/scripts/factory/bin:$PWD/realbin:$PATH
    . "$FACTORY_TEST_SCRIPTS/identity.sh"
    assert_eq "$(gh)" "real ghs_cached"
}

test_pre_push_refuses_commits_not_by_the_bot() {
    fake_home
    git init -q --bare remote.git
    . "$FACTORY_TEST_SCRIPTS/identity.sh"
    cd home
    git remote add local ../remote.git
    git commit -q --allow-empty -m "by the bot"
    git push -q local HEAD:refs/heads/ok
    git commit -q --allow-empty --author="David <david@example.com>" -m "as David"
    if git push -q local HEAD:refs/heads/bad 2>../push.err; then
        fail "a commit authored by David was pushed"
    fi
    assert_contains "$(cat ../push.err)" "not authored and committed by the bot"
    git commit -q --amend --allow-empty --no-edit --reset-author
    git push -q local HEAD:refs/heads/bad
}

test_session_hook_sources_identity_before_every_command() {
    git init -q project
    export CLAUDE_ENV_FILE=$PWD/env CLAUDE_PROJECT_DIR=$PWD/project
    mkdir -p project/scripts/factory
    touch project/scripts/factory/identity.sh
    "$FACTORY_TEST_SCRIPTS/../../.claude/hooks/session-identity.sh"
    assert_eq "$(cat env)" ". $(pwd -P)/project/scripts/factory/identity.sh"
}

test_session_hook_prefers_the_trusted_checkout() {
    git init -q project
    mkdir -p project/scripts/factory trusted/scripts/factory
    touch project/scripts/factory/identity.sh trusted/scripts/factory/identity.sh
    export CLAUDE_ENV_FILE=$PWD/env CLAUDE_PROJECT_DIR=$PWD/project FACTORY_TRUSTED=$PWD/trusted
    "$FACTORY_TEST_SCRIPTS/../../.claude/hooks/session-identity.sh"
    assert_eq "$(cat env)" ". $PWD/trusted/scripts/factory/identity.sh"
}
