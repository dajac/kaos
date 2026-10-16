#!/usr/bin/env bash
# Creates or updates the factory's labels and migrates the issues that
# carry the old "ready" label. Idempotent; run by "factory setup", as the
# bot.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

# name|color|description
labels='
accepted|0e8a16|David accepted the work. Only David adds it
needs-spec|5319e7|A spec must be approved before the build
needs-triage|ededed|Stage: not looked at yet
triaged|c5def5|Stage: triage proposal posted, waiting for David
needs-info|d876e3|Stage: waiting for the reporter
queued|bfd4f2|Stage: accepted, waiting for a job
spec-review|5319e7|Stage: spec PR open
in-progress|fbca04|Stage: a build or address job is running
reviewing|1d76db|Stage: the draft PR is with the agent review
in-review|0e8a16|Stage: PR ready, waiting for David
changes-requested|d93f0b|Stage: David asked for changes
blocked|b60205|Stage: waiting on a decision, see the last kaos-agent comment
bug|d73a4a|Something is not working
enhancement|a2eeef|New feature or request
task|0366d6|Work that is neither a bug nor a feature
documentation|0075ca|Improvements or additions to documentation
area:core|c2e0c6|kaos-core
area:server|c2e0c6|kaos-server and the HTTP API implementation
area:api|c2e0c6|The public Java API, the HTTP API or its JSON
area:ui|c2e0c6|kaos-ui
area:build|c2e0c6|Maven, CI, packaging
area:protocol|c2e0c6|Kafka group protocol semantics
area:factory|c2e0c6|The software factory itself
priority:high|b60205|Goes first in the queue
'

while IFS='|' read -r name color description; do
    [[ -z $name ]] && continue
    gh label create "$name" --repo "$FACTORY_REPO" --color "$color" --description "$description" --force >/dev/null
done <<<"$labels"
info "labels up to date"

# The open issues labeled "ready" were never triaged: they go to triage, so
# that David accepts them from a proposal, rather than becoming
# buildable at once. Deleting the label clears it from closed issues.
if gh label list --repo "$FACTORY_REPO" --json name --jq '.[].name' | grep -qx ready; then
    for issue in $(gh issue list --repo "$FACTORY_REPO" --label ready --state open --limit 200 --json number --jq '.[].number'); do
        gh issue edit "$issue" --repo "$FACTORY_REPO" --add-label needs-triage --remove-label ready >/dev/null
        info "#$issue: ready -> needs-triage"
    done
    gh label delete ready --repo "$FACTORY_REPO" --yes >/dev/null
    info "deleted the label ready"
fi
