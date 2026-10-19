# Remove the client-reported generation from the API (#101)

## Problem

#100 stopped showing the classic generation in the Rows view: the
consumers report it, not the coordinator (`DescribeGroups` does not
return it), so it lags behind the coordinator after a rebalance and
contradicts the rule that the rows show the coordinator's view. The
value is still part of both APIs:

- `GroupSnapshot.generation`, the highest generation the consumers of
  the group report (`SnapshotAssembler`);
- `ConsumerSnapshot.generation`, the generation the consumer reports
  (`ConsumerRunner.reportGeneration`, from
  `groupMetadata().generationId()`);
- the `generation` property of the group and of the consumer in
  `kaos-v1.yaml`, and so in `kaos-ui/src/api/schema.d.ts`.

A user of the Java API or of the HTTP API reads a number that looks
like the coordinator's generation and is not. Nothing in the UI reads
it any more, so it only misleads.

## Proposal

Remove the client-reported generation everywhere, in one PR branched
from `main` (#100 is merged, so the triage's ordering question is
settled):

1. `GroupSnapshot` loses its `generation` component and
   `ConsumerSnapshot` loses its `generation` component. Their Javadoc
   no longer mentions a generation (`GroupSnapshot`: "An epoch that is
   not known is -1"; `ConsumerSnapshot`: the classic-member paragraph
   says the member epoch is -1, without the generation).
2. Internally, nothing keeps the value: `ConsumerLocalState` loses its
   `generation` component, `ConsumerRunner` loses the `generation`
   field, `reportGeneration` and the two `generation.set(-1)` resets,
   and `SnapshotAssembler` no longer computes the group maximum.
3. `EventLog` drops the two `generation: a -> b` lines, the group one
   and the per-consumer one. The rest of the change log is unchanged.
4. `kaos-v1.yaml` drops the `generation` property and its `required`
   entry from the group and from the consumer schemas;
   `schema.d.ts` is regenerated with `npm run gen:api`.
5. The tests and the UI fixtures stop using it (see Tests).

The `ConsumerRow` and `GroupHeader` comments "DescribeGroups reports
no generation for a classic ..." stay: they explain why the classic
row shows no generation, which is still true.

The PR title carries the breaking marker, e.g.
`refactor(api)!: Remove the client-reported generation`.

## API and JSON

Both changes are breaking, in the same way: a field disappears.

Java, before and after:

```java
// before
record GroupSnapshot(String id, GroupType type, GroupState state, int epoch,
    int targetAssignmentEpoch, int generation, List<String> subscribedTopics, ...)
record ConsumerSnapshot(String id, String clientId, Protocol protocol, boolean subscribed,
    List<Fault> faults, String memberId, String instanceId, int memberEpoch,
    int generation, ConsumerStatus coordinatorState, ...)

// after: the same records without `generation`
record GroupSnapshot(String id, GroupType type, GroupState state, int epoch,
    int targetAssignmentEpoch, List<String> subscribedTopics, ...)
record ConsumerSnapshot(String id, String clientId, Protocol protocol, boolean subscribed,
    List<Fault> faults, String memberId, String instanceId, int memberEpoch,
    ConsumerStatus coordinatorState, ...)
```

A caller of `generation()` or of the canonical constructor no longer
compiles.

JSON of a group in the snapshot (`GET /api/v1/snapshot` and the SSE
`snapshot` event), before and after:

```json
{ "id": "g", "type": "CLASSIC", "state": "STABLE", "epoch": -1,
  "targetAssignmentEpoch": -1, "generation": 3, "subscribedTopics": ["t"], ... }
{ "id": "g", "type": "CLASSIC", "state": "STABLE", "epoch": -1,
  "targetAssignmentEpoch": -1, "subscribedTopics": ["t"], ... }
```

and of a consumer:

```json
{ "id": "consumer-0", ..., "memberEpoch": -1, "generation": 3, "coordinatorState": "STABLE", ... }
{ "id": "consumer-0", ..., "memberEpoch": -1, "coordinatorState": "STABLE", ... }
```

A JSON client that ignores unknown properties and does not require
`generation` keeps working; one that reads it breaks. The embedded UI
is updated in the same PR.

## UX

No visible change: since #100 the Rows view shows no generation.
The `kaos.events` log no longer prints `[g] generation: -1 -> 3` or
`[g] consumer-0 generation: -1 -> 3`.

## Alternatives

- **Keep the generation lines in the event log as a client-local
  annotation**, fed from `ConsumerLocalState`. `EventLog` diffs the
  published snapshots only ("same source as the listeners" in
  `docs/ARCHITECTURE.md`), so it would need a second, internal input
  and the guarantee that the log matches what listeners see would go.
  The lines also restate what the coordinator lines already say (the
  group state going through `PREPARING_REBALANCE` and back to `STABLE`,
  the partitions assigned and revoked). Not worth it.
- **Deprecate first, remove later.** The project is pre-1.0 and its only
  known API client is the embedded UI; a deprecation cycle buys nothing.
- **Replace it with the coordinator's generation.** `DescribeGroups`
  does not return it, so there is no source. Out of scope.

## Architecture

`docs/ARCHITECTURE.md`:

- "Change log": drop "and generation" from the group items and "the
  generation" from the per-consumer items.
- `ConsumerLocalState`: drop `generation` from its component list.
- The group description: drop "Group generation: the highest
  generation its consumers report."
- "Snapshot shape": the unknown-number convention becomes "(epoch,
  lag)"; drop `generation` from the group fields and the consumer
  fields.
- The `GroupHeader` paragraph and "Differences from the prototype"
  keep saying that a classic group shows no generation; the second
  one's parenthesis about the prototype stays as it is.

## Tests

- `SnapshotAssemblerTest`: drop the generation assertions and the
  `generation` parameter of its local-state helper.
- `EventLogTest`: drop the two expected `generation` lines; add an
  assertion that a classic rebalance diff (state change and new
  assignment) produces no line containing `generation`.
- `ConsumerRunnerTest`: drop the three `localState().generation()`
  assertions; the scenarios they belong to keep their other checks.
- `ConsumerGroupLifecycleTest.switchesTheProtocolInBothDirections`
  waits on `generation() > 0` before the switch, to be sure that a
  classic client finished its join and not only the coordinator. The
  replacement wait is stronger and uses the coordinator view plus the
  public raw lists: `allAssigned(4, "consumer-0", "consumer-1")` and,
  for each consumer, `raw().localAssignment()` equals
  `raw().assignment()`. The local assignment is set in
  `onPartitionsAssigned`, which the client calls after it has stored
  the generation from its SyncGroup, so the new condition implies the
  old one for every consumer, not only for one. The build runs this
  test at least ten times in a row against a broker, without reruns of
  failed tests, and reports the result in the PR.
- `OpenApiContractTest` keeps validating the fixtures against the
  spec. `GroupSnapshot` and `ConsumerSnapshot` are
  `additionalProperties: false` in `kaos-v1.yaml`, so a `generation`
  left in a serialized record fails it.
- UI: `snapshot.fixture.ts`, `share.test.ts` and `rerender.test.tsx`
  drop `generation`; `rows.test.tsx` drops the `generation: 3` of the
  classic group fixture and keeps its "no generation" assertions.
  `npm run check:api` and the type check prove nothing else reads it.
- `./mvnw verify` and CI green.

## Open questions

1. **Event log.** Drop the generation lines (recommended), or keep them
   as a client-local annotation fed from `ConsumerLocalState`? I
   recommend dropping them, with the value, for the reasons in
   Alternatives.
2. **The lifecycle test's wait.** Replace `generation() > 0` with "the
   local assignment of every consumer equals its coordinator
   assignment" (recommended), or with `allAssigned` alone? The first
   keeps the guarantee that the clients finished their join before the
   switch; `allAssigned` alone only says the coordinator is done.
3. **Title type.** `refactor(api)!` (recommended: no behavior a user
   relies on changes, a field goes away) or `feat(api)!`?
