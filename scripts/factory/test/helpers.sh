# Assertions of the factory's script tests, sourced by test/run.sh.

fail() {
    echo "    $*" >&2
    exit 1
}

assert_eq() {
    [[ $1 == "$2" ]] || fail "expected '$2', got '$1'${3:+ ($3)}"
}

assert_contains() {
    [[ $1 == *"$2"* ]] || fail "expected '$1' to contain '$2'"
}
