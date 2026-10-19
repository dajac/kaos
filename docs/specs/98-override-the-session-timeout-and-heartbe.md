# Override the session timeout and heartbeat interval per classic consumer

Issue: #98. Follows #94 (merged as 66c611f), which made the group's
`sessionTimeoutMs` and `heartbeatIntervalMs` reach the members of both
protocols.

## Problem

Since #94, a group's session timeout and heartbeat interval reach both
protocols: the consumer-protocol members through the group configs
`consumer.session.timeout.ms` and `consumer.heartbeat.interval.ms`, and
the classic members through the `session.timeout.ms` and
`heartbeat.interval.ms` that `ConsumerRunner` sets on their client.

In Kafka, a classic member sends its own session timeout in its
`JoinGroup` (and its client sets its own heartbeat interval), so two
classic members of one group can have different values. Kaos cannot
show this: `ConsumerOverrides` has no such component, and the config
panel offers no such override. A user cannot see, for example, one
classic member with a 10 s session timeout being fenced while the
others, at 45 s, stay in the group.

A consumer-protocol member cannot have its own values: the client
refuses `session.timeout.ms` and `heartbeat.interval.ms` with
`group.protocol=consumer`, and the coordinator takes them from the
group configs. So the override exists only for a classic consumer.

## Config rules

The configs of a group and of its consumers follow two rules:

1. Every config can be set at the group level, as the default of its
   consumers.
2. Every config that a consumer's protocol supports can be overridden
   on that consumer. A config the protocol does not support is not
   offered by the UI and is refused by the API.

"Supported by the protocol" means what Kafka lets one member set for
itself: a client config of that protocol, or a Kaos behavior of the
consumer loop. A group config of the coordinator, which no member sets,
has no consumer level. The Kafka client enforces the same split:
`ConsumerConfig` throws a `ConfigException` for `session.timeout.ms`,
`heartbeat.interval.ms` or `partition.assignment.strategy` with
`group.protocol=consumer`, and for `group.remote.assignor` with
`group.protocol=classic`.

### Audit of the existing configs

| Config | Group level | Consumer protocol | Classic protocol | Consumer level on `main` |
|---|---|---|---|---|
| `protocol` | `consumerDefaults` | `group.protocol` | `group.protocol` | override |
| `assignor` | `consumerDefaults` | `group.remote.assignor` | `partition.assignment.strategy` | override; the names are checked against the effective protocol |
| `staticMember` | `consumerDefaults` | `group.instance.id` | `group.instance.id` | override |
| `rackId` | `consumerDefaults` | `client.rack` | `client.rack` | override |
| `pollTimeoutMs` | `consumerDefaults` | Kaos loop | Kaos loop | override |
| `pollDelayMs` | `consumerDefaults` | Kaos loop | Kaos loop | override |
| `maxPollIntervalMs` | `consumerDefaults` | `max.poll.interval.ms` | `max.poll.interval.ms` | override |
| `sessionTimeoutMs` | `GroupSpec` | group config `consumer.session.timeout.ms`, no client config | `session.timeout.ms` | **none** |
| `heartbeatIntervalMs` | `GroupSpec` | group config `consumer.heartbeat.interval.ms`, no client config | `heartbeat.interval.ms` | **none** |
| `assignmentIntervalMs` | `GroupSpec` | group config `consumer.assignment.interval.ms`, no client config | not used | none, as rule 2 wants |

What the audit finds:
- Rule 1 holds for every config: the consumer settings are in
  `consumerDefaults`, the others in `GroupSpec`.
- Rule 2 holds for the seven consumer settings: both protocols support
  them, so the panel offers all seven on every consumer, and the
  assignor, whose names depend on the protocol, is already refused by
  `ConfigValidator` when it does not fit the effective protocol.
- Rule 2 is broken by `sessionTimeoutMs` and `heartbeatIntervalMs` on
  a classic consumer: the classic protocol supports them per member,
  and Kaos has no override. This spec closes that gap, and only that
  one.
- Outside the configs, the subscription (`subscribedTopics`) is per
  member in Kafka too, in both protocols, and Kaos has it only on the
  group. It is the group's shape rather than a setting, so this spec
  leaves it alone; see the open questions.

### How this spec follows them

- Rule 1: the group's `sessionTimeoutMs` and `heartbeatIntervalMs` stay
  the defaults of every consumer, as since #94.
- Rule 2: a classic consumer can override both; a consumer-protocol
  consumer cannot. The panel does not offer them on a consumer-protocol
  consumer and removes them when the consumer protocol is selected
  (UX), and the API refuses them with a `Violation` (Validation).

The API refusal is Kaos's own check, not the client's, although the
client would refuse the two configs too:
- a `Violation` is a 400 before anything starts, with the path of the
  override, which the panel maps to its field; the client's
  `ConfigException` would come only when the consumer is built, after
  the spec is accepted, as a failed consumer;
- Kaos never sets the two configs on a consumer-protocol client anyway
  (the runtime `switchProtocol` included), so the client would never
  see them to refuse them.

The rules go into `docs/ARCHITECTURE.md` (Architecture), so that the
next config follows them too.

## Proposal

### Model

`ConsumerOverrides` gains two components, `OptionalInt
sessionTimeoutMs` and `OptionalInt heartbeatIntervalMs`. They are
overrides only: `ConsumerSettings` does not get them, so neither
`consumerDefaults` nor `effective` has them (see Alternatives). What a
consumer inherits for them is the group's `GroupSpec.sessionTimeoutMs`
and `heartbeatIntervalMs`, not `consumerDefaults`.

`ConsumerOptions` gains `sessionTimeout(Duration)` and
`heartbeatInterval(Duration)`, which set the overrides, as
`GroupOptions` already names them for the group.

`ConsumerOptions` also describes `consumerDefaults`
(`GroupOptions.consumerDefaults(c -> …)`), which is a `ConsumerSettings`
and has no room for them. Setting either in a `consumerDefaults` lambda
throws `IllegalArgumentException` from `GroupSpec.of` ("set the
session timeout of the group with `GroupOptions.sessionTimeout`"),
rather than being dropped in silence.

### Runtime

`ConsumerRunner.ClassicTimeouts.of(GroupSpec)` becomes
`ClassicTimeouts.of(GroupSpec group, ConsumerSpec consumer)`: each value
is the consumer's override when set, the group's value otherwise.
`GroupRuntime` builds it for each consumer it creates, at the start
and on `addConsumer`. `clientProperties` is unchanged: the `CLASSIC`
branch sets both values, the `CONSUMER` branch sets neither.

So:
- a classic consumer with the overrides gets them on its client, over
  the group's values;
- a classic consumer without them gets the group's values, as today;
- a consumer-protocol consumer never sets them on its client.

`switchProtocol` keeps the spec as it is (the spec keeps the protocol
the consumer was created with). A classic consumer with the overrides
that is upgraded to the consumer protocol gets neither on its new
client, and gets its overrides back if it is downgraded again. A
consumer-protocol consumer downgraded to classic has no overrides (the
validation below refuses them on it) and gets the group's values.

### Validation

`ConfigValidator.checkConsumer` checks, for each override that is set:
- the range of the group field of the same key, `1000–300000` for
  `sessionTimeoutMs` and `500–60000` for `heartbeatIntervalMs`, with
  the same message as the other ranges;
- that the consumer's effective protocol is `classic`. Otherwise it
  reports `Violation("groups[i].consumers[j].overrides.sessionTimeoutMs",
  "applies only to the classic protocol; the consumer protocol takes
  the session timeout of the group")`, and the same for
  `heartbeatIntervalMs`.

The protocol check reads the effective protocol, so it applies whether
the consumer overrides its protocol or inherits it from
`consumerDefaults`. A spec whose `consumerDefaults.protocol` is changed
to `consumer` while a consumer that inherits it keeps the overrides is
refused, with the violation on the override, which is what was sent and
what must go. This mirrors Kafka, whose client refuses the two configs
with the consumer protocol, and the UI, which drops them.

Kaos does not check `heartbeatIntervalMs < sessionTimeoutMs`, for the
overrides as for the group (#94): the client and the broker validate
them (`group.min.session.timeout.ms` and `group.max.session.timeout.ms`
turn into `InvalidSessionTimeoutException`, which fails the consumer
since #94). An override can be combined with the group's other value
(a heartbeat override with the group's session timeout), as in Kafka.

## API and JSON

### Java

```java
var group = sim.addGroup("orders", g -> g
    .subscribe(orders)
    .sessionTimeout(Duration.ofSeconds(45))
    .consumerDefaults(c -> c.protocol(CLASSIC))
    .consumer(c -> c.sessionTimeout(Duration.ofSeconds(10)).heartbeatInterval(Duration.ofSeconds(2)))
    .consumers(2));
```

- `ConsumerOverrides(protocol, assignor, staticMember, rackId,
  pollTimeoutMs, pollDelayMs, maxPollIntervalMs, sessionTimeoutMs,
  heartbeatIntervalMs)`: two new components, appended; a `null` one is
  read as empty, like the others.
- `ConsumerOptions.sessionTimeout(Duration)` and
  `heartbeatInterval(Duration)`: new.
- `ConsumerSettings`, `GroupSpec`, `GroupOptions`: unchanged, apart from
  their Javadoc.
- `ConfigMetadata`: no new field; the overrides use the ranges, labels
  and defaults of the `GROUP` fields `sessionTimeoutMs` and
  `heartbeatIntervalMs`.

Adding record components changes the canonical constructor of
`ConsumerOverrides`, so a caller of the 7-argument constructor no
longer compiles. Nothing has been released (`0.1.0-SNAPSHOT`); see the
open questions.

### HTTP

The `ConsumerOverrides` schema of `kaos-v1.yaml` gains two optional
integer properties, with the ranges of the group fields:

```yaml
sessionTimeoutMs:
  type: integer
  format: int32
  minimum: 1000
  maximum: 300000
  description: Classic protocol only; overrides the group's session timeout.
heartbeatIntervalMs:
  type: integer
  format: int32
  minimum: 500
  maximum: 60000
  description: Classic protocol only; overrides the group's heartbeat interval.
```

A consumer of `POST /api/v1/simulator` (and the body of
`POST /api/v1/simulator/groups/{groupId}/consumers`):

```json
{ "overrides": { "protocol": "CLASSIC", "sessionTimeoutMs": 10000, "heartbeatIntervalMs": 2000 } }
```

`GET /api/v1/simulator` returns them in `overrides`, and only there:
`effective` is unchanged. A spec sent back round-trips. The change is
compatible: the schema has `additionalProperties: false`, so no body
that is valid today carries them, and a response without them is still
valid. `ConsumerSettingsRequest` (the `consumerDefaults` of a request)
is unchanged. A body that sets them on a consumer-protocol consumer
gets a 400 with the violation above.

`kaos-ui/src/api/schema.d.ts` is regenerated.

## UX

The config panel, for a consumer whose effective protocol is classic
(its own protocol override, or the group's protocol):
- "+ Add override" lists "Session Timeout (ms)" and "Heartbeat Interval
  (ms)", after "Max Poll Interval (ms)", with the value it inherits as
  the hint: the group's setting when it was added, the default of
  `/meta` otherwise. Picking one adds the override at that value.
- The override shows as a number input with the range of the group
  field and its inline error, as the other number overrides.
- A collapsed consumer shows the chips `sessionTimeout` and
  `heartbeat`.

For a consumer whose effective protocol is consumer, the two entries
are not in the menu. When the effective protocol becomes consumer, the
two overrides are removed, for good (switching back to classic does
not restore them):
- the consumer's protocol override is set to consumer, or removed while
  the group's protocol is consumer;
- the group's protocol is set to consumer, for every consumer that
  inherits it.

A consumer left with no override shows "Inherits all settings from
group." again.

Changing the group's session timeout or heartbeat interval leaves the
overrides as they are. A violation on
`groups[i].consumers[j].overrides.sessionTimeoutMs` maps to the field of
the override. A reload during a run restores the overrides, from
`overrides` of `GET /simulator`, like the others.

Words describe this well enough: the entries, inputs and chips are
those of the existing number overrides, so there is no prototype.

## Alternatives

- **Add the two fields to `ConsumerSettings`** too, so that `effective`
  shows them. `ConsumerSettings` is also the type of `consumerDefaults`,
  which would then hold a second session timeout for the group, beside
  `GroupSpec.sessionTimeoutMs`, with a rule needed for which one wins.
  Resolving `effective` from the `GroupSpec` instead breaks
  `EffectiveConfigResolver`'s single input (a `ConsumerSpec` outside a
  group resolves against `ConsumerSettings.defaults()`), and the value
  would be meaningless on a consumer-protocol consumer. The overrides
  and the group fields already say everything; the UI computes what a
  consumer inherits itself, as it does today.
- **Keep an override on a consumer-protocol consumer and ignore it**. It
  would carry over a runtime `switchProtocol` to classic, but a spec
  would hold values that do nothing, which Kafka refuses and the UI
  drops. A violation tells the API user at once.
- **Check `heartbeatIntervalMs < sessionTimeoutMs`** in Kaos. #94 left
  it to Kafka, and the check would have to combine an override with
  the group's other value; Kafka's own validation shows the real
  behavior.
- **New `CONSUMER` fields in `ConfigMetadata`**. They would duplicate the
  ranges of the group fields, and their default would be one the
  consumer never inherits.

## Architecture

`docs/ARCHITECTURE.md`:
- "Configuration metadata": the two config rules above, before the
  table, and a column of the table saying where each field can be set
  (group, consumer, and for which protocol), so that a new field
  states its levels.
- "Consumer groups" (the session timeout paragraph and the Kafka
  settings list): a classic consumer's own override wins over the
  group's value; the consumer-protocol client gets neither.
- "Options lambdas": `ConsumerOptions.sessionTimeout` and
  `heartbeatInterval`, and the `IllegalArgumentException` of a
  `consumerDefaults` lambda that sets them.
- "Records as the data view": `ConsumerOverrides` has the components of
  `ConsumerSettings` plus the two classic-only ones, which inherit from
  the group rather than from `consumerDefaults` and are not in
  `effective`.
- "Configuration metadata": the overrides use the ranges of the group
  fields; a note under the table.
- "Validation": the range and the classic-only checks of the two
  overrides.
- Frontend `configModel.ts`: the two overrides, the menu filtered by
  the effective protocol, their removal on a switch to the consumer
  protocol.
- "Config panel": the per-consumer overrides list gains session timeout
  and heartbeat interval, classic only.

## Tests

`kaos-core`:
- `ConsumerRunnerTest`: `clientProperties` of a classic consumer with
  both overrides, with one, and with none (the group's values); a
  consumer-protocol consumer gets neither key.
- `ClassicTimeouts.of(group, consumer)`: override over the group, per
  value.
- `ConfigValidatorTest`: overrides in and out of range; an override on
  a consumer whose own protocol is consumer, and on one that inherits
  the consumer protocol from `consumerDefaults`; a valid classic one.
- `ConsumerOptions`/`GroupSpec.of`: the options set the overrides; a
  `consumerDefaults` lambda that sets one throws.
- `ConsumerGroupLifecycleTest` (`@IntegrationTest`): a classic group at
  45 s with two consumers, one at a 6 s session timeout override, both
  under `Fault.networkIsolation()` (a freeze stops the polls, not the
  background heartbeats of a classic client); the coordinator removes
  the overriding member after about 6 s while the other is still
  listed.

`kaos-server`:
- A `POST /simulator` with the overrides, then `GET /simulator` returns
  them in `overrides`, and sending the returned spec back is accepted.
- A 400 with `groups[0].consumers[0].overrides.sessionTimeoutMs` for an
  override on a consumer-protocol consumer.
- The OpenAPI document check covers the new properties.

`kaos-ui`:
- `configModel.test.ts`: the menu entries for classic and consumer
  consumers, the inherited hint, the chips, the removal when the
  consumer's or the group's protocol becomes consumer, the request body,
  and `configFromSimulator` restoring them.
- `violations.test.ts`: the mapping of the two override paths.
- `ConfigPanel.test.tsx`: add both overrides on a classic consumer,
  switch it to consumer, see them gone.

## Plan

One PR, `feat(core): Override the session timeout and heartbeat
interval per classic consumer`: core, server, UI and docs together,
since the API without the panel leaves the panel unable to restore a
spec that has them (it would drop them on a reload). It is built on
`main`, which has #94.

## Open questions

1. **`effective` or overrides only?** Recommendation: overrides only,
   as above; `ConsumerSettings` and `consumerDefaults` stay as they
   are.
2. **An override on a consumer-protocol consumer: violation or
   ignored?** Recommendation: a violation on the override, computed
   from the effective protocol, inherited included. Kaos's own check
   rather than the client's `ConfigException`, for the reasons in
   "Config rules".
3. **A `consumerDefaults` lambda that sets them**: throw
   `IllegalArgumentException` (recommended), or copy them to the
   group's settings when the group does not set its own?
4. **Ranges and the heartbeat check**: the group's ranges, no
   heartbeat < session timeout check (recommended, as #94).
5. **Breaking change marker**: the canonical constructor of
   `ConsumerOverrides` changes. Recommendation: no `!` and no
   compatibility constructor, since nothing is released; say so if you
   want the `!` (or a 7-argument constructor kept).
6. **The config rules**: do the two rules of "Config rules" read as
   you meant them, in particular "supported" as "settable by one
   member in Kafka", which keeps `assignmentIntervalMs` group-only?
   Recommendation: yes, written into `docs/ARCHITECTURE.md` by this PR.
7. **Per-consumer subscription**: Kafka lets each member subscribe to
   its own topics, in both protocols. Recommendation: out of this spec;
   a separate issue if you want it, since it changes the group's shape
   (and the Rows view) rather than a setting.
