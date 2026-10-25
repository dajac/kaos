# Spec: A job times out when the Mac sleeps (#115)

## Problem

The loop runs on David's Mac and nothing keeps the Mac awake while a
job runs. When the Mac idle-sleeps, the job's Claude session stops, but
its time limit keeps counting: `job.sh` sets
`deadline=$((start + limit * 60))` from `date +%s` and compares the
wall clock against it every 5 seconds. On wake-up the first check finds
the deadline passed, the job is killed as `timeout`, and the next tick
blocks the issue (its first rule blocks on any `failed`, `timeout` or
`refused` job).

That is what happened to the spec job of #103 on 2026-10-23: the log
ends with "API Error: Your computer went to sleep mid-response", the
answer to David's comment on #107 sat uncommitted in the worktree, and
#103 was blocked until David unblocked it by hand the next morning.

Two things are wrong:

1. Nothing prevents the idle sleep while a job runs.
2. When a sleep does happen (the lid is closed, the battery runs low),
   the factory cannot tell it from a real timeout or failure, and
   blocks an issue that only needs the same job run again.

## Proposal

One PR, in three parts.

### 1. Keep the Mac awake while a session runs

`job.sh` starts, right after it has launched `claude`:

```sh
caffeinate -i -w "$pid" &
```

- `-i` holds a `PreventUserIdleSystemSleep` assertion: the Mac does not
  idle-sleep, the display may still sleep, and closing the lid still
  sleeps the Mac.
- `-w "$pid"` releases the assertion as soon as the session ends, so
  there is nothing to kill, whatever way the session ends (exit,
  timeout, `./factory down --now`).
- It covers every session `job.sh` runs: the five jobs and the tick.
  Between sessions, while the loop only polls, the Mac may sleep: the
  loop simply goes on at wake-up, and an idle factory does not keep the
  Mac awake all night.

Where `caffeinate` is missing (not macOS), the session runs without it.
`cmd_loop` logs it once when the loop comes up:
`no caffeinate: a sleep of the machine can interrupt a job`.

### 2. Do not count the time asleep against the limit

The watch loop of `job.sh` moves to `lib.sh` as a function,
`watch_session <pid> <limit-seconds>`, which prints `<status> <slept>`
(`ok` or `timeout`, and the seconds spent asleep). It reads the clock
and pauses through two small functions, `now` (`date +%s`) and `nap`
(`sleep`), that the tests replace.

Each turn of the loop pauses 5 seconds. When a turn takes more than 35
seconds of wall clock, the excess over 5 seconds is a sleep of the
machine: it is added to `slept` and pushes the deadline back by the
same amount.

```sh
deadline=$((start + limit)) slept=0 last=$start
while kill -0 "$pid" 2>/dev/null; do
    t=$(now)
    gap=$((t - last - 5))
    if ((gap > 30)); then
        slept=$((slept + gap))
        deadline=$((deadline + gap))
    fi
    last=$t
    ((t > deadline)) && { stop_session "$pid"; echo "timeout $slept"; return; }
    nap 5
done
echo "ok $slept"
```

This holds whether `sleep 5` resumes at wake-up or after 5 more awake
seconds: either way the turn's wall-clock length is about the time
asleep. A loaded machine never stalls a 5-second `sleep` by 30 seconds,
so a real timeout still ends as `timeout`. `pmset -g log` was the other
option (see "Alternatives").

### 3. A job cut short by a sleep is retried, not blocked

**A new status, `interrupted`.** `job.sh` reports `interrupted` when
the watch saw a sleep (`slept > 0`) and the session did not end `ok`
(it ended `failed`, an `is_error` result such as "Your computer went to
sleep mid-response", or `timeout`). A session that saw a sleep and
still ended `ok` stays `ok`: Claude Code may have recovered on its own.
`refused` is never `interrupted`, since the job did not start. The
result line gains `slept`:

```json
{"kind": "spec", "issue": "103", "status": "interrupted", "exit": 1,
 "log": ".../20261023T132241Z-spec-103.jsonl", "seconds": 31205,
 "slept": 29820, "cost": 2.41, "result": null}
```

**A cap, in the loop.** `cmd_loop` counts the consecutive `interrupted`
ends of the same job (kind and issue), in memory. The first two are
passed to the tick as `interrupted`; the third becomes `failed` with
the reason `interrupted by a sleep 3 times in a row`, which the tick
blocks as today. Any other end, or another job, resets the count. A
restart of the loop resets it too, which is fine: David restarts it.

The loop logs the cause:

```
2026-10-23T22:02:46Z job: spec #103: {"status":"interrupted","seconds":31205,"slept":29820,...}
2026-10-23T22:02:46Z job: spec #103 interrupted by a sleep of 8h17m (1 of 2 retries)
```

As after any other non-`ok` end, the next tick waits for the next poll,
which gives the network time to come back after the wake-up.

**The tick.** `last-job`'s status may be `interrupted`. One row is
added to the table, after "a blocked handoff newer than the stage":

| Stage | When | Do |
|---|---|---|
| any | `last-job` is an `interrupted` job of N, and N has no handoff of that job newer than the job's start | nothing on the stage: N is a candidate for the same job |

An interrupted job that handed off before the sleep falls through to
the usual rules, which apply its handoff. "Choosing the next job" gets
a new rule 2, after David's requests: the retry of an interrupted job.
The loop's `stage_fits` check is unchanged: the issue is still in the
stage of that job.

**The draft of the interrupted run.** The spec, build and address
worktrees are kept between runs (`branch_worktree` fast-forwards them
and keeps the working tree), so the uncommitted work of the interrupted
run is still there for the retry. The skills `factory-spec`,
`factory-build` and `factory-address` get one rule: "The worktree may
hold the uncommitted work of an interrupted run: read `git status` and
`git diff` first, and keep what still fits." The triage and review
worktrees are removed after every run, as today: they hold no draft
worth keeping (a triage writes one comment, a review starts from a
fresh checkout on purpose).

## API and JSON

No change of the Java API, the HTTP API or its JSON. The factory's own
interfaces change, compatibly:

- `job.sh`'s result line gains `slept` (seconds, `0` without a sleep)
  and the status `interrupted`.
- The tick's `last-job` argument may carry the status `interrupted`.
- `lib.sh` gains `watch_session`, `now` and `nap`.

## UX

The loop log line above. `./factory status` is unchanged: an
interrupted job leaves its issue in its stage, and the retry shows as
the running job.

## Alternatives

- **`caffeinate -i -w <loop pid>` from `cmd_loop`**: the Mac stays
  awake whenever the loop is up, even idle between jobs. It also covers
  the tick and the polls, but it keeps an idle factory's Mac awake for
  nothing; nothing is lost when the Mac sleeps between sessions.
- **`caffeinate -i -s`**: `-s` only prevents system sleep on AC power;
  it does not keep a MacBook awake with the lid closed (clamshell needs
  an external display), so it adds nothing to `-i` here. The issue
  wants the lid to keep sleeping the Mac anyway.
- **Refusing to start without `caffeinate`**: the factory has no other
  hard macOS dependency in the loop itself, and the sleep detection
  makes the retry work without it.
- **`pmset -g log`** to find sleeps: macOS only, slow to read, its
  format is not stable, and it cannot be faked in the tests. The gap
  in the watch loop is portable and testable.
- **Retrying in the loop, without a tick**: the loop cannot see a
  handoff the job may have posted before the sleep, and would run the
  job twice. The tick already reads handoffs.
- **Keeping the retry count on GitHub** (a field in a comment): it
  would survive a restart of the loop, but needs a comment per retry;
  the in-memory count is enough for a bound.

## Architecture

`docs/ARCHITECTURE.md` (Kaos itself) does not change.
`docs/factory/README.md` does:

- "Limits": the time limit counts awake time only; a session runs under
  `caffeinate -i`; a job cut short by a sleep ends `interrupted` and is
  retried at most twice, the third interruption blocks as a failure.
  "the tick then blocks the issue rather than retrying" gains "except
  for an interrupted job".
- "Architecture": the tick's inputs list the status `interrupted`.
- The tick's table in the stages section gets the new row, and the
  choice of the next job its new rule 2.
- "Running it": `caffeinate` among the tools the loop uses when present.

## Tests

`scripts/factory/test/job_test.sh` (new), with `now` and `nap` replaced
by a fake clock and a background `sleep` as the session:

- a session that ends before its limit: `ok 0`;
- a session that runs past its limit with no gap: `timeout 0`, and the
  session is stopped;
- a gap of 600 seconds in a 60-second limit, the session ending at 30
  awake seconds: `ok 600`;
- a gap, then more awake time than the limit: `timeout <gap>`;
- gaps of 20 seconds (a loaded machine): not counted.

The status function (`job_status <watch-status> <slept> <exit>
<is_error>`, in `lib.sh`): `interrupted` for `failed` or `timeout` with
`slept > 0`, `ok` stays `ok`, `failed` and `timeout` without a sleep
are unchanged.

The loop's cap, extracted as `interruption_cap <count> <status>` in
`lib.sh`: the first two `interrupted` pass, the third is `failed` with
the reason; another status resets.

By hand, on the Mac: during a job, `pmset -g assertions` lists
`PreventUserIdleSystemSleep` held by `caffeinate`, and no more once the
job has ended; closing the lid during a job sleeps the Mac, and at
wake-up the loop logs the interruption and the next tick reruns the
job.

## Open questions

1. **Per session or for the whole loop?** Recommendation: per session
   (every job and the tick), `caffeinate -i -w <session pid>` in
   `job.sh`.
2. **`-i` or `-i -s`?** Recommendation: `-i` alone; the lid still
   sleeps the Mac.
3. **Without `caffeinate`?** Recommendation: run without it and log it
   once when the loop comes up.
4. **The cap**: two retries (three runs) in a row, counted by the loop
   in memory? Recommendation: yes.
5. **The gap threshold**: 30 seconds beyond the 5-second pause?
   Recommendation: yes.
6. **Any non-`ok` end after a sleep is `interrupted`**, even one that
   would have failed anyway? Recommendation: yes; the cap bounds the
   cost, and a sleep makes the cause unknowable.
7. **Resuming the draft**: the rule in the spec, build and address
   skills? Recommendation: yes.
