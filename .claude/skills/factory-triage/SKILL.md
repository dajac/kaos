---
name: factory-triage
description: Triage one issue of Kaos for the software factory, run headless by the factory's loop. It investigates the issue and posts a proposal for David with a handoff. Not for interactive use.
disable-model-invocation: true
---

# Triage

You triage one issue for the factory (`docs/factory/README.md`, "Jobs",
"2. triage"). You run headless, as the bot `kaos-agent[bot]`, in a
worktree detached at `origin/main` that is removed afterwards: change
no file, commit nothing. Nobody answers questions: when you cannot
decide, say so in the proposal.

Argument: the issue number.

## Rules

- First run `date -u +%Y-%m-%dT%H:%M:%SZ` and keep the result as
  `seen_until`: the moment you read the issue (step 0).
- Read `CLAUDE.md` and the parts of `docs/ARCHITECTURE.md` the issue
  touches before judging it.
- Use one command per Bash call; no `cd`, no `for` loops, no `$(...)`
  assignments. Use `git -C`, `./mvnw -pl <module>` and paths instead.
- You may add the **type** and **area** labels (`bug`, `enhancement`,
  `task`, `documentation`, `area:*`) with `gh issue edit N --add-label`.
  Never touch a stage label, `accepted`, `needs-spec` or
  `priority:high`, never close the issue, never edit its body.
- Write for David, briefly, in the style of the existing Kaos issues.
  The UI shows the group as the coordinator sees it: when the issue is
  about the UI, check what the coordinator reports, not only what the
  client sees.

## Steps

1. Read the issue and its comments: `gh issue view N --comments`. If it
   was triaged before, read the previous proposal and what David wrote
   since, and revise rather than restart.
2. Look for duplicates and related work: `gh issue list --state all
   --search "<key words>"`, `gh pr list --state all --search ...`.
3. Locate the code, the tests and the part of `docs/ARCHITECTURE.md`
   involved. For a bug, try to reproduce it at the cheapest level that
   shows it: an existing test, a new test you run without committing
   (`./mvnw -q -pl <module> test -Dtest=<Class>`), or by reading the
   code. Say how you reproduced it, or that you could not.
4. Decide the route:
   - `spec` when the issue changes the public Java API, the HTTP API or
     its JSON, the UX of a whole view, the architecture, or the
     factory itself; when it needs several PRs; or when you found two
     reasonable designs and cannot pick one;
   - `build` for a bug with a clear expected behavior, a small UI fix,
     a task or docs;
   - `needs-info` when the expected behavior cannot be inferred: ask
     the precise questions;
   - `close` for a duplicate or something already fixed: point to it.
     It is a proposal: David closes.
5. Add the type and area labels.
6. Post one comment with `gh issue comment N --body-file <file>` (write
   the file under `.factory/` of the worktree), in this shape:

   ````
   **Triage**

   <two to five sentences: what the problem is, where it comes from,
   how it was reproduced or why it could not be>

   **Proposal:** <build | spec | needs info | close>, because <reason>.
   <For spec: the questions the spec must answer. For needs info: the
   questions. For close: the duplicate or the fix.>

   **Acceptance criteria**
   - <observable, testable statements>

   **Where:** `path/to/File.java`, `kaos-ui/src/...`; <related #N>

   To accept it, add the `accepted` label (keep or remove `needs-spec`);
   to change it, comment; to drop it, close the issue.

   ```yaml
   factory:
     job: triage
     type: <bug | enhancement | task | documentation>
     areas: [<core | server | api | ui | build | protocol | factory>]
     route: <build | spec | needs-info | close>
     size: <s | m | l>
     acceptance:
       - <the same criteria, one line each>
     files: [<paths>]
     seen_until: <the time of step 0>
   ```
   ````

   When the route is `spec`, also add the `needs-spec` label: it is a
   proposal David keeps or removes.

That comment is the handoff: the factory's next tick reads it. Do
nothing after posting it.
