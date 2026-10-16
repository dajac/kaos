# Tests of the test runner itself.

test_a_failing_command_in_the_middle_fails_the_test() {
    mkdir t
    cp "$FACTORY_TEST_SCRIPTS/test/run.sh" "$FACTORY_TEST_SCRIPTS/test/helpers.sh" t/
    printf 'test_x() {\n    false\n    true\n}\n' >t/x_test.sh
    if out=$(t/run.sh 2>&1); then
        fail "the runner passed a failing test: $out"
    fi
    assert_contains "$out" "0 passed, 1 failed"
}
