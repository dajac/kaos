---
name: factory
description: Show the state of the Kaos software factory (what waits for David, what is in flight, the loop) and queue a job. Use when David asks about the factory, the pipeline, or what is waiting for him.
---

# The factory

The factory is described in `docs/factory/README.md`. In an interactive
session:

1. Run `./factory status` and summarize it for David: what waits for
   him first (triage proposals to accept, specs and PRs to review,
   blocked issues), then what is in flight and queued. Link the issues.
2. If the loop is down, say so and that `./factory up` starts it.
3. When David asks for a job, queue it with `./factory run <job> <N>`
   (`triage`, `spec`, `build`, `review`, `address`); the loop's next
   tick runs it if the transition is legal. Do not run the job's skill
   yourself.

What only David does: adding the `accepted` label, approving or
merging a PR (`./factory merge`), closing an issue, resolving a review
thread. This session runs with his login, so do one of these only when
he asks for that one, here and now.
