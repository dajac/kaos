# Tests of the helpers of lib.sh.

# A clone whose origin has the branches main and spec/101-remove-it.
fake_origin() {
    git init -q -b main origin
    git -C origin commit -q --allow-empty -m init
    git -C origin branch spec/101-remove-it
    git clone -q origin home
}

test_remote_has_branch_finds_an_existing_branch() {
    fake_origin
    FACTORY_HOME=$PWD/home . "$FACTORY_TEST_SCRIPTS/lib.sh"
    remote_has_branch home main || fail "main not found"
    remote_has_branch home spec/101-remove-it || fail "spec/101-remove-it not found"
}

test_remote_has_branch_ignores_a_branch_that_only_ends_the_same() {
    fake_origin
    FACTORY_HOME=$PWD/home . "$FACTORY_TEST_SCRIPTS/lib.sh"
    if remote_has_branch home 101-remove-it; then
        fail "101-remove-it matched spec/101-remove-it"
    fi
}

test_remote_has_branch_is_false_for_a_missing_branch() {
    fake_origin
    FACTORY_HOME=$PWD/home . "$FACTORY_TEST_SCRIPTS/lib.sh"
    if remote_has_branch home 102-other; then
        fail "102-other found"
    fi
}
