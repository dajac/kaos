# Spec #57: Fault and stop badges hide the coordinator state of a consumer

## Problem

The Rows view shows the group as the coordinator sees it; facts that
only the client knows (its faults, its subscription, its failure) may
annotate a row but must not replace what the coordinator reports.

The consumer badge breaks that rule. It shows `ConsumerSnapshot.status`,
which `StatusDeriver.status` builds with the priority
`FAILED > STOPPED > FROZEN > ISOLATED > coordinator state`. For as long
as a fault lasts, the badge cannot show what the coordinator does about
it:

- "Partition network": the badge says `PARTITIONED` for 45 s while the
  coordinator keeps the member `STABLE` with its partitions, and still
  says `PARTITIONED` after the session timeout, when the coordinator has
  removed the member. Only the cells going blank show the removal.
- "Freeze consumer": the badge says `FROZEN` while the coordinator gives
  the member 6 more partitions.
- "Stop consumer" on a static member: the badge says `STOPPED` while
  the coordinator still lists the member (`coordinatorState`
  `STOPPING`).

Watching how the coordinator reacts to a fault is the point of the
simulator, so the badge hides the most interesting part.

## Proposal

One PR, across core, server and UI, because the enum change below
breaks the type check of the UI until the UI follows.

### Core: `status` becomes the membership to show

`ConsumerSnapshot.status` becomes the coordinator state with the
Stable→Reconciling override, and nothing else:

- `StatusDeriver.status` drops the `FAILED`, `STOPPED`, `FROZEN` and
  `ISOLATED` rules. It returns `RECONCILING` for a consumer-protocol
  member that is `STABLE` for the coordinator and `hasMismatch` locally,
  and `coordinatorState` otherwise. Its parameters shrink to what that
  needs (the protocol and the local assignment instead of the whole
  `ConsumerLocalState`).
- `ConsumerStatus` loses `FAILED`, `FROZEN` and `ISOLATED`. It keeps
  `STOPPED`, `STOPPING`, `JOINING`, `RECONCILING` and `STABLE`, the
  values `coordinatorState` already uses. `STOPPED` keeps its current
  `coordinatorState` meaning: not listed by the coordinator and not
  subscribed.
- The local facts stay where they already are, unchanged: `faults`,
  `subscribed` and `failure`.

When #54 drops the override, `status` equals `coordinatorState`; that PR
can then remove one of the two (see the open questions).

### UI: the badge is the state, the tags are the local facts

- The badge shows `status` with the colours it has today (`STABLE`
  green, `RECONCILING` yellow, `JOINING` blue, `STOPPING` red, `STOPPED`
  grey). The row panel (left rail, border, the dimming of a `STOPPED`
  row) follows the badge.
- Tags follow the name, one per local fact, in this order:

  | Tag | When | Colour | Title |
  |---|---|---|---|
  | `failed` | `failure` is not empty | red (`--red`) | the failure message |
  | `unsubscribed` | `subscribed` is false | grey (`--text-muted`) | none |
  | `frozen` | `faults` has `freeze` | cyan (`--fault`) | none |
  | `network isolated` | `faults` has `network-isolation` | orange (`--orange`) | none |
  | `connection reset` | `faults` has `connection-reset` | orange (`--orange`) | none |

  The order is the most severe first, then the faults in the order of
  `Fault.all()` (which `faults` already follows). A tag is a small
  outlined chip in the colour of the old badge of that fault; the panel
  classes `frozen` and `partitioned` and their CSS are removed.
- A new pure function in `model/rows.ts`, `consumerTags(consumer)`,
  returns the tags (`{text, cls, title?}`) so Vitest can cover them
  without rendering. `consumerBadge` maps `status` only; the
  `ISOLATED`→`PARTITIONED` text and the `FAILED`→`stopping` class go.
- Classic members: with the faults out of the badge, `CLASSIC_BADGES`
  would be empty and a classic member would never show a badge. This
  PR drops `show` and `CLASSIC_BADGES`: every member shows its badge,
  which is the badge part of #58. If #58 lands first, nothing to do
  here. Tags show for both protocols.
- The failure moves from the title of the badge to the title of the
  `failed` tag.

## API and JSON

Breaking for the public Java API and the HTTP API (`!` in the title);
nothing is released yet (`0.1.0-SNAPSHOT`, no tag), so no deprecation
step.

- Java: `ConsumerStatus.FAILED`, `FROZEN` and `ISOLATED` are removed.
  `ConsumerSnapshot.status()` keeps its type; its Javadoc becomes "the
  state to show for the consumer: `coordinatorState`, or `RECONCILING`
  while a partition is in flight". `ConsumerHandle.switchProtocol`'s
  Javadoc (`the consumer is then FAILED`) says "the consumer then has a
  failure" instead. Code that waited for `status() == STABLE` (the
  README example) now also matches a frozen or isolated member that the
  coordinator still has as `STABLE`.
- OpenAPI: `ConsumerStatus` becomes
  `enum: [STOPPED, STOPPING, JOINING, RECONCILING, STABLE]`;
  `src/api/schema.d.ts` is regenerated. No new field.

A frozen consumer that the coordinator keeps, before and after:

```json
{ "id": "consumer-0", "subscribed": true, "faults": ["freeze"],
  "coordinatorState": "STABLE", "status": "FROZEN", "failure": "" }

{ "id": "consumer-0", "subscribed": true, "faults": ["freeze"],
  "coordinatorState": "STABLE", "status": "STABLE", "failure": "" }
```

A failed consumer the coordinator has dropped:

```json
{ "subscribed": true, "faults": [], "coordinatorState": "JOINING",
  "status": "JOINING", "failure": "FencedInstanceIdException: fenced" }
```

The event log keeps its `status: A -> B` line, which now records only
membership changes; faults (`fault freeze injected`) and failures
(`failed: …`) already have their own lines.

## UX

Today on the left, proposed on the right, from a throwaway prototype
that uses the real `rows.css`. The cells are those of today: #54 and
#55 change them, not this spec.

![57-badges-and-tags](https://github.com/dajac/kaos/blob/issue-screenshots/pr-PR_NUMBER/57-badges-and-tags.png?raw=true)

1. Freeze: `RECONCILING` + `frozen` (the override, since the frozen
   client never picks up p12–17; `STABLE` + `frozen` once #54 lands).
2. Network isolation, before the session timeout: `STABLE` +
   `network isolated`.
3. The same after the session timeout: `JOINING` + `network isolated`;
   the badge changes, the tag stays.
4. Static member stopped, still listed: `STOPPING` + `unsubscribed`.
5. Stopped and dropped by the coordinator: `STOPPED` + `unsubscribed`,
   dimmed as today.
6. Failed, dropped by the coordinator: `JOINING` + `failed`, whose
   title is the failure.
7. Two faults during a rebalance, on hover: `RECONCILING` + `frozen` +
   `connection reset`, then the meta.
8. A frozen classic member: `STABLE` + `frozen`.

On hover the meta follows the tags; the overlay already widens to 85 %
on hover, which fits the name, two tags and the meta at 1440 px.

## Alternatives

- **Keep `status`, add a field for the overridden coordinator state.**
  Compatible, but the snapshot would carry three states
  (`coordinatorState`, the new one, `status`) where one is the state to
  show and the others are derivable from it and from `faults`,
  `subscribed` and `failure`. `status` would only serve the event log
  and Java callers, with a meaning the UI no longer uses.
- **Badge from `coordinatorState` in the UI, `status` untouched.** Loses
  the override (`RECONCILING` while a partition is in flight), or makes
  the UI recompute it from `raw`, a coordinator-side derivation the
  architecture keeps in the core.
- **Keep the enum values, never produce them.** Avoids `!`, but leaves
  dead values in the public API of an unreleased library.
- **Panel colour from the fault.** A cyan or orange panel next to a
  green `STABLE` badge reads as two states; the tag carries the fault.

## Architecture

`docs/ARCHITECTURE.md`:

- Own small types: `ConsumerStatus` lists `STOPPED`, `STOPPING`,
  `JOINING`, `RECONCILING`, `STABLE`.
- Snapshots, "Status priority": replaced by "The status is the
  coordinator state with the override above. The faults, the
  subscription and the failure are separate fields, which the UI shows
  as tags."
- The JSON section (`coordinatorState`, `status`): `status` is the state
  to show.
- UI, `rows.ts`: the consumer badge maps `status` only and is always
  shown; `consumerTags` gives the tags of the local facts. The
  "Hiding the badge for healthy classic members" item goes (as in #58).
- UI tests: `ConsumerRow.test.tsx` checks the badge of every status and
  the tags.

## Tests

- `StatusDeriverTest`: a frozen, isolated, reset, unsubscribed or
  failed consumer gets its coordinator state (`STABLE`, `JOINING`,
  `STOPPING`, `STOPPED`); the override still applies to a frozen
  consumer-protocol member with a partition in flight; it never applies
  to a classic one.
- `SnapshotAssemblerTest`, `EventLogTest`, `SnapshotRecordsTest`,
  `OpenApiContractTest`: updated to the new values.
- `ConsumerGroupLifecycleTest` (integration): the frozen and isolated
  scenarios assert `faults` and the `status` the coordinator drives
  (`STABLE`, then `JOINING` after the session timeout or eviction); the
  failed scenarios assert `failure` instead of `FAILED`.
- Vitest, `rows.test.ts`: `consumerBadge` for the five statuses on both
  protocols; `consumerTags` for each tag, their order with several, and
  the `title` of `failed`.
- Vitest, `ConsumerRow.test.tsx`: the panel class follows the badge, a
  frozen member renders `STABLE` and `frozen`, a failed member's tag has
  the failure as its title, a classic member shows its badge.
- `./mvnw verify` and CI green.

## Open questions

1. **`status` keeps its name with a narrower meaning, and three enum
   values go (breaking).** Recommended, as above. The other way is a new
   field and a compatible change (see Alternatives).
2. **After #54 drops the override, `status` equals `coordinatorState`.**
   Recommendation: #54, or a follow-up, removes `status` and keeps
   `coordinatorState`, the name that says what it is. Not in this PR,
   which must not depend on #54.
3. **The `unsubscribed` tag repeats `STOPPING` and `STOPPED`**, which
   already mean "not subscribed". Recommendation: keep it, as the
   accepted triage asks: the rule "one tag per local fact" stays simple,
   and the tag says why the member leaves. The other way: no
   `unsubscribed` tag at all.
4. **Tag labels and colours**: `network isolated` and `connection reset`
   as two tags (two faults, both can be active), both orange, rather
   than one `partitioned` tag. Recommended as in the table.
5. **Order with #58**: this PR always shows the badge, which overlaps
   #58's badge part. Recommendation: whichever lands second rebases; if
   #58 lands first, this PR only removes `CLASSIC_BADGES`' remaining
   uses.
