# Tests of the loop's merge pass: the candidates it picks from a
# snapshot.

candidates() {
    jq -r -f "$FACTORY_TEST_SCRIPTS/merge-candidates.jq" snapshot.json | paste -sd' ' -
}

test_candidates_are_the_bots_approved_green_prs_in_review() {
    cat >snapshot.json <<'JSON'
{
  "issues": [
    {"number": 70, "stage": "in-review"}, {"number": 71, "stage": "in-review"}, {"number": 72, "stage": "reviewing"},
    {"number": 73, "stage": "in-review"}, {"number": 74, "stage": "in-review"}, {"number": 75, "stage": "in-review"},
    {"number": 76, "stage": "in-review"}, {"number": 77, "stage": "in-review"}, {"number": 78, "stage": "in-review"}
  ],
  "prs": [
    {"number": 95, "author": "app/kaos-agent", "head": "75-b", "base": "main", "draft": false, "review": "APPROVED", "checks": "pass", "closes": [75]},
    {"number": 90, "author": "app/kaos-agent", "head": "70-a", "base": "main", "draft": false, "review": "APPROVED", "checks": "pass", "closes": [70]},
    {"number": 91, "author": "dajac", "head": "71-a", "base": "main", "draft": false, "review": "APPROVED", "checks": "pass", "closes": [71]},
    {"number": 92, "author": "app/kaos-agent", "head": "72-a", "base": "main", "draft": false, "review": "APPROVED", "checks": "pass", "closes": [72]},
    {"number": 93, "author": "app/kaos-agent", "head": "spec/73-a", "base": "main", "draft": false, "review": "APPROVED", "checks": "pass", "closes": [73]},
    {"number": 94, "author": "app/kaos-agent", "head": "74-a", "base": "main", "draft": true, "review": "APPROVED", "checks": "pass", "closes": [74]},
    {"number": 96, "author": "app/kaos-agent", "head": "76-a", "base": "70-a", "draft": false, "review": "APPROVED", "checks": "pass", "closes": [76]},
    {"number": 97, "author": "app/kaos-agent", "head": "77-a", "base": "main", "draft": false, "review": "CHANGES_REQUESTED", "checks": "pass", "closes": [77]},
    {"number": 98, "author": "app/kaos-agent", "head": "78-a", "base": "main", "draft": false, "review": "APPROVED", "checks": "pending", "closes": [78]},
    {"number": 99, "author": "app/kaos-agent", "head": "79-a", "base": "main", "draft": false, "review": "APPROVED", "checks": "pass", "closes": []}
  ],
  "closed_prs": []
}
JSON
    assert_eq "$(candidates)" "90 95"
}

test_no_candidates_in_an_empty_snapshot() {
    echo '{"issues": [], "prs": [], "closed_prs": []}' >snapshot.json
    assert_eq "$(candidates)" ""
}
