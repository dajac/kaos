# Spec: Merge the PRs that David approves (#103)

## Problem

David approves a ready PR on GitHub, then has to run
`./factory merge <PR>` in his own terminal. The approval is the
decision; the merge is a mechanical step that he forgets, so approved
PRs wait (#70 and #74 sat approved in `./factory status`). Nothing in
the factory can take that step today, by design:

- `docs/factory/README.md` lists auto-merge as a non-goal and makes
  David "the only one who merges" (goal 5, stage 6, "Guardrails");
- `scripts/factory/merge.sh` refuses the bot's identity and any `gh`
  login other than `dajac`;
- the guard hook refuses `gh pr merge`, `pulls/<N>/merge`,
  `mergePullRequest`, `merge.sh` and `./factory merge` in every job.

## Proposal

The approval stays David's gate; the merge becomes the factory's. The
**loop** (`./factory up`, plain bash, no Claude session) merges every
PR that passes the gate on each poll, with the bot's identity and the
same checks and squash as `./factory merge`. No Claude session, job or
tick, ever merges: the guard keeps refusing every way to merge in
them.

### Who merges: the loop, not a job

On each poll, after the snapshot and before the tick, the loop runs
one merge pass, unless the factory is paused:

1. It picks the candidates from the snapshot with `jq`, so an idle
   poll costs no extra call: an open PR authored by the bot
   (`app/kaos-agent`), not a draft, based on `main`, whose head matches
   `^[0-9]+-` (never `spec/`), with `review` `APPROVED`, `checks`
   `pass`, and closing an issue in `in-review`. Lowest PR number first.
2. For each, it runs the trusted checkout's
   `scripts/factory/merge.sh --auto <PR>`, which re-checks the whole
   gate against GitHub (below) and merges, or prints why it holds.
3. When it merged anything, it takes the snapshot again, so that the
   tick that follows sees the merged PRs in `closed_prs` and cleans up
   their worktrees as today.

The loop already runs with `identity.sh` sourced, so `gh` carries the
bot's installation token; the App has Contents and Pull requests write,
which the merge API needs. The squash is authored by the PR's author,
the bot, as today.

Why the loop: the gate is mechanical, so it needs no model, and the
loop is the only part of the factory that is not a Claude session, so
the guard needs no exception. The loop's scripts come from the trusted
checkout at `origin/main`, so a branch cannot change the merge of its
own PR.

### The gate

`merge.sh` keeps today's checks, the same in both modes:

- the PR is open, not a draft, based on `main`;
- David's last review that approves or requests changes is `APPROVED`
  and its `commit_id` is the PR's current head: **a push after the
  approval voids it**, and David approves the new head;
- no unresolved review thread (and at most 100 threads);
- at least one check, every check `pass` or `skipping`;
- the merge is pinned to that head (`sha=<head>`), so a push between
  the check and the merge makes GitHub refuse it.

`--auto` adds:

- it runs only with the bot's identity (`FACTORY_IDENTITY` set; the
  interactive mode keeps refusing it);
- the PR is authored by the bot, its head is not `spec/…`, and the
  issue it closes is in `in-review`;
- **the approval carries no request** (below).

### Approvals that carry a request (#102)

An approval whose text asks for something ("LGTM. Let's file an issue
to remove it from the API too.") must be handled before the merge, and
#102 (reading the text of reviews) is not built yet. A bash script
cannot tell a follow-up from a condition, so `--auto` takes the safe
reading: it **holds** the merge when

- the approving review's body, trimmed, is anything but empty or a
  bare approval: case-insensitive `lgtm`, `looks good`, `looks good to
  me`, `ship it`, optionally followed by `.` or `!`; or
- David wrote anything on the PR or on its issue after the approval
  (a comment, a review comment, or another review that neither approves
  nor requests changes).

A held PR stays `in-review` and approved. The loop posts one comment on
the PR, once per approval (a hidden marker
`<!-- factory-merge-held: <review id> -->` in the bot's comment tells
whether it was posted), with the reason and the way out:

> Approved, but not merged automatically: the approval has a note. Once
> it is handled, run `./factory merge 90`, or approve again without a
> note.

`./factory merge` still merges it, since it does not apply the note
rule: David's own merge is his decision. `./factory status` shows the
held PR in "Waiting for David" with the action `merge by hand`
(an `in-review` issue whose PR is `APPROVED` with CI `pass` and still
open after the loop's merge pass).

When #102 lands, its job handles the request and records it in a
handoff (`request: handled`, newer than the approval); `--auto` then
counts the note as handled and merges. That is #102's spec to settle;
this one only leaves the hook for it.

### Failures

| Case | What happens |
|---|---|
| CI red after the approval | Not a candidate. The tick's existing rule (`in-review`, `checks` `fail`) starts an `address` job, whose new commits void the approval. |
| CI pending | Not a candidate; the next poll tries again. |
| A push after the approval | `--auto` holds without a comment: the PR is not approved on its head any more, and David reviews it again. |
| The PR conflicts with `main` | See "Conflicts with `main`" below: back to `in-progress`, the `address` job merges `main` in, David approves again. |
| Any other refusal (405, 409, 5xx, a lost token) | Logged in `loop.log`; retried on the next poll; one comment on the PR per approval and reason after three failed polls in a row. |
| A stale base without a conflict | Merged, as `./factory merge` does today: there is no "up to date" rule without branch protection, and CI runs on the merge commit with `main`. |

GitHub notifies David of the merge itself (he is the PR's reviewer).

### Conflicts with `main`

Nothing in the factory looks at conflicts today: `./factory merge`
lets GitHub refuse the PUT, and David brings `main` in by hand. With
the loop merging, a conflict shows up in two ways: `main` moved since
the approval (another PR merged, possibly in the same pass), or the PR
conflicted already when David approved it.

**Detection, in `merge.sh --auto`.** After the gate passes, and before
the PUT, it reads the PR's `mergeable` (GraphQL, in the same query as
the threads). GitHub computes it asynchronously, so:

- `MERGEABLE`: it merges, pinned to the head as above;
- `UNKNOWN`: it holds without a comment (exit 3, "mergeability not
  computed yet"); the next poll reads it again;
- `CONFLICTING`: the conflict path below (exit 3).

Within one pass, merging a lower PR can make a higher one conflict
while GitHub still reports the old `MERGEABLE`. The PUT then answers
405 ("not mergeable"); `--auto` logs it and does nothing else, and the
next poll reads the recomputed `mergeable`. The pass never retries a
PR in the same poll.

**The conflict path.** `merge.sh --auto`:

1. moves the issue back with `stage.sh <N> in-progress`;
2. posts one comment on the PR, once per head (a hidden marker
   `<!-- factory-merge-conflict: <head sha> -->`):

   > Approved, but not merged: the branch conflicts with `main`. The
   > address job merges `main` into it; review the new head and approve
   > it again.

The approval is not dismissed: the next push voids it, as any push
does.

**The resolution, in the `address` job.** The tick's existing rule
(`in-progress`, the PR exists, the last handoff is a `build`,
`review`, `address` or `tick`) starts `address`. The job does not
take the conflict from the bot's comment, which is data: it reads
`mergeable` itself (`gh pr view <PR> --json mergeable`) and, when it
is `CONFLICTING`,

- merges `origin/main` into the branch (`git merge origin/main`, a
  merge commit, never a rebase or a force-push, so "changes since
  last review" still works);
- resolves the conflicts keeping the intent of both sides, runs the
  checks of the PR as for any address commit, and pushes;
- says in its handoff what it resolved and how, so that David sees it
  in the review;
- when it cannot resolve a conflict with confidence (the two sides
  change the same behavior in incompatible ways), it aborts the merge,
  pushes nothing, and blocks for David (`blocked: conflicts with main
  in <files>`, `resume: in-progress`).

From there the PR follows the usual path: `reviewing`, the review job,
`in-review`, and David approves the new head before the loop merges
it.

**Conflicts before the approval.** The merge pass looks only at
approved PRs, so a PR that conflicts while waiting for David is found
when he approves it, and costs him a second approval. Catching it
earlier needs `mergeable` in the snapshot (one more GraphQL field per
open PR) and a tick rule (`in-review`, `CONFLICTING` → `in-progress`).
It is left out of this PR (open question 3).

Stacked children (base not `main`) are never candidates, so the merge
pass never sees their conflicts; their rebase stays with "Stacked PRs
(planned)".

### The squash message and `./factory merge`

`merge.sh` stays one script, with two modes:

- `./factory merge <PR>`: David's, unchanged (his `gh` login, no
  note rule), for held PRs, his own PRs and anything the loop skips;
- `merge.sh --auto <PR>`: the loop's.

Both build the message the same way, already tested: the title with
`(#N)` once, the body above `---` without its HTML comments, then the
`Co-Authored-By` lines of the PR's commits, each once. Both then
retarget the PRs based on the branch to `main` and delete the branch.

### Stacked PRs

Unchanged: the merge retargets the children to `main` before deleting
the branch, as `./factory merge` does. A child is not a candidate while
its base is not `main`; the rebase of a child onto `main` stays the
planned `address` rule of "Stacked PRs (planned)", and the new head
needs David's approval again before the loop merges it.

### Spec PRs

Never merged: `--auto` refuses `spec/` heads, and the tick keeps closing
an approved spec PR.

### The guard

Unchanged in what it refuses: `gh pr merge`, `pulls/<N>/merge`,
`mergePullRequest`, `enablePullRequestAutoMerge`, `merge.sh` (with or
without `--auto`, by any path) and `./factory merge` stay refused in
every job and tick. The loop is not a Claude session, so the guard
never sees it. `guard_test.sh` gains cases for `merge.sh --auto`, by
relative and absolute path.

As before, this is a guardrail, not a boundary: the App's token can
merge, and a job that ran code the guard cannot see could too. The
breach check below is the after-the-fact control.

### The breach check (planned)

"Each new commit on `main` comes from a PR merged by David" becomes:
each new commit on `main` is the squash of a PR
(`GET /repos/dajac/kaos/commits/<sha>/pulls`) that is

- merged by `dajac`; or
- merged by `kaos-agent[bot]`, with David's last approving-or-changes
  review `APPROVED` on the PR's final head (`commit_id` equal to the
  PR's `head.sha`), and authored by the bot.

Everything it needs stays on GitHub after the merge. It stays
planned; this spec only changes its rule in the README.

## API and JSON

No change to the public Java API, the HTTP API or its JSON. The
factory's own interfaces:

- `scripts/factory/merge.sh --auto <PR>`: exit 0 when merged, 3 when
  held (the reason on stderr, one line), 1 on an error;
- `./factory merge <PR>`: unchanged;
- the snapshot of `status.sh`: unchanged (it has `review` and
  `checks` already).

## Alternatives

- **A `merge` job (a Claude session).** It would need an exception in
  the guard for one job kind, and a model to run a deterministic
  check. Rejected: the loop does it for free, and no session ever gets
  the power to merge.
- **The tick merges.** Same objection, and the tick reads text anyone
  can write.
- **GitHub's auto-merge** (`gh pr merge --auto`). It cannot pin the
  approval to the head without branch protection (unavailable on
  GitHub Free), nor drop the review material below `---`, nor hold on
  a note.
- **Merge whatever the approval says, and file its requests later.**
  Simpler, and right for "LGTM, let's file an issue", but wrong for
  "LGTM once X is renamed". Holding costs David one command until #102
  lands; merging a conditional approval costs a revert.
- **Ask a model whether the note is a condition.** That is #102's
  work; until then a bare-approval rule is predictable.

## Architecture

No change to `docs/ARCHITECTURE.md` (Kaos itself is untouched).
`docs/factory/README.md` changes:

- "Problem and goals": goal 5 becomes "David has the final word: he
  accepts issues and approves specs and PRs; his approval of a PR is
  the decision to merge it, which the factory carries out". Non-goals:
  "deployment, a hosted service" (auto-merge dropped).
- "Overview": `D3 -->|approve| M` through the loop, and "the PR
  approval" as the third gate.
- "Architecture": the loop's merge pass between the snapshot and the
  tick, and the re-snapshot after a merge.
- Stage table: `in-review` | David approves the head, threads
  resolved, CI green, no note | the loop merges; the issue is closed by
  `Closes #N`, the next tick cleans up. And `in-review` | the merge
  conflicts | `in-progress`, start `address`.
- Stage 6: the gate, the note rule and the held comment, the failures,
  a "Conflicts with `main`" part as above; `./factory merge` as
  David's manual merge.
- "Identity": the bot merges only from the loop.
- "Guardrails": `merge.sh --auto` in the refused list; the loop is the
  only merger.
- The breach check's new rule (still planned).
- "Running it": `./factory merge` described as the manual merge.
- `.claude/skills/factory-address/SKILL.md`: the job reads the PR's
  `mergeable`; on `CONFLICTING` it merges `origin/main` into the
  branch, resolves, reports the resolution in its handoff, or blocks
  when it cannot resolve with confidence.
- `scripts/factory/overview.sh`: the `merge by hand` action.
- "Decisions": "David's approval on the head is the merge decision; the
  loop merges, never a Claude session."

## Tests

`scripts/factory/test/`, with the fake `gh`:

- `merge_test.sh`, `--auto`:
  - merges a ready bot PR approved on its head with an empty body, or
    `LGTM.`; the PUT carries `sha=<head>`, the title with `(#N)` once,
    the body above `---`, the co-authors once; children retargeted
    before the branch is deleted;
  - holds (exit 3, no PUT) when: the approval is on an older commit;
    the last review requests changes; a thread is unresolved; a check
    is pending or failing, or there is none; the PR is a draft, based
    on another branch, a `spec/` head, or not the bot's; the issue is
    not `in-review`; the approval has a note; David commented after
    it;
  - posts the held comment once per approval, not on the next poll;
  - refuses to run without the bot's identity;
  - `mergeable` `CONFLICTING`: no PUT, `stage.sh <N> in-progress`,
    the conflict comment once per head (not again on the next poll,
    again after a new head); `UNKNOWN`: exit 3, no PUT, no comment, no
    stage change; a 405 on the PUT: logged, no stage change.
- Interactive mode: the existing tests stay green, and one more shows
  that it merges an approval with a note.
- `guard_test.sh`: `merge.sh --auto 90`, `scripts/factory/merge.sh
  --auto 90` and its absolute path are refused in a job.
- The loop's pass: a test of the candidate filter (a function of the
  `factory` script, or a `jq` file under `scripts/factory/`, run on a
  snapshot fixture): only bot, non-draft, `main`-based, approved, green,
  `in-review` PRs, no `spec/`.
- `overview_test.sh`: an approved, green, open `in-review` PR shows
  `merge by hand`.
- By hand, once built: approve a small bot PR without a note and see
  it merged within one poll; approve one with a note and see the held
  comment.

The work fits one PR: `feat(factory): Merge the PRs that David
approves`, with `merge.sh --auto`, the loop's pass, the overview line,
the address skill's conflict rule, the README and the tests. It
touches `scripts/factory/` and `.claude/`, so David builds it himself
(`job.sh` refuses a job on such a branch).

## Open questions

1. **Who merges.** The loop, in bash, with `merge.sh --auto`; no job or
   tick ever merges. *Recommendation: yes.*
2. **Notes in approvals until #102.** Hold anything but a bare approval
   and comment once, or merge anyway and leave the note to David?
   *Recommendation: hold.* Is the list of bare approvals (`lgtm`, `looks
   good`, `looks good to me`, `ship it`) the right one?
3. **Conflicts with `main`.** At merge time, send the PR to `address`
   (it merges `main` in, blocks for you when it cannot resolve with
   confidence, then you approve again), or block it for you right away?
   *Recommendation: `address`; most conflicts are mechanical, and you
   review the resolution anyway.* Should the tick also catch conflicts
   before your approval (`mergeable` in the snapshot)?
   *Recommendation: not in this PR; file it if second approvals turn
   out frequent.*
4. **Pause.** Does `./factory pause` stop the merges too?
   *Recommendation: yes; pause means the factory acts on nothing.*
5. **`./factory merge` stays** for held PRs and David's own, without the
   note rule. *Recommendation: keep it.*
6. **Your own PRs.** PRs opened under `dajac` (the bootstrap, or ones
   you open by hand) are never candidates: GitHub does not let you
   approve them. *Recommendation: keep them out; you merge those.*
