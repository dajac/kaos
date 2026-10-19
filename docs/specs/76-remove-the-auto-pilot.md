# Spec: Remove the auto-pilot (#76)

## Problem

The Auto-Pilot button of the sidebar turns on a per-group autopilot
that, every 10 to 30 s, asks one random consumer to leave the group if
it is subscribed or to join it if it is not. The button shows nothing
but on or off: the user does not know when the next flip comes, which
consumer it picks, or why a consumer stops on its own (#69, closed in
favour of this issue). It confuses more than it shows, and the
`leave`/`join` actions of each consumer already do the same thing on
purpose.

David asked to remove it everywhere (issue comment): the UI, the HTTP
API and the Java API. Nothing is kept as "planned"; a better automation
can come back later under its own issue and spec.

## Proposal

Remove the autopilot from every layer, in one PR titled
`feat(api)!: Remove the auto-pilot`. Once it lands, outside the
factory's own files (see Tests), `git grep -i -E "auto-?pilot"` finds
nothing.

**UI (`kaos-ui`)**
- `ConfigPanel.tsx`: the footer holds only the Start/Stop button;
  `AutopilotButton` goes.
- `simulationStore.ts`: the `setAutopilot` action goes, and the
  comment that lists the actions no longer names the autopilot.
- `api/client.ts`: the `AutopilotRequest` type and `setAutopilot` go.
- `styles/controls.css`: the `.btn-autopilot` rules go.
- `api/schema.d.ts` is regenerated with `npm run gen:api`.
- The fixtures (`model/snapshot.fixture.ts`,
  `components/rows/rerender.test.tsx`) and the client mocks
  (`state/configStore.test.ts`, `state/simulationStore.test.tsx`) drop
  the `autopilot` field and `setAutopilot`.

**HTTP API (`kaos-server`)**
- The route `PUT /api/v1/simulator/groups/{groupId}/autopilot` goes
  (`ApiRoutes`, `SimulationService.setAutopilot`).
- `dto/AutopilotRequest` goes.
- `openapi/kaos-v1.yaml`: the path, the `AutopilotRequest` and
  `AutopilotSnapshot` schemas, and the `autopilot` property of
  `GroupSnapshot` (and its entry in `required`) go.
- The `SimulationService` Javadoc stops listing the autopilot among
  the operations that only flip flags.

**Java API (`kaos-core`)**
- `Autopilot` and `AutopilotSnapshot` are deleted.
- `ConsumerGroup.autopilot()` goes; the Javadoc of the type and of
  `remove()` stops mentioning it.
- `GroupSnapshot` loses its `autopilot` component (and the
  `requireNonNull` and `@param` that go with it).

**Internals (`kaos-core`, `internal`)**
- `engine/AutopilotImpl` is deleted, and with it the
  `kaos-<groupId>-autopilot` thread.
- `engine/GroupRuntime` no longer builds, opens, registers or closes an
  autopilot; `autopilot()` goes. The ordering it enforces today ("the
  autopilot is off before any consumer stops") has nothing left to
  order. `runners()` stays: `SimulatorEngine` uses it.
- `state/GroupLocalState` loses its `autopilot` component;
  `state/SnapshotAssembler` stops copying it.
- `state/EventLog` no longer logs `autopilot on, every a..b ms` /
  `autopilot off`; the `[group] autopilot asks <consumer> to
  leave|join` lines go with `AutopilotImpl`. No other `kaos.events`
  line changes.

`ConsumerHandle.leave()`/`join()`, the per-consumer actions and the
Rows view are untouched. The Rows view has no handling of its own for
a flip of the autopilot: a flip shows as a manual Stop/Start does, so
it loses nothing.

## API and JSON

All three changes are breaking, hence the `!`. Kaos is
`0.1.0-SNAPSHOT`, has never been published to a Maven repository, and
its UI ships in the same jar as the server, so the UI and the server
cannot be out of step.

Java, before and after:

```java
// before
group.autopilot().start(Duration.ofSeconds(10), Duration.ofSeconds(30));
group.snapshot().autopilot().enabled();
// after: no such methods; Autopilot and AutopilotSnapshot do not exist
```

HTTP:

```
PUT /api/v1/simulator/groups/orders/autopilot  {"enabled": true}
before: 204
after:  404 problem detail, like any unknown route
```

JSON of a group snapshot (the SSE `snapshot` event and the
`GET` of the snapshot), before:

```json
{
  "id": "orders",
  "type": "CONSUMER",
  "subscribedTopics": ["orders"],
  "autopilot": {"enabled": false, "minIntervalMs": 10000, "maxIntervalMs": 30000},
  "lag": 0,
  "consumers": []
}
```

after: the same object without `"autopilot"`. Every other field is
unchanged. A client that ignores unknown fields keeps working against
an old server; one that requires `autopilot` (as the generated
`schema.d.ts` does today) must be updated, which this PR does for the
UI.

## UX

The sidebar footer, whose layout is a column with a 6 px gap, shows
only the Start button while idle (Starting… while starting, Stop while
running, Stopping… while stopping), full width as today. No other
part of the screen changes. No prototype screenshot: the footer is the
current one minus its second button.

## Alternatives

- **Remove the button only, keep core and HTTP API.** Cheap to bring
  back, but leaves a public API and a route that nothing in the product
  uses, with tests and docs to maintain. David chose the full removal.
- **A stack of PRs (core, then server and OpenAPI, then UI).** The core
  PR cannot land alone: removing `ConsumerGroup.autopilot()` breaks the
  server's compilation, and changing the OpenAPI document fails the
  build until `schema.d.ts` is regenerated. Only "UI first, then the
  rest" splits cleanly, and the UI part is about 40 lines. One PR,
  almost only deletions, is simpler to review.
- **Mark the autopilot "planned" in `docs/ARCHITECTURE.md`.** It would
  describe something that is neither built nor designed. The issue says
  "for now"; a future automation gets its own spec.
- **Keep `autopilot` in the JSON, always off, for compatibility.** No
  client outside this repository exists to protect.

## Architecture

`docs/ARCHITECTURE.md` drops every mention of the autopilot:
- the `kaos-core` status row, the package listings and the class tree
  (`Autopilot`, `AutopilotSnapshot`, `AutopilotImpl`);
- the list of `ConsumerGroup` methods, the component tree ("group id +
  group settings + subscribed topics"), and the example that calls
  `autopilot().start(..)`;
- the removal of a group, the group start and stop sequences, and the
  "per group" list;
- the threads section (`kaos-<groupId>-autopilot`) and the
  concurrency note on lists read by the autopilot;
- the autopilot rules (`min ≤ max`, a removed consumer skipped), the
  `kaos.events` lines it logs, and the `autopilot` of the group's local
  state;
- the HTTP route table, the autopilot body rules, the OpenAPI
  coverage list and the `autopilot{...}` JSON shape;
- the frontend: `setAutopilot` in the client, the store's action list,
  the Auto-Pilot button of the sidebar, and the config panel status;
- Features: the "Auto-Pilot" subsection and its mentions in the parity
  check and the Rows view status. The removal is listed with the
  intended differences from the prototype;
- Tests: the autopilot in `ConsumerGroupLifecycleTest`,
  `DynamicComponentsTest` and `HttpRunTest`.

`README.md` drops "or let the Auto-Pilot do it at random", "or press
**Auto-Pilot**", and "each group has an `autopilot()`".

## Tests

Deleted, with what they cover:
- `AutopilotImplTest`;
- `ConsumerGroupLifecycleTest.theAutopilotFlipsSubscriptions`;
- `GroupRuntimeTest.theAutopilotIsOffBeforeAnyConsumerStopsAddedLaterIncluded`
  and the `with the autopilot …` part of its close events;
- the autopilot steps of `DynamicComponentsTest` (removing a group),
  `SimulatorTest`, `HttpRunTest`, `EventLogTest` (the on/off lines) and
  `simulationStore.test.tsx`;
- the `autopilot` row of the 409 cases of `ApiRoutesTest` and the
  `AutopilotRequest` cases of `OpenApiContractTest`.

Updated: `PublicApiTest` no longer lists the two types;
`SnapshotRecordsTest`, `SnapshotAssemblerTest`, `EventLogTest` and
`OpenApiContractTest` build snapshots without `autopilot`.

Added:
- Vitest, `ConfigPanel.test.tsx`: in every phase (idle, starting,
  running, stopping) the footer `#config-footer` holds exactly one
  button, `#btn-start-sim`, and no element has the text "Auto-Pilot".
- `ApiRoutesTest`: `PUT /api/v1/simulator/groups/app/autopilot` is a
  404 problem detail, as an unknown route.
- `OpenApiContractTest` already checks the snapshot records against
  the document, so it fails if `GroupSnapshot` and the schema disagree.

Checks: `git grep -i -E "auto-?pilot" -- . ':!docs/factory'
':!scripts/factory' ':!docs/specs'` is empty (the factory's own
README and test fixtures name issue #76 by its title); `./mvnw verify`
and CI are green.

## Open questions

1. **One PR or a stack?** Recommendation: one PR,
   `feat(api)!: Remove the auto-pilot`; it is almost only deletions and
   a stack cannot keep the build green without the UI going first.
2. **The `kaos.events` lines of the autopilot go too?** Recommendation:
   yes, both the on/off lines of `EventLog` and the "asks … to
   leave/join" lines of `AutopilotImpl`; nothing else produces them.
3. **What does the old route answer?** Recommendation: the 404 of any
   unknown route, with no dedicated 410 or redirect; there is no
   external client to warn.
4. **Keep a note in `docs/ARCHITECTURE.md`?** Recommendation: no
   "planned" entry; one line among the intended differences from the
   prototype, so that the parity claim stays true.
