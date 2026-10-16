#!/usr/bin/env bash
# Prints, as one JSON document, what the tick needs to know about one
# issue to decide its next transition:
# - handoff: the last handoff of a job (the bot's last comment with a
#   fenced yaml "factory:" block): its time, its raw block, and its
#   scalar fields (job, seen_until, route, round, blocking, blocked,
#   resume...);
# - david: what David wrote after the watermark (the handoff's
#   seen_until, or the bot's last comment when there is no handoff), on
#   the issue and on its PRs: comments, reviews and review comments;
# - others: the comments of anyone but the bot and David after the
#   watermark, on the issue (a reporter answering a question);
# - accepted_by: who last added the accepted label;
# - labeled: for each label, when it was last added;
# - prs: the issue's PRs, found by their branch (<N>-..., spec/<N>-...),
#   with their state, head, CI, David's last review and the number of
#   unresolved threads.
#
# Usage: scripts/factory/activity.sh <issue>
set -euo pipefail
. "$(dirname "$0")/lib.sh"

issue=${1:?usage: activity.sh <issue>}
bot=${FACTORY_BOT_LOGIN:-kaos-agent[bot]}
[[ -f $FACTORY_STATE/bot.env ]] && . "$FACTORY_STATE/bot.env" && bot=$FACTORY_BOT_LOGIN

api() {
    gh api "repos/$FACTORY_REPO/$1" --paginate | jq -s 'add // []'
}

comments=$(api "issues/$issue/comments")
timeline=$(api "issues/$issue/timeline")
prs=$(gh pr list --repo "$FACTORY_REPO" --state all --limit 50 --search "head:$issue- OR head:spec/$issue-" \
    --json number,title,state,isDraft,headRefName,baseRefName,headRefOid,statusCheckRollup,mergedAt |
    jq --arg n "$issue" '[.[] | select(.headRefName | test("^(spec/)?" + $n + "-"))]')

pr_details='[]'
for pr in $(jq -r '.[].number' <<<"$prs"); do
    reviews=$(api "pulls/$pr/reviews")
    review_comments=$(api "pulls/$pr/comments")
    conversation=$(api "issues/$pr/comments")
    unresolved=$(gh api graphql -F n="$pr" -F owner="${FACTORY_REPO%%/*}" -F name="${FACTORY_REPO#*/}" -f query='
        query($owner: String!, $name: String!, $n: Int!) {
          repository(owner: $owner, name: $name) {
            pullRequest(number: $n) { reviewThreads(first: 100) { nodes { isResolved } } }
          }
        }' | jq '[.data.repository.pullRequest.reviewThreads.nodes[] | select(.isResolved | not)] | length')
    pr_details=$(jq --argjson pr "$pr" --argjson reviews "$reviews" --argjson rc "$review_comments" \
        --argjson conv "$conversation" --argjson unresolved "$unresolved" \
        '. + [{number: $pr, reviews: $reviews, review_comments: $rc, conversation: $conv, unresolved: $unresolved}]' <<<"$pr_details")
done

jq -n --argjson issue "$issue" --arg bot "$bot" --arg david "$FACTORY_DAVID" \
    --argjson comments "$comments" --argjson timeline "$timeline" --argjson prs "$prs" --argjson details "$pr_details" '
    def checks:
        if (. // []) | length == 0 then "none"
        elif any(.[]; (.conclusion // .state) as $s | $s == "FAILURE" or $s == "ERROR" or $s == "CANCELLED" or $s == "TIMED_OUT" or $s == "ACTION_REQUIRED") then "fail"
        elif any(.[]; (.status // "COMPLETED") != "COMPLETED" or .state == "PENDING" or .state == "EXPECTED") then "pending"
        else "pass" end;
    def handoff_of:
        (.body | capture("```yaml\\s*\\n(?<yaml>factory:[\\s\\S]*?)\\n```") .yaml) as $yaml
        | ([$yaml | split("\n")[] | capture("^\\s+(?<k>[a-z_]+):\\s*(?<v>[^\\s].*)$")?
            | {key: .k, value: (.v | sub("\\s+#.*$"; "") | gsub("^\"|\"$"; ""))}] | from_entries) as $fields
        | {at: .created_at, url: .html_url, yaml: $yaml, fields: $fields, job: $fields.job, seen_until: $fields.seen_until};
    ([$comments[] | select(.user.login == $bot) | select(.body | test("```yaml\\s*\\nfactory:")) | handoff_of] | last) as $handoff
    | ([$comments[] | select(.user.login == $bot) | .created_at] | last) as $last_bot
    | (($handoff.seen_until // $handoff.at // $last_bot) // "1970-01-01T00:00:00Z") as $mark
    | [
        ($comments[] | select(.user.login == $david and .created_at > $mark)
            | {on: "issue", kind: "comment", at: .created_at, url: .html_url, body}),
        ($details[] | .number as $pr
            | (.conversation[] | select(.user.login == $david and .created_at > $mark)
                | {on: "pr", pr: $pr, kind: "comment", at: .created_at, url: .html_url, body}),
              (.reviews[] | select(.user.login == $david and .submitted_at > $mark)
                | {on: "pr", pr: $pr, kind: "review", state, at: .submitted_at, url: .html_url, body}),
              (.review_comments[] | select(.user.login == $david and .created_at > $mark)
                | {on: "pr", pr: $pr, kind: "review_comment", at: .created_at, url: .html_url, path, line, body}))
    ] as $new
    | {
        issue: $issue,
        handoff: $handoff,
        watermark: $mark,
        david: ($new | sort_by(.at)),
        others: [$comments[] | select(.user.login != $bot and .user.login != $david and .created_at > $mark)
            | {author: .user.login, at: .created_at, url: .html_url, body}],
        accepted_by: ([$timeline[] | select(.event == "labeled" and .label.name == "accepted") | .actor.login] | last),
        labeled: ([$timeline[] | select(.event == "labeled") | {key: .label.name, value: .created_at}] | from_entries),
        prs: [$prs[] | .number as $n | ($details[] | select(.number == $n)) as $d | {
            number, title, state, draft: .isDraft, head: .headRefName, base: .baseRefName, sha: .headRefOid,
            merged: (.mergedAt != null),
            checks: (.statusCheckRollup | checks),
            david_review: ([$d.reviews[] | select(.user.login == $david and (.state == "APPROVED" or .state == "CHANGES_REQUESTED")) | {state, commit: .commit_id, at: .submitted_at}] | last),
            unresolved: $d.unresolved
        }]
    }'
