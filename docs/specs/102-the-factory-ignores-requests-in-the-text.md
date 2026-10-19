# Spec: the factory reads the text of David's reviews and comments (#102)

## Problem

On a ready PR (`in-review`), the tick acts on three things only: a
review that requests changes, an inline review comment, and a comment
that mentions `@kaos-agent`
(`.claude/skills/factory-tick/SKILL.md`, the `in-review` rule and the
note below the table). Everything else David writes there is read by no
job: an approval with a body, a review with the state `COMMENTED`, a
plain PR comment, a comment on the issue.

So a request is lost unless David phrases it in the one form the tick
recognizes. On #100, David approved with "LGTM. Let's file an issue to
remove it from the API too."; no rule matched, the PR was merged, and
the follow-up issue had to be filed by hand. The same happens to a
question ("why did you keep the field?") or to a change asked in an
approval ("LGTM once the typo in the doc is fixed").

There is a second gap: once the PR is merged, `Closes #N` closes the
issue, and the tick looks at open issues only. Text David writes just
before or after the merge is never read, even by a rule that would
match it.

The other stages already hand David's text to a job that reads it:
`triaged`, `needs-info` and `blocked` go back to a job when David
writes, and `spec-review` starts the `spec` job again. The one
exception is the body of David's approval of a spec PR, which the tick
closes at once (see "Out of scope").

## Proposal

A new job, `respond`, reads what David wrote on an `in-review` PR, or
on a PR merged since, and decides what each piece asks for. The tick
does not classify text: it only sees that there is some and starts the
job.

### When the tick starts `respond`

"David's text" is every entry of `A.david` (the reviews, review
comments and comments David wrote after the watermark, on the issue and
its PRs) whose `body` is not empty once trimmed. An approval with an
empty body is no text.

The `in-review` rules become, in this order:

| Stage | When | Do |
|---|---|---|
| `in-review` | a `respond` handoff newer than the stage with `send_back: yes` | `stage.sh N changes-requested` |
| `in-review` | `A.david` has a review that requests changes or a review comment (inline) | `stage.sh N changes-requested` (unchanged) |
| `in-review` | the PR's `checks` is `fail` | `stage.sh N in-progress`, candidate for `address` (unchanged) |
| `in-review` | David's text is not empty | candidate for `respond` |

The `@kaos-agent` mention is no longer a rule: a comment that mentions
it is David's text like any other, and `respond` decides whether it
asks for a change (see "Open questions").

Merged PRs: for each PR of `closed_prs` that is merged, authored by the
bot, whose head matches `^[0-9]+-` (not a spec PR), the tick runs
`activity.sh` on its issue (the number of the head) and, when David's
text is not empty, makes that issue a candidate for `respond`. The
issue is closed and keeps its `in-review` label, which no rule removes.
`status.sh` adds `updated_at` to each entry of `closed_prs`, so that a
comment on a merged PR changes the snapshot and triggers a tick, as one
on an open PR does.

In the choice of the next job, `respond` comes second, right after a
job David requested: David is waiting for it, and it is short.

The loop's `stage_fits` lets `respond` run on an issue that carries
`in-review`, open or closed; nothing else.

### What `respond` does

`.claude/skills/factory-respond/SKILL.md`, run by `job.sh respond <N>`
in `.claude/worktrees/respond-<N>`, detached at `main` and removed
afterwards, as `triage` is. It changes no code and pushes nothing.
Limits: 15 minutes and $3.

1. Notes `seen_until`, runs `scripts/factory/activity.sh N`, and takes
   David's text (as defined above). It reads the PR, its diff, the
   issue, the triage and the spec when there is one, to understand it.
2. Splits each entry into the requests it holds (one approval can hold
   a remark and a follow-up) and classifies each as one of:
   - **remark**: asks for nothing ("LGTM", "nice", "thanks"). No action,
     no reply.
   - **question**: asks for an answer, not a change. It replies on the
     PR with the answer, quoting the question.
   - **follow-up**: asks for work outside this PR: "file an issue",
     "later", "too", "separately", or a change outside the issue's
     scope. It files one issue per request with `gh issue create`, no
     labels (the tick gives it `needs-triage`, the only way a stage
     label is set), a title in the repository's issue style, and a body
     with the problem, "Follow-up of #<PR> (#N), asked in <URL of
     David's entry>", and David's words quoted. It then replies on the
     PR: "Filed #<new>: <title>."
   - **change**: asks to change this PR before it merges ("fix the typo
     first", "rename this"). On an open PR, it replies "Sending back for
     an address round: <the change>." and hands off with `send_back:
     yes`. On a merged PR, there is nothing to send back: it files the
     change as a follow-up and says so in the reply.
   - **unclear**: it cannot tell which of the above. It replies with
     one question, which offers the readings it hesitates between, and
     does nothing else. David's answer is new text, and starts
     `respond` again.
3. Replies once per entry, as one `gh pr comment <PR> --body-file`,
   that starts with a link to the entry it answers. An entry of
   remarks only gets no reply.
4. Hands off on the issue:

   ````
   **Respond** to David on #<PR>: <one line per request: what it was, what was done>.

   ```yaml
   factory:
     job: respond
     pr: <PR>
     merged: <yes | no>
     follow_ups: [<issue numbers filed>]
     send_back: <yes | no>
     seen_until: <the time noted first>
   ```
   ````

   When everything was a remark, the handoff is still posted, with the
   one line "nothing asked", since it is what moves the watermark; it
   goes on the issue, not on the PR.

Before acting on an entry, the job checks that it has not acted on it
already: it skips an entry whose URL a bot comment on the PR, or a bot
issue's body, already names. A job that dies after filing an issue and
before its handoff therefore does not file it twice on its next run.

### An approval that asks for a change

The text decides, not the review state:

- a change to this PR ("LGTM once …", "LGTM, but rename …") is a
  **change**: the PR goes back to `changes-requested`, the `address`
  job pushes new commits, so the approval no longer covers the head,
  and `./factory merge` refuses until David approves again;
- work outside it ("… too", "file an issue …", "later") is a
  **follow-up**: the approval stands and the PR stays `in-review`;
- when it is not clear which ("LGTM. We should also handle X." where X
  could fit in this PR), it is **unclear**: the bot asks, and the PR
  stays `in-review` and approved.

### The watermark

The watermark is the `seen_until` of the last handoff of a job that
reads David's comments. `respond` is such a job, so its handoff moves
the watermark and a later tick does not start it again for the same
text; nothing changes in `activity.sh` for that.

One exception: a `respond` handoff with `send_back: yes` does not move
the watermark, like a `review` handoff. The `address` job that follows
must still see David's text, the request included, in `A.david`.
`activity.sh` excludes it from the readers:
`[$handoffs[] | select(.job != "review" and (.job != "respond" or .fields.send_back != "yes"))]`.
The tick tells that the send-back is pending by the handoff being newer
than the `in-review` label (the first rule above); once the issue is in
`changes-requested`, that rule no longer applies.

A merge that lands before the tick reads the text loses nothing: the
watermark lives on the issue, the merged PR stays in `closed_prs` for
two days, and the text is still after the watermark.

## API and JSON

No change of the Kaos Java API, HTTP API or JSON. The factory's own
interfaces change, compatibly:

- `tick-schema.json`: `respond` joins the `job` enum.
- `job.sh respond <N>`: a new kind, with its worktree and limits.
- `status.sh`: each entry of `closed_prs` gains `updated_at`.
- `activity.sh`: a `respond` handoff with `send_back: yes` is not a
  reader for the watermark.
- A new handoff, `job: respond`, with the fields above.

`stage.sh` does not change: `in-review → changes-requested` is already
legal, and `respond` moves no stage.

## UX

No UI change. What David sees on GitHub, for the four kinds of text:

- "LGTM." with an approval: nothing on the PR; a one-line `respond`
  handoff on the issue.
- "LGTM. Let's file an issue to remove it from the API too." (the #100
  case): a new issue by `kaos-agent[bot]`, then the PR comment
  "[Your review](…): Filed #105: Remove the classic generation from the
  API." The PR stays approved and `in-review`.
- "Why keep `generation` in the JSON?": the PR comment with the answer.
- "LGTM once the doc typo is fixed": the PR comment "Sending back for
  an address round: fix the typo in …", then the usual address and
  review rounds, and the PR comes back to David.

## Alternatives

- **The tick classifies the text.** It already reads `A.david`, and it
  would save a job. But the tick runs every poll with a $1 cap and a
  fresh context, reading text anyone can write next to the scripts that
  guard it: it should apply rules, not judge intent. It also writes no
  handoff for a remark, so the watermark would not move, and every
  later tick would classify the same text again, possibly differently.
- **Send all of it to `address`.** Simple: any text on `in-review` goes
  to `changes-requested`. But every "LGTM, file an issue" would leave
  `in-review`, run the gates and a review round, and invalidate nothing
  while costing the most expensive loop of the factory; and `address`
  runs in the PR's worktree, which a merged PR no longer has.
- **Keep `@kaos-agent` as the only trigger.** The current rule. It is
  what failed on #100: David writes the request where he reviews, and
  should not have to remember a keyword.

## Architecture

`docs/factory/README.md`:

- "Architecture", the job list, the worktree table and "Limits":
  `respond`, `respond-<N>` detached at `main`, 15 minutes and $3.
- "Labels", the transition table: the `in-review` rows above; the note
  "A plain comment on a ready PR is not a request for changes" is
  replaced by a pointer to `respond`.
- "A 'new' comment": `respond` reads David's comments, and a send-back
  handoff does not move the watermark.
- "Jobs": a new section "7. `respond`" after `address`, with the
  classification and the actions; "6. `address`" no longer says that
  `@kaos-agent` sends a PR back.
- "Files": `.claude/skills/factory-respond/SKILL.md`.

`.claude/skills/factory-tick/SKILL.md`: the `in-review` rules, step 4
for merged PRs, the job order; the note below the table goes.
`.claude/skills/factory-address/SKILL.md`: its step 1 already reads
`A.david`; it adds that a `respond` send-back names the change to make.
`docs/ARCHITECTURE.md` does not change: it describes Kaos, not the
factory.

## Tests

- `scripts/factory/test/activity_test.sh`: a `respond` handoff moves
  the watermark; one with `send_back: yes` does not, and David's
  approval before it stays in `david`.
- `scripts/factory/test/status_test.sh`: `closed_prs` carries
  `updated_at`.
- The loop's `stage_fits`, if it has a test (`selftest_test.sh`):
  `respond` runs on `in-review`, open or closed, and on nothing else.
- The skills cannot be unit tested. The PR's "How to test" replays the
  four cases of "UX" on a throwaway PR of the bot, opened against
  `main` and closed after: a remark, a follow-up in an approval, a
  question, a change in an approval; then the follow-up after a merge,
  with a comment on a merged PR of the last two days. Each case lists
  the expected stage and comments, and that a second tick does nothing.

## Open questions

1. **Does `@kaos-agent` keep a meaning?** Recommendation: no special
   rule; it is text like any other, and `respond` reads it. A mention
   that asks for a change still sends the PR back, through `respond`
   instead of directly.
2. **A remark still costs a job and a one-line handoff on the issue.**
   Recommendation: accept it. The alternative is a tick-side filter
   ("the body is only LGTM"), which is a second classifier to keep in
   sync, and still needs something to move the watermark.
3. **A merged PR's text is read for two days only** (the window of
   `closed_prs`). Recommendation: enough; a later comment is better as
   a new issue.
4. **Unclear text stays `in-review`, not `blocked`.** Recommendation:
   `in-review`, since the PR is still waiting for David and `blocked`
   would hide it among the stuck jobs; `./factory status` already lists
   it as "review the PR".
5. **The body of David's approval of a spec PR** is still ignored: the
   tick closes the spec PR and queues the build. Recommendation: a
   follow-up issue, not this one. `respond` could run there too, but a
   change asked in a spec approval must reach the build, which needs
   its own rule (the build reads the approval's body, or the spec job
   amends the spec first).

## PRs

One PR: `feat(factory): Act on the text of David's reviews and comments`,
with the `respond` skill, the tick rules, `job.sh`, `tick-schema.json`,
`stage_fits`, `status.sh`, `activity.sh`, their tests, and
`docs/factory/README.md`. It changes `.claude/` and `scripts/factory/`,
so `job.sh` refuses to build it: David builds it himself.
