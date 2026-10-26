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

# A fake gh that answers issue N with the given labels.
fake_issue() {
    local n=$1
    shift
    mkdir -p fake bin
    export FAKE_GH=$PWD/fake
    cp "$FACTORY_TEST_SCRIPTS/test/fake-gh" bin/gh
    PATH=$PWD/bin:$PATH
    jq -n --argjson n "$n" '{number: $n, labels: ($ARGS.positional | map({name: .}))}' --args "$@" >"fake/issue-$n.json"
}

test_factory_issue_is_true_for_an_area_factory_issue() {
    fake_issue 103 enhancement accepted area:factory
    FACTORY_HOME=$PWD . "$FACTORY_TEST_SCRIPTS/lib.sh"
    factory_issue 103 || fail "#103 not taken for a factory issue"
}

test_factory_issue_is_false_for_another_area() {
    fake_issue 101 task accepted area:core area:api
    FACTORY_HOME=$PWD . "$FACTORY_TEST_SCRIPTS/lib.sh"
    rc=0
    factory_issue 101 || rc=$?
    assert_eq "$rc" 1
}

test_factory_issue_fails_when_the_labels_cannot_be_read() {
    fake_issue 101 area:core
    rm fake/issue-101.json
    FACTORY_HOME=$PWD . "$FACTORY_TEST_SCRIPTS/lib.sh"
    rc=0
    factory_issue 101 2>/dev/null || rc=$?
    assert_eq "$rc" 2
}
