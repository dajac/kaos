# Spec: a group config refused by the broker fails the start (#61)

## Problem

A group's session timeout, heartbeat interval and assignment interval
are set on the broker as dynamic group configs when the group starts.
When the broker refuses one, `GroupConfigManager.apply` turns the
refusal into a warning and the group runs without that config. The
warning reaches `ConsumerGroup.warnings()` and the `warnings` field of
the HTTP status, but the UI never reads `warnings`: the user sees a
simulation that runs, and the locked config panel still shows the value
that was refused.

This is easy to hit. With the default broker settings,
`consumer.session.timeout.ms` must lie within 45000–60000 ms and
`consumer.heartbeat.interval.ms` within 5000–15000 ms, while the ranges
of `/meta` are 1000–300000 ms and 500–60000 ms. A session timeout of
10000 ms is accepted by the panel and silently dropped by the broker.
The simulation then shows behaviour (a 45 s session timeout) that does
not match what the user configured, which defeats a simulator.

David's comment on the issue settles the direction: the simulation must
not start when a config is invalid, and the error must be shown.

## Proposal

A group config that the broker refuses fails the start of the group.

- `GroupConfigManager.apply` no longer catches `RefusedConfigException`
  into a warning. It throws a `SimulationException` whose message names
  the key, the value, the group and the broker's message, with the
  `RefusedConfigException` as its cause:

  ```
  The broker refused the group config consumer.session.timeout.ms=10000
  of group orders: consumer.session.timeout.ms must be greater than or
  equal to group.consumer.min.session.timeout.ms.
  ```

  The keys set before the refused one are reverted, by the `catch` that
  already exists in `apply` for any other failure. The keys after it
  are not tried. The order stays session timeout, heartbeat interval,
  assignment interval.
- The start of the group fails with that exception, so:
  - `Simulator.start()` fails, and its rollback undoes every component
    already started, as for any failed component start. The message
    goes through `wrapFailure` unchanged, since it is already a
    `SimulationException`.
  - `Simulator.addGroup(...)` on a running simulator fails, and the
    group is not added (`startComponent` already leaves nothing
    behind).
- Over HTTP, the operation fails on the lifecycle thread as any other:
  `lastError` holds the message. `POST /simulator` still answers 202;
  the status goes `STARTING` → `IDLE` with `lastError` set. `POST
  /simulator/groups` answers 202; the status stays `RUNNING` with
  `lastError` set.
- The UI already shows both:
  - a failed start: the config panel's banner "The simulation failed to
    start." with `lastError` as its detail (`configStore.applyStatus`);
    the panel is unlocked again with the user's values, so the user can
    fix the field and start again;
  - a failed add: the error of the Rows view
    (`simulationStore`'s `lastError` while `RUNNING`).
  No new UI component is needed; the change in `kaos-ui` is the removal
  of `warnings` (see below).
- A key the broker does not know is refused the same way: an explicit
  `consumer.assignment.interval.ms` on a broker before 4.3 fails the
  start. The default is never sent, so a group that leaves the
  assignment interval alone still starts on such a broker.
- `warnings` has no source left, so it is removed: `ConsumerGroup.warnings()`
  in the Java API, `warnings` in `Status` and `Simulator` of the HTTP
  API, and `Applied.warnings` in `GroupConfigManager`.

Not in scope:
- A classic member whose session timeout is under the broker's
  `group.min.session.timeout.ms` keeps failing on its own (the consumer
  goes `FAILED` with `InvalidSessionTimeoutException`), as today: that
  is the client's check, not a group config.
- The ranges of `/meta` are unchanged (see the open questions).

## API and JSON

Breaking, so the build PR's title carries a `!`:
`fix(core)!: Fail the start of a group whose config the broker refuses`.
Kaos is not released yet (0.1.0-SNAPSHOT), so nobody depends on the
removed members outside this repository.

Java API (`kaos-core`):
- `ConsumerGroup.warnings()` is removed.
- `Simulator.start()` and `Simulator.addGroup(...)` document that they
  throw `SimulationException` when the broker refuses a group config.
  Their signatures do not change.

HTTP API (`kaos-v1.yaml`):
- `Status`: `{phase, lastError, warnings}` becomes `{phase, lastError}`.
- `Simulator`: `{phase, spec, lastError, warnings}` becomes
  `{phase, spec, lastError}`.

Before:

```json
{"phase": "RUNNING", "lastError": "",
 "warnings": ["The broker refused the group config consumer.session.timeout.ms=10000 of group orders, which runs without it: ..."]}
```

After, for the same start:

```json
{"phase": "IDLE",
 "lastError": "The broker refused the group config consumer.session.timeout.ms=10000 of group orders: consumer.session.timeout.ms must be greater than or equal to group.consumer.min.session.timeout.ms."}
```

A client that ignores unknown fields keeps working; one that requires
`warnings` breaks. `kaos-ui/src/api/schema.d.ts` is regenerated
(`npm run gen:api`).

## UX

No new screen state. The two states the user sees already exist:

1. Start with a refused value: the panel shows "Starting…", then goes
   back to editable with the red banner "The simulation failed to
   start." and the message above as its detail. The field keeps the
   value the user typed, so it can be fixed.
2. Add a group with a refused value while running: the Rows view shows
   its error line with the message; the group does not appear.

The issue also asked to mark the refused field of the locked panel.
With the start failing, the panel is never locked on a refused value,
so there is nothing to mark there. Marking the field of the unlocked
panel after a failed start is an open question below.

## Alternatives

- **Keep the warning, show it in the UI** (the issue's original scope):
  a banner in the Rows view and a mark on the locked field. Rejected by
  David's comment: the simulation would run with settings other than
  those shown, which is the very problem.
- **Reject the spec before the start (400)** by reading
  `group.consumer.min/max.session.timeout.ms` and
  `group.consumer.min/max.heartbeat.interval.ms` from the broker in
  `POST /simulator`. It would give a synchronous 400 with a field path,
  so the UI could mark the field. But `Simulator.validate` is offline
  today and would need a broker call; the bounds are only what Kafka
  checks today and Kaos would duplicate them ("Kaos adds no check of
  its own: Kafka's validation applies"); a dynamic broker config change
  between the check and the start would still need the failure path
  proposed here. Better as a later improvement on top of this one, if
  wanted.
- **Keep `warnings` for future uses**, always empty. Rejected: an API
  member with no source is dead weight, and it is cheaper to remove it
  before the first release than after. A future need can add a
  structured field then.
- **Fail only out-of-range values, keep an unknown key
  (`consumer.assignment.interval.ms` on an old broker) as a warning.**
  Rejected: it keeps `warnings` alive for one case, and the user did
  set that value explicitly; running without it is the same silent
  mismatch.

## Architecture

`docs/ARCHITECTURE.md`:
- "Consumer groups": the starting bullet drops the sentence on
  `warnings()` and says that a group config the broker refuses fails
  the start of the group, with its key, value and the broker's message,
  after the keys already set are reverted. The best-effort bullet
  ("Removing a static member … and setting a group config the broker
  refuses are best-effort") drops the group config.
- The list of the `ConsumerGroup` methods (around `autopilot()`,
  `warnings()`, `snapshot()`) drops `warnings()`.
- HTTP API: the `GET /simulator` line, "Status" (`{phase, lastError}`)
  and the bullet "`warnings` lists the `warnings()` of every group" are
  updated or removed.
- Tests: "a config the broker refuses is a warning" becomes "a config
  the broker refuses fails the start and is reverted".

The Javadoc of `GroupConfigManager` and `ConsumerGroup` follows.

## Tests

Core:
- `GroupConfigManagerTest` (unit, with a fake `Store`):
  - a refused first key throws `SimulationException` naming key, value,
    group and the broker's message, and writes nothing else;
  - a refused second key throws and reverts the first one to its
    previous value (and deletes it when it had none);
  - a refusal whose revert also fails keeps the revert failure as
    suppressed;
  - the success path is unchanged (no `warnings` left in `Applied`).
- `ConsumerGroupLifecycleTest` (`@IntegrationTest`):
  `aGroupConfigTheBrokerRefusesIsAWarning` becomes
  `aGroupConfigTheBrokerRefusesFailsTheStart`: with a session timeout
  over `group.consumer.max.session.timeout.ms` and a valid heartbeat
  interval, `simulator.start()` throws with the message above, the
  simulator is `IDLE`, no dynamic group config is left on the group and
  the group does not exist on the broker. `aClassicSessionTimeout…`
  drops its `warnings()` assertion.
- `DynamicComponentsTest` (`@IntegrationTest`): adding a group with a
  refused config to a running simulator throws, the group is not
  listed, and the other groups keep running.

Server:
- `SimulationServiceTest` / `HttpRunTest`: a start with a refused
  config ends `IDLE` with `lastError` naming the key; an add ends
  `RUNNING` with `lastError`; the status JSON has no `warnings`.
- `ApiRoutesTest` and the OpenAPI check follow the schema change.

UI (Vitest):
- `configStore.test.ts`: a status `STARTING` → `IDLE` with this
  `lastError` sets the banner "The simulation failed to start." with
  the message as detail (it may already exist with another message; it
  then only loses `warnings`).
- The fixtures of `configStore`, `simulationStore`, `configModel`,
  `events` and `client` tests drop `warnings`.

`./mvnw verify` is green.

## Open questions

1. **Remove `warnings` (breaking) or keep it empty?** Recommendation:
   remove it from the Java API and the HTTP API now, before the first
   release.
2. **An explicit `consumer.assignment.interval.ms` on a broker before
   4.3: fail too?** Recommendation: yes, the same rule for every
   refused key; the default is never sent, so only a user who set it
   is affected, and the message tells them why.
3. **Name the broker's bound values in the message?** The broker's
   message names the bound config (`group.consumer.min.session.timeout.ms`)
   but not its value. Kaos could describe the broker configs on a
   refusal and append "(the broker allows 45000–60000)".
   Recommendation: not in this PR; the broker's message is enough to act
   on, and it keeps Kaos free of a check of its own.
4. **Mark the refused field in the panel after a failed start?** It
   needs a structured error (field path) in the status instead of a
   string. Recommendation: no, the message names the group and the key;
   revisit with the pre-start check of the alternatives if wanted.
5. **Narrow the ranges of `/meta` to the connected broker's bounds?**
   Recommendation: no, keep `/meta` static and broker-independent; a
   separate issue can propose it, since it touches the meta contract
   and the UI's range checks.

One PR is enough: the core change, the API removal, the server and UI
follow-ups, and the docs.
