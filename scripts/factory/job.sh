#!/usr/bin/env bash
# Runs one headless Claude Code session of the factory: a job on an
# issue, or a tick. Called by the loop, one at a time.
#
# Usage: scripts/factory/job.sh <triage|spec|build|review|address> <issue>
#        scripts/factory/job.sh tick <snapshot.json> <previous-snapshot.json> last-job=<kind,N,status,start,log|none> [reason=<why>] [<kind:N>...]
#
# It prepares the worktree of the job (see docs/factory/README.md,
# "Architecture"), runs "claude -p /factory-<kind> <issue>" there with
# the bot's identity, the jobs' settings of the trusted checkout,
# --permission-mode dontAsk, a budget cap and a time limit, and logs the
# session to .factory/logs/. It records the running job in
# .factory/job.json while it runs. Its last line on stdout is one JSON
# object: {kind, issue, status, exit, log, seconds, cost, result}, where
# status is ok, failed, timeout or refused (with a reason: the job did
# not start) and result is the tick's structured output.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
. "$FACTORY_SCRIPTS/identity.sh"
[[ ${FACTORY_IDENTITY:-} == "${FACTORY_BOT_LOGIN:-}" && -n ${FACTORY_IDENTITY:-} ]] ||
    die "no bot identity; run ./factory setup"

kind=${1:-}
arg=${2:-}
shift 2 || true
case $kind in
triage | spec | build | review | address) [[ $arg =~ ^[0-9]+$ ]] || die "usage: job.sh $kind <issue>" ;;
tick) [[ -f $arg ]] || die "usage: job.sh tick <snapshot.json> <previous-snapshot.json> last-job=... [<kind:N>...]" ;;
*) die "unknown job '$kind'" ;;
esac

trusted=${FACTORY_TRUSTED:-$FACTORY_HOME/.claude/worktrees/factory-main}
[[ -f $trusted/.claude/factory-job.json ]] || die "no trusted checkout at $trusted; run scripts/factory/trusted.sh"
export FACTORY_TRUSTED=$trusted
export FACTORY_SCRIPTS=$trusted/scripts/factory
worktrees=$FACTORY_HOME/.claude/worktrees
mkdir -p "$FACTORY_STATE/logs"

# Time limits in minutes and budget caps in dollars, per kind.
case $kind in
tick) limit=10 budget=1 ;;
triage) limit=30 budget=3 ;;
spec) limit=45 budget=8 ;;
build) limit=90 budget=20 ;;
review) limit=60 budget=20 ;;
address) limit=60 budget=12 ;;
esac
limit=${FACTORY_LIMIT_MINUTES:-$limit}
budget=${FACTORY_BUDGET_USD:-$budget}

slug() {
    gh issue view "$1" --repo "$FACTORY_REPO" --json title --jq .title |
        tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//' | cut -c1-40 | sed -E 's/-+$//'
}

# A worktree on <branch>, created from <start> unless the branch already
# exists on origin, in which case it continues from there. An existing
# worktree is fast-forwarded to origin's branch.
branch_worktree() {
    local dir=$1 branch=$2 start=$3
    if [[ -d $dir ]]; then
        if git -C "$FACTORY_HOME" ls-remote --exit-code --heads origin "$branch" >/dev/null 2>&1; then
            git -C "$dir" fetch -q origin "$branch"
            git -C "$dir" merge -q --ff-only FETCH_HEAD ||
                die "$dir has diverged from origin/$branch; fix it by hand"
        fi
        return
    fi
    if git -C "$FACTORY_HOME" ls-remote --exit-code --heads origin "$branch" >/dev/null 2>&1; then
        git -C "$FACTORY_HOME" fetch -q origin "$branch"
        git -C "$FACTORY_HOME" worktree add -q -B "$branch" "$dir" "origin/$branch"
    else
        git -C "$FACTORY_HOME" worktree add -q -B "$branch" "$dir" "$start"
    fi
}

detached_worktree() {
    local dir=$1 rev=$2
    [[ -d $dir ]] && git -C "$FACTORY_HOME" worktree remove --force "$dir"
    git -C "$FACTORY_HOME" worktree add -q --detach "$dir" "$rev"
}

# The base of new worktrees: main, unless FACTORY_REF names another
# branch (to try the factory itself before it is merged).
base=origin/${FACTORY_REF:-main}
git -C "$FACTORY_HOME" fetch -q origin
cleanup_dir=
case $kind in
tick)
    # The tick runs in the trusted checkout and reads its inputs from
    # there, under .factory/ (gitignored).
    dir=$trusted
    mkdir -p "$dir/.factory/tick"
    cp "$arg" "$dir/.factory/tick/snapshot.json"
    cp "${1:?previous snapshot}" "$dir/.factory/tick/previous.json"
    shift
    prompt="/factory-tick .factory/tick/snapshot.json .factory/tick/previous.json $*"
    ;;
triage)
    dir=$worktrees/triage-$arg
    detached_worktree "$dir" "$base"
    cleanup_dir=$dir
    prompt="/factory-triage $arg"
    ;;
spec)
    # An existing spec branch is reused, whatever the issue's title has
    # become since.
    head=$(gh pr list --repo "$FACTORY_REPO" --state all --search "head:spec/$arg-" --json headRefName \
        --jq "[.[] | select(.headRefName | test(\"^spec/$arg-\"))][0].headRefName // empty")
    branch=${head:-spec/$arg-$(slug "$arg")}
    dir=$worktrees/${branch//\//-}
    branch_worktree "$dir" "$branch" "$base"
    prompt="/factory-spec $arg"
    ;;
build | address)
    head=$(gh pr list --repo "$FACTORY_REPO" --state open --search "head:$arg-" --json headRefName \
        --jq "[.[] | select(.headRefName | test(\"^$arg-\"))][0].headRefName // empty")
    branch=${head:-$arg-$(slug "$arg")}
    dir=$worktrees/$branch
    branch_worktree "$dir" "$branch" "$base"
    prompt="/factory-$kind $arg"
    ;;
review)
    pr=$(gh pr list --repo "$FACTORY_REPO" --state open --search "head:$arg-" --json number,headRefName \
        --jq "[.[] | select(.headRefName | test(\"^$arg-\"))][0].number // empty")
    [[ -n $pr ]] || die "#$arg has no open PR to review"
    git -C "$FACTORY_HOME" fetch -q origin "pull/$pr/head"
    dir=$worktrees/review-$arg
    detached_worktree "$dir" FETCH_HEAD
    # Claude Code loads the agents, skills and CLAUDE.md of its working
    # directory: the reviewers and their rules come from main, never
    # from the PR under review. The PR's own versions stay in its
    # commits (git diff origin/main...HEAD).
    git -C "$dir" checkout -q --no-overlay "$base" -- .claude CLAUDE.md scripts/factory
    # The other files Claude Code loads from a working directory: main's,
    # or none.
    for file in $(git -C "$dir" diff --name-only "$base" -- CLAUDE.local.md .mcp.json ':(glob)**/CLAUDE.md' ':(glob)**/CLAUDE.local.md'); do
        if git -C "$dir" cat-file -e "$base:$file" 2>/dev/null; then
            git -C "$dir" checkout -q "$base" -- "$file"
        else
            rm -f "$dir/$file"
        fi
    done
    cleanup_dir=$dir
    prompt="/factory-review $arg $pr"
    ;;
esac

# A job refused before it starts still ends with a result line, so that
# the loop and the tick can say why.
refuse_job() {
    jq -cn --arg kind "$kind" --arg issue "$arg" --arg reason "$1" \
        '{kind: $kind, issue: $issue, status: "refused", reason: $reason, exit: 0, log: null, seconds: 0, cost: null, result: null}'
    exit 0
}

# A local settings file in the job's worktree would load with the job
# and could loosen its permissions.
[[ ! -e $dir/.claude/settings.local.json ]] || refuse_job "the worktree has a .claude/settings.local.json"
# Claude Code loads the hooks, skills, agents and instructions of the
# job's worktree, and the job runs its scripts: a branch that changes
# them would change the rules of its own job. The factory does not build
# its own changes: David does. The branch's changes are measured from
# where it forked from main, not against today's main.
factory_paths=(.claude CLAUDE.md CLAUDE.local.md .mcp.json scripts/factory factory
    ':(glob)**/CLAUDE.md' ':(glob)**/CLAUDE.local.md')
if [[ $kind == spec || $kind == build || $kind == address ]]; then
    fork=$(git -C "$dir" merge-base "$base" HEAD)
    if ! git -C "$dir" diff --quiet "$fork" HEAD -- "${factory_paths[@]}" ||
        [[ -n $(git -C "$dir" status --porcelain -uall -- "${factory_paths[@]}") ]] ||
        [[ -n $(git -C "$dir" status --porcelain -uall --ignored -- .claude) ]]; then
        refuse_job "the branch changes the factory itself (.claude/, a CLAUDE.md, .mcp.json, scripts/factory/ or ./factory): David builds it by hand"
    fi
fi

stamp=$(date -u +%Y%m%dT%H%M%SZ)
name=${kind}-${arg##*/}
[[ $kind == tick ]] && name=tick
log=$FACTORY_STATE/logs/$stamp-$name.jsonl
[[ $kind != tick ]] && ln -sf "$log" "$FACTORY_STATE/logs/latest-$arg.jsonl"

flags=(-p "$prompt"
    --permission-mode dontAsk --permission-prompts none
    --settings "$trusted/.claude/factory-job.json"
    --max-budget-usd "$budget"
    --output-format stream-json --verbose)
if [[ $kind == tick ]]; then
    # The tick reads untrusted issue text in the checkout whose scripts
    # guard it: it may write its comment files under .factory/ and
    # nothing else.
    flags+=(--json-schema "$(cat "$FACTORY_SCRIPTS/tick-schema.json")" --disallowedTools)
    for path in ./scripts/** ./.claude/** ./.github/** ./docs/** ./config/** ./kaos-core/** ./kaos-server/** ./kaos-ui/** \
        ./factory ./pom.xml ./mvnw ./run.sh ./CLAUDE.md ./README.md; do
        flags+=("Edit($path)" "Write($path)")
    done
fi

start=$(date +%s)
(cd "$dir" && exec claude "${flags[@]}") </dev/null >"$log" 2>&1 &
pid=$!
jq -n --arg kind "$kind" --arg issue "$arg" --argjson pid "$pid" --arg log "$log" --argjson start "$start" \
    '{kind: $kind, issue: $issue, pid: $pid, log: $log, started: $start}' >"$FACTORY_STATE/job.json"

status=ok
deadline=$((start + limit * 60))
while kill -0 "$pid" 2>/dev/null; do
    if (($(date +%s) > deadline)); then
        status=timeout
        kill -INT "$pid" 2>/dev/null || true
        sleep 10
        kill -TERM "$pid" 2>/dev/null || true
        sleep 10
        kill -KILL "$pid" 2>/dev/null || true
        break
    fi
    sleep 5
done
code=0
wait "$pid" || code=$?
[[ $status == ok && $code != 0 ]] && status=failed
rm -f "$FACTORY_STATE/job.json"

# The tick may write under .factory/ only; anything else it changed in
# the trusted checkout is reverted, and reported.
if [[ $kind == tick && -n $(git -C "$trusted" status --porcelain) ]]; then
    info "the tick changed the trusted checkout: $(git -C "$trusted" status --porcelain | tr '\n' ' '); reverting"
    git -C "$trusted" checkout -q --force HEAD
    git -C "$trusted" clean -q -fd -e .factory
    status=failed
fi

if [[ -n $cleanup_dir ]]; then
    git -C "$FACTORY_HOME" worktree remove --force "$cleanup_dir" 2>/dev/null || true
fi

result=$(grep '"type":"result"' "$log" | tail -n 1)
[[ -n $result ]] || result='{}'
jq -cn --arg kind "$kind" --arg issue "$arg" --arg status "$status" --argjson exit "$code" --arg log "$log" \
    --argjson seconds "$(($(date +%s) - start))" --argjson r "$result" \
    '{kind: $kind, issue: $issue, status: (if $status == "ok" and ($r.is_error // false) then "failed" else $status end),
      exit: $exit, log: $log, seconds: $seconds, cost: ($r.total_cost_usd // null),
      result: ($r.structured_output // null)}'
