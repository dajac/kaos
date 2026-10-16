# Tests of David's merge: the squash commit message.

test_commit_message_is_the_body_above_the_rule() {
    FACTORY_HOME=$PWD . "$FACTORY_TEST_SCRIPTS/lib.sh"
    body=$'<!--\nA comment.\n-->\nThis patch fixes x.\r\n\n- One\n\nCloses #75\n\n---\n\n## How to test\n\n    ./factory try 81\n'
    assert_eq "$(commit_message <<<"$body")" $'This patch fixes x.\n\n- One\n\nCloses #75'
    assert_eq "$(commit_message <<<$'No rule at all.\n')" 'No rule at all.'
}
