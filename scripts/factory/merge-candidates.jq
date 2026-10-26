# The PRs the loop's merge pass tries, from a snapshot of status.sh, one
# number per line, lowest first: the bot's open PRs, not drafts, based on
# main, not spec PRs, approved, with CI green, closing an issue in
# in-review. merge.sh --auto checks the whole gate again on GitHub.
#
# Usage: jq -r -f scripts/factory/merge-candidates.jq <snapshot.json>
[.issues[] | select(.stage == "in-review") | .number] as $in_review
| [.prs[]
    | select(.author == "app/kaos-agent"
        and .draft == false
        and .base == "main"
        and (.head | test("^[0-9]+-"))
        and .review == "APPROVED"
        and .checks == "pass"
        and any(.closes[]; . as $n | $in_review | index($n)))
    | .number]
| sort[]
