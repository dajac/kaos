# Kaos architecture

This is a living document. It always describes the code as merged. Any
PR that changes or refines the design updates it in the same PR, and
reviewers check that it matches the code.

**Status.** `main` contains the Maven build and the module skeleton.
Everything else described here is **planned**. When a part lands, its
"planned" marker is removed from the status table below and from the
section that describes it.

| Part | Status |
|---|---|
| Maven multi-module build, `run.sh`, shaded jar | built |
| Integration-test harness (failsafe, Testcontainers, `KafkaClusterExtension`) | built |
| CI (GitHub Actions, `./mvnw verify` on every PR and on push to `main`) | built |
| `kaos-core` | the spec, options and validation model is built (specs, options lambdas, `ConfigMetadata`, `Fault`, `Protocol`, `Simulator.validate`, `internal/config`); the rest is planned |
| `kaos-server` | skeleton (`Main`, `Server`, `GET /api/v1/meta` returning the version); the rest is planned |
| `kaos-ui` | planned |

## Context

Kaos is an interactive Kafka consumer group (KIP-848) simulator. It runs
real consumers and producers against a Kafka cluster, lets the user
disturb them (stop, freeze, isolate from the network, switch protocol),
and shows live how the group rebalances and how lag evolves.

The system has three parts:

1. `kaos-core`, an embeddable library with a **fluent Java API**. A
   `Simulator` harness holds independent topics, producers and consumer
   groups, and components can be added or removed at runtime.
2. `kaos-server`, a thin server exposing a **versioned, frontend-agnostic
   HTTP API (REST + SSE, OpenAPI)**, so that other frontends such as a
   TUI can be built on it.
3. `kaos-ui`, a **React + TypeScript** UI embedded in the jar.

One `java -jar` ships everything.

### Relation to the prototype

A working prototype lives on the frozen `initial-setup` branch. It never
lands on `main`. It is a read-only reference for porting logic and for
side-by-side parity checks; read it with `git show initial-setup:<path>`
or from a worktree of that branch.

The prototype has the feature set and the UI (the Rows view) that Kaos
keeps, but not an architecture to build on:

- Its backend is 9 classes in one package, built on the JDK
  `HttpServer`. The proxy fault state is a JVM-global static. A failed
  start leaks the proxy and threads. Lists are shared unsafely across
  threads. There are no tests and no README.
- Its UI is a single 3,621-line `simulator.html`. It polls every 200 ms.
  It owns domain logic that any frontend would need, such as partition
  classification and the effective member status.

The architecture below keeps the same features and the same screen, and
addresses each of these points. The prototype's legacy Bento ("Old")
view is not carried over.

### Out of scope for now

- Quality gates in CI (coverage, static analysis, required checks). The
  basic CI build is in place, see [Verification](#verification).
- Docker and docker-compose.
- Release automation.
- CONTRIBUTING, community files and templates. Open source polish is
  limited to a good README.
- A Playwright end-to-end suite.
- A TUI frontend.
- A multi-group UI, producer controls and the connection-reset fault in
  the UI. The Java and HTTP APIs already support them.

## Tech stack

Status: the Java build rows are in place (Java 21, Kafka, Kroxylicious,
Javalin, Jackson, the command line, logging, packaging, and JUnit 6 with
AssertJ, Testcontainers and failsafe). Mockito, Awaitility, the frontend
and the API contract are planned.

The versions below were checked on 2026-09-19. When a tool rejects a
brand-new major (JUnit 6, TypeScript 7, frontend-maven-plugin 2.x), the
build steps back one major.

| Area | Choice |
|---|---|
| Language / build | Java 21 (`maven.compiler.release=21`), Maven wrapper, multi-module |
| Kafka | `kafka-clients` 4.3.1, pinned and excluded from Kroxylicious |
| Proxy | `kroxylicious-runtime` 0.24.0 (requires Java 21) |
| HTTP | Javalin 7.2.x (Jetty 12), with REST, SSE and static files |
| JSON | Jackson via `jackson-bom` 2.22.x |
| Command line | picocli 4.7.x in server, through reflection (no annotation processor) |
| Logging | `slf4j-api` only in core; logback-classic 1.5.x in server |
| Packaging | `maven-shade-plugin` 3.6.x |
| Java tests | JUnit (junit-bom), AssertJ 3.27.x, Mockito 5.x, Awaitility 4.3, Testcontainers 2.0.x `testcontainers-kafka` with `apache/kafka:4.3.1`, failsafe for the tests tagged `integration` |
| Frontend | Node 24 LTS (pinned by frontend-maven-plugin + `.nvmrc`), Vite 8, React 19, TypeScript, Zustand 5, `@fontsource-variable/{jetbrains-mono,dm-sans}` |
| Frontend tests | Vitest, React Testing Library. Playwright is deferred. |
| API contract | Hand-written OpenAPI 3.1 YAML, with `openapi-typescript` generating the TS types |

Notes:

- **Jackson BOM.** Importing `jackson-bom` guarantees a single Jackson
  version across the modules. (The prototype mixes Jackson 2.17 and
  2.21.)
- **Logging.** The group change log goes to a logger named `kaos.events`.
- **Shade configuration:**
  - `ServicesResourceTransformer` is mandatory, because Kroxylicious
    discovers `FilterFactory` through the ServiceLoader.
  - Manifest with `Main-Class` and `Multi-Release: true`.
  - License and notice transformers. The per-dependency Apache license
    copies are dropped, the project `LICENSE` is included once as
    `META-INF/LICENSE`, and the `NOTICE` files are merged.
  - Signature files and `module-info.class` are excluded.
  - The shaded jar replaces the module jar,
    `kaos-server/target/kaos-server-<version>.jar`. The unshaded one is
    kept as `original-kaos-server-<version>.jar`.
- **Version.** Maven filters `version.properties` in `kaos-server`, and
  `/api/v1/meta` reports the version read from it.
- **Contract enforcement.** The committed `schema.d.ts` is checked with a
  diff, and a Java contract test validates the fixtures against the spec.

## Project structure

Status: the parent POM, the Maven wrapper, `run.sh`, and `Main`,
`Server`, `CliOptions`, `Version` and `dto/MetaResponse` of
`kaos-server` exist, next to `LICENSE`, `.gitignore`, `CLAUDE.md`, the
CI workflow and this document. In `kaos-core`, the spec, options and
validation model exists: the five spec records with `ConsumerSettings`
and `ConsumerOverrides`, the three options classes, `ConfigMetadata`,
`Fault`, `Protocol`, `Violation`, `SimulationException`,
`InvalidConfigException`, the whole `internal/config` package, and a
`Simulator` that holds only the static `validate`. Everything else in
the tree below is planned, including the `kaos-ui` module and the
`-DskipUi` switch. The test harness of `kaos-core` also exists; see
[Verification](#verification).

```
kaos/
  pom.xml                      parent: BOMs, pluginManagement, -DskipUi, -DskipITs
  README.md  LICENSE  .gitignore  run.sh  CLAUDE.md
  docs/ARCHITECTURE.md         living design doc, kept up to date in every PR
  .github/workflows/ci.yml     CI: ./mvnw verify on every PR and on push to main
  kaos-core/                   library: kafka-clients, kroxylicious-runtime, slf4j-api
    src/main/java/io/github/dajac/kaos/            PUBLIC API
      Simulator, Topic, ProducerHandle, ConsumerGroup, ConsumerHandle, Autopilot, Fault
      ProducerOptions, GroupOptions, ConsumerOptions, Registration, SnapshotListener
      SimulatorSpec, TopicSpec, ProducerSpec, GroupSpec, ConsumerSpec (records), ConfigMetadata
      ConsumerSettings, ConsumerOverrides (records), Violation
      SimulatorSnapshot, ProducerSnapshot, GroupSnapshot, ConsumerSnapshot, PartitionView, PartitionRef
      enums: Protocol, Phase, GroupState, GroupType, PartitionState, ConsumerStatus, ProducerState
      SimulationException, InvalidConfigException (List<Violation>), SimulationStateException
    src/main/java/io/github/dajac/kaos/internal/   NOT API
      config/   ConfigValidator, EffectiveConfigResolver, Assignors
      engine/   SimulatorEngine, GroupRuntime, ConsumerRunner, ProducerRunner, AutopilotImpl,
                Resources, Threads
      kafka/    TopicManager, GroupConfigManager, GroupPoller (-> AdminView), ClientFactory
      proxy/    NetworkProxy, ProxyLauncher, NetworkFaults, NetworkFaultFilterFactory,
                BoundPluginRegistry (+ META-INF/services FilterFactory entry)
      state/    SnapshotAssembler, PartitionClassifier, StatusDeriver, SnapshotPublisher
    src/test/java/io/github/dajac/kaos/test/       IT harness: IntegrationTest, KafkaClusterExtension, KafkaCluster
  kaos-server/                 Javalin adapter + shaded runnable jar
    src/main/java/io/github/dajac/kaos/server/
      Main (CLI), CliOptions, Version, Server, ApiRoutes, SimulationService, SseHub, ProblemHandler, StaticUi, dto/*
    src/main/resources/openapi/kaos-v1.yaml
  kaos-ui/                     packaging jar; vite build -> target/classes/ui
    package.json vite.config.ts tsconfig.json index.html pom.xml
    src/ main.tsx App.tsx
      api/        schema.d.ts (generated), client.ts, events.ts (EventSource + backoff)
      state/      simulationStore.ts, configStore.ts, uiSettings.ts ('kaos.uiSettings')
      model/      configModel.ts, assignors.ts, lag.ts, axis.ts, tooltip.ts   (pure, tested)
      components/ layout/ config/ sim/ settings/
      styles/     tokens.css base.css config.css rows.css controls.css
```

**Public API vs `internal`.** The package `io.github.dajac.kaos` is the
public API of `kaos-core`. Everything under
`io.github.dajac.kaos.internal` is not API: public types never expose an
`internal` type, and code outside `kaos-core`, the server included,
never imports one.

## Architecture

### Java API (`kaos-core`)

Status: the spec, options and validation model is built. This covers
the paragraphs "Options lambdas", "Records as the data view" and
"Validation" below, the `Fault` type and the `Protocol` enum. The
harness is planned: `Simulator` has only the static `validate`, the
components (`Topic`, `ProducerHandle`, `ConsumerGroup`,
`ConsumerHandle`) do not exist, and neither do `describe()`, the `add…`
overloads, the snapshot types and the other enums.

The model is a harness, `Simulator`, with independent components under
it. The style is Testcontainers-like: construct, configure, `start()`,
and `close()` tears everything down.

```
Simulator            harness: cluster connection, proxy, threads, snapshot, lifecycle
 ├─ Topic        0..n   created and owned by the simulator; shared by producers and groups
 ├─ ProducerHandle 0..n load generators writing to topics
 └─ ConsumerGroup  0..n group id + group settings + subscribed topics + autopilot
      └─ ConsumerHandle 0..n   options, faults, join/leave, switchProtocol
```

```java
try (Simulator simulator = new Simulator("localhost:9092")
        .withClientProperty("security.protocol", "SASL_SSL")   // optional
        .withProxyPort(19092)                                  // optional
        .withListener(snap -> ...)) {                          // sees STARTING and failures

  Topic orders = simulator.addTopic("orders", 36);

  ProducerHandle load = simulator.addProducer(p -> p.topics(orders).ratePerPartition(2));

  ConsumerGroup next = simulator.addGroup("next", g -> g
      .subscribe(orders)
      .sessionTimeout(Duration.ofSeconds(45))                  // optional group settings
      .consumerDefaults(c -> c.protocol(CONSUMER).assignor("uniform"))
      .consumers(3));                                          // 3 inheriting consumers
  ConsumerGroup legacy = simulator.addGroup("legacy", g -> g
      .subscribe(orders)
      .consumerDefaults(c -> c.protocol(CLASSIC).assignor("cooperative-sticky"))
      .consumer(c -> c.rackId("a"))
      .consumers(2));

  simulator.start();                     // proxy -> topics -> producers -> groups; blocks

  ConsumerHandle c0 = next.consumer(0);  // also next.consumer("consumer-0")
  c0.inject(Fault.freeze());             // stop polling
  c0.inject(Fault.networkIsolation());   // proxy drops its requests
  c0.clear(Fault.freeze());  c0.clearFaults();  c0.faults();
  next.consumer(1).leave();  next.consumer(1).join();
  next.consumer(2).switchProtocol(CLASSIC);

  load.pause();  load.resume();          // watch lag drain in both groups
  next.autopilot().start(Duration.ofSeconds(10), Duration.ofSeconds(30));

  // The same add/remove calls work while running.
  ConsumerHandle extra = next.addConsumer(c -> c.rackId("b"));   // joins immediately
  extra.remove();
  simulator.addGroup("third", g -> g.subscribe(orders).consumers(1));
  legacy.remove();                       // stops consumers, reverts group configs

  next.awaitSnapshot(s -> s.state() == GroupState.STABLE, Duration.ofSeconds(30));
  SimulatorSnapshot all = simulator.snapshot();
}                                        // close() == stop(): removes everything
```

**Harness**
- `Simulator` owns what components share:
  - the admin client;
  - the proxy and its `NetworkFaults`;
  - the thread factory;
  - the poller and the snapshot publisher;
  - the teardown stack.
- `with…` methods configure the harness. They throw
  `IllegalStateException` after `start()`.

**Components**
- The components are `Topic`, `ProducerHandle`, `ConsumerGroup` and
  `ConsumerHandle`.
- They are created with `add…` and destroyed with `remove()`.
- The same calls work before `start()` (declared, then started by
  `start()`) and while running (started immediately).
- Each component has its own internal start and stop, and its own undo
  entries.
- `remove()` rules:
  - Removing a topic that a producer or group still uses throws
    `SimulationStateException`.
  - Removing a group stops its consumers, removes static members,
    reverts its group configs and deletes the group (best-effort).
  - Removing a simulator-owned topic deletes it and waits for the
    deletion.

**Decoupled producers**
- A `ProducerHandle` targets topics, not groups.
- It has `pause()`/`resume()`, and a configurable
  `ratePerPartition(double recordsPerSecond)`.
- The default rate is the prototype's: 1 record per partition every
  500 ms.

**Multiple groups**
- Settings, consumers, autopilot, snapshot and lag are per group.
- Client ids are `<groupId>-consumer-N`. They are unique across groups,
  because the network fault matches on `client.id`.
- Consumer ids (`consumer-N`) are stable within their group and are
  never reused.

**Options lambdas**
- `ProducerOptions`, `GroupOptions` and `ConsumerOptions`.
- A lambda builds a spec, and needs no simulator for it:
  `ProducerSpec.of(p -> …)`, `GroupSpec.of(id, g -> …)` and
  `ConsumerSpec.of(c -> …)`. The `add…(lambda)` methods of the harness
  go through these factories, so the lambdas and the specs are two doors
  into the same model.
- The options take `Duration`s; the specs hold milliseconds
  (`sessionTimeoutMs`), under the same names as `ConfigMetadata` and the
  violations.
- The options reference topics by name (`subscribe("orders")`,
  `topics("orders")`). The overloads taking a `Topic`, used in the
  example above, are planned with the harness.
- Consumer values that are not set inherit from the group's
  `consumerDefaults`. Values that `consumerDefaults` does not set keep
  the defaults of `ConfigMetadata`. The order of the calls in a group
  lambda does not matter.

**Records as the data view**
- `simulator.describe()` (planned) returns an immutable `SimulatorSpec`
  with `topics[]`, `producers[]` and `groups[]`.
- The records:
  - `TopicSpec(name, partitions)`;
  - `ProducerSpec(id, topics, ratePerPartition)`;
  - `GroupSpec(id, subscribedTopics, sessionTimeoutMs,
    heartbeatIntervalMs, assignmentIntervalMs, consumerDefaults,
    consumers)`;
  - `ConsumerSpec(id, overrides, effective)`.
- There are no nullable values in the specs:
  - `ConsumerSettings` is a complete set of consumer settings. It is the
    type of `consumerDefaults` and of `effective`.
  - `ConsumerOverrides` has the same components as `Optional`s. An empty
    one inherits.
  - The empty assignor name selects the default of the protocol (of the
    broker for `CONSUMER`, of the client for `CLASSIC`). The empty rack
    id means no rack.
  - An empty producer or consumer id means "not assigned yet": the
    simulator or the group assigns it when the component is added.
- `effective` is derived, never trusted. Resolution happens in one
  place, `EffectiveConfigResolver`: an override that is set wins, and
  everything else is inherited. A `GroupSpec` recomputes `effective` for
  its consumers when it is constructed. A `ConsumerSpec` outside of a
  group accepts a `null` `effective`, which resolves against the
  `ConfigMetadata` defaults, and keeps a supplied one as informative
  only: validating the consumer or adding it to a group resolves again
  against that group's defaults and never reads the `effective` of its
  argument.
- The records are also the door for request bodies, so a value that is
  left out never surfaces as a `NullPointerException`. The compact
  constructors read a `null` list as empty, a `null` name or id as
  empty, `null` overrides (or a `null` component of them) as "inherit",
  a `null` `consumerDefaults` as the `ConfigMetadata` defaults, and a
  `null` protocol, assignor or rack id of a `ConsumerSettings` as its
  default. The accessors never return `null`. What must not be empty (a
  topic name, a group id, a subscription) is then reported as a
  `Violation`.
- Matching `TopicSpec`, `ProducerSpec`, `GroupSpec` and `ConsumerSpec`
  records are accepted by overloads (planned): `addGroup(GroupSpec)` and
  so on. The server uses these overloads for request bodies.

**Configuration metadata**
- `ConfigMetadata.get()` is the immutable table of everything
  configurable:
  - the fields, each with a key, a label, a scope (`SIMULATOR`, `TOPIC`,
    `PRODUCER`, `GROUP` or `CONSUMER`), a type, a default and, for the
    bounded integers, an inclusive range;
  - the name rule: `[a-zA-Z0-9._-]+`, at most 249 characters, and
    neither `.` nor `..` (Kafka's legal topic names);
  - the assignor names of each protocol, and whether the protocol
    accepts a custom name;
  - the fault ids.
- The defaults of the specs and of the options, and every range check
  of `ConfigValidator`, read this table.
- The defaults and ranges are the prototype's:

| Scope | Key | Default | Range |
|---|---|---|---|
| simulator | `numTopics` | 1 | 1–20 |
| topic | `partitions` | 36 | 1–100 |
| producer | `ratePerPartition` | 2.0 (1 record every 500 ms) | > 0 |
| group | `id` | `kaos` | the name rule |
| group | `numConsumers` | 4 | |
| group | `sessionTimeoutMs` | 45000 | 1000–300000 |
| group | `heartbeatIntervalMs` | 5000 | 500–60000 |
| group | `assignmentIntervalMs` | 1000 | 0–60000 |
| consumer | `protocol` | `CONSUMER` | |
| consumer | `assignor` | empty | see below |
| consumer | `staticMember` | false | |
| consumer | `rackId` | empty | |
| consumer | `pollTimeoutMs` | 0 | 0–60000 |
| consumer | `pollDelayMs` | 1000 | 0–120000 |
| consumer | `maxPollIntervalMs` | 300000 | 1000–600000 |

- `numTopics` and `numConsumers` are counts that a frontend uses for its
  form; they are not spec fields. The validation enforces only the
  maximum of `numTopics`, because an empty simulator is valid.
- Assignors: `CONSUMER` lists `uniform` and `range` and accepts any
  other name; `CLASSIC` accepts only `range`, `roundrobin`, `sticky` and
  `cooperative-sticky`. `Assignors` maps the classic names to the client
  classes.

**Validation**
- `add…` and `start()` (planned) throw `InvalidConfigException` with a
  list of `Violation(field, message)`. `field` is a path such as
  `groups[0].consumers[1].overrides.pollDelayMs`.
- `Simulator.validate(SimulatorSpec)` runs the same check without side
  effects. The server uses it to return 400 before going async.
- `ConfigValidator` is a set of pure functions that return every
  violation at once. Next to the whole-spec check, it checks one topic,
  producer, group or consumer against the existing topics, for the
  `add…` calls made while running.
- The checks:
  - the ranges of `ConfigMetadata`;
  - the name rule, on topic names, on group ids, and on the producer
    and consumer ids that are supplied rather than assigned. Group ids
    and these ids end up in client ids and in the URL paths of the HTTP
    API;
  - a positive finite rate;
  - the assignor against the protocol, on the `consumerDefaults` of the
    group, and on the effective settings of each consumer that overrides
    its protocol or its assignor. The violation always names a value
    that was sent: `consumerDefaults.assignor`, `overrides.assignor`,
    or `overrides.protocol` when the consumer overrides only its
    protocol and the inherited assignor does not fit it;
  - a producer or a group names at least one topic, only topics of the
    spec, and none twice;
  - topic names, group ids, and assigned producer ids are unique; so are
    assigned consumer ids within their group.

**Faults are first-class**
- `Fault` is a sealed type. A new fault is a new `Fault`. It adds no
  methods to `ConsumerHandle`.
- Faults are values (empty records). Each has an `id()` (`freeze`,
  `network-isolation`, `connection-reset`), which the HTTP API and
  `ConfigMetadata` use; `Fault.of(id)` is the reverse.
- The snapshot lists each consumer's active faults.

| Fault | Where it acts | Effect |
|---|---|---|
| `Fault.freeze()` | The runner stops polling. It never touches the proxy. | The member is evicted by the max poll interval. |
| `Fault.networkIsolation()` | The proxy silently drops that client's requests (a blackhole, like a real partition). | The session times out and the member is fenced. After the fault is cleared, recovery can take up to `request.timeout.ms`. |
| `Fault.connectionReset()` | The proxy closes that client's connections instead of forwarding. | The client sees disconnects and retries quickly. |

- `Fault.connectionReset()` is available in the Java and HTTP APIs and
  is listed in `/meta`. The UI does not expose it; it offers only the
  blackhole.

**Own small types**
- The enums are `Protocol`, `Phase`, `GroupState`, `GroupType`,
  `PartitionState`, `ConsumerStatus` and `ProducerState`.
- There is a `PartitionRef` record.
- No kafka-clients class appears in the public API or in the JSON.
- `ConsumerGroup` does not clash with kafka-clients.
- `ProducerHandle` and `ConsumerHandle` avoid Kafka's `Producer` and
  `Consumer` interfaces.

**Naming rule**
- No Java type name contains "Kaos".
- The project name stays in the Maven modules, the package
  `io.github.dajac.kaos`, thread names, the `kaos.events` logger and the
  `kaos.uiSettings` key.

**Sync and async**
- These calls block: `start`, `stop`/`close`, `add…` and `remove()`
  while running, and `switchProtocol`.
- These calls return immediately: `inject`, `clear`, `join`, `leave`,
  `pause` and `resume`.
- There are no `CompletableFuture` variants. `awaitSnapshot` on the
  simulator or on a group covers "wait until".

**Thread safety**
- Every public method is thread-safe.
- Listeners run on one publisher thread. Listener exceptions are caught
  and logged.

**Concurrency limits**
- There is no static state, so several `Simulator`s can coexist in one
  JVM. Each gets its own proxy and its own port block.
- The server holds one `Simulator`.

### Threading and failure model

Status: planned.

**Threads**
- `kaos-<groupId>-consumer-N` is the sole owner of its `KafkaConsumer`.
- There is one `kaos-producer-<id>` thread per producer.
- `kaos-poller` runs one thread for the whole simulator. It covers all
  groups, because `describeConsumerGroups` takes a list.
- `kaos-publisher` runs one thread. It assembles, diffs and stores the
  snapshot, and calls the listeners.
- There is one `kaos-<groupId>-autopilot` thread while that group's
  autopilot is on.
- `inject`, `clear`, `join`, `leave`, `pause` and `resume` only flip
  atomics that the owning loop reads. The exceptions are `wakeup()` and
  `switchProtocol`.

**Thread creation**
- Threads are named platform threads. On JDK 21, virtual threads would
  pin their carrier inside the Kafka client (`synchronized` blocks and
  selector I/O).
- All threads are created through one internal `ThreadFactory` in
  `engine/`. Switching to virtual threads later is then a one-line
  change.
- Javalin serves HTTP requests on virtual threads (a config flag).
  Handlers only flip flags, read the snapshot reference or submit work
  to the lifecycle executor.

**Shared state**
- Only three things cross threads:
  - the per-runner atomics and local assignment, which the publisher
    copies into an immutable `ConsumerLocalState`;
  - `NetworkFaults`;
  - the `AtomicReference` holding the latest snapshot.
- Component collections are immutable lists. They are swapped atomically
  under the structure lock (copy-on-write). Readers such as the poller,
  the publisher and the autopilot never see a list being mutated.

**Locks**
- A single `structureLock` per `Simulator` serializes `start`, `stop`
  and every `add…`/`remove()`.
- A per-runner `switchLock` is held during `switchProtocol` and during
  consumer removal. `stop` takes each `switchLock` in turn.
- Flag flips take no lock.
- The publisher takes no locks.

**Teardown**
- `Resources` is a stack of undo actions. Each component has its own
  sub-stack, and the simulator has the top-level stack.
- Undo actions are registered only after their step succeeded.
- A failed `start`, a failed `add…`, `remove()` and `close()` all unwind
  through the same code.
- Each undo runs in its own try/catch. Failures are attached as
  suppressed exceptions on a single `SimulationException`.
- `consumer.close(Duration)` is bounded.
- `close` is idempotent.
- Waits are interruptible (`parkNanos` or a `Condition`, plus
  `consumer.wakeup()`). There are no sleep-slice loops.

**Runtime failures**
- A runner poll error is logged to `kaos.events` and the loop continues.
- An unrecoverable runner error sets the consumer status to `FAILED`
  with a message. The simulator keeps running.
- A poller error keeps the last `AdminView` and sets `stale` on the
  snapshot.
- Autopilot validates `min ≤ max`. It does nothing when its group has no
  consumers, and it stops when its group is removed.

### Snapshot pipeline

Status: planned.

1. `GroupPoller` polls every 200 ms, with one `describeConsumerGroups`
   call for all groups plus the offsets. It produces an `AdminView` per
   group.
2. `SnapshotAssembler`, a pure function, merges each group's view with
   the local state of its consumers, and adds the topics and producers.
3. `SnapshotPublisher` compares the result with the last snapshot and
   ignores the revision number.
4. If the snapshot changed, the publisher bumps `revision`, stores it in
   an `AtomicReference` and notifies the listeners.
5. Local flag flips and structural changes poke the publisher directly,
   so they show up immediately.

`simulator.snapshot()`, `group.snapshot()` (one slice of it),
`GET /snapshot` and SSE all read the same reference.

**Domain logic owned by core** (any frontend, a TUI included, gets it
for free):
- Partition classification (assigned, pending, revoking, empty).
- Out-of-group blanking.
- Classic eager mid-rebalance blanking. It uses the **per-consumer**
  effective assignor. (The prototype uses the group-level assignor.)
- The Stable→Reconciling override.
- Status priority `STOPPED > FROZEN > ISOLATED > …`. `connectionReset`
  counts as ISOLATED.
- Effective config.
- Consumer ids.

**Owned by the UI** (presentational only):
- Lag rounding and fill.
- Axis stride.
- Tooltip placement.
- Hiding the badge for healthy classic members.
- S/M/L strip height.

### Proxy without static state

Status: planned.

- There is one proxy and one `NetworkFaults` instance per `Simulator`.
  All groups share them.
- Per-consumer independence comes from the filter deciding on **every
  request** by `header.clientId()`.
  - Client ids are `<groupId>-consumer-N`, so they are unique across
    groups.
  - All of a consumer's connections (coordinator, leaders, metadata)
    carry the same id, so they go dark together.
  - Nothing else is affected.
- `NetworkFaults` maps each client id to a mode: `BLACKHOLE` (drop) or
  `RESET` (close the connection).
- Adding or removing consumers or groups at runtime needs nothing from
  the proxy.
- Producers and the admin client bypass the proxy.
- The `NetworkProxy` interface keeps the topology swappable. A proxy per
  group or a port per consumer would only matter for connection-level
  faults that need identity by port.
- `NetworkFaultFilterFactory` has two constructors: a no-arg "unbound"
  one used by the ServiceLoader, and a bound one that takes a
  `NetworkFaults`.
- `BoundPluginRegistry implements PluginFactoryRegistry` returns the
  bound instance for the Kaos filter type. It delegates everything else
  to `ConfigParser`.
- `ProxyLauncher` does the following:
  - It builds the Kroxylicious YAML from a `Map`. It does not use string
    concatenation or the unstable record constructors.
  - It discovers node ids with `describeCluster`. It emits explicit
    `nodeIdRanges`, because the default 0..2 breaks other clusters.
  - It probes a free contiguous port block starting at `--proxy-port`.
  - It calls `startup().get(timeout)`.
  - It confines the unavoidable `internal.config.Features` import.

### HTTP API v1 (`kaos-server`)

Status: planned, except a stub of `GET /api/v1/meta` that returns only
`{"version": "<build version>"}`, and the command line:
`--bootstrap-server <host:port>` (default `localhost:9092`),
`--port <port>` (default 8081), `--help` and `--version`. picocli parses
it: `CliOptions` holds the annotated options, `Main` is the command that
mixes them in, and `Version` provides `--version`. The application owns
every option: `run.sh` builds the jar and passes all its arguments
through unchanged, so a new option needs no change in the script.

Exit codes: `--help` and `--version` exit with status 0. Invalid options
print the error and the usage, and exit with status 2. A failed startup,
such as a port already in use, releases the server, prints the reason
and exits with status 1.

The HTTP API is resource-oriented and mirrors the Java model. The server
holds one `Simulator`.

```
GET    /api/v1/meta                     defaults, ranges, assignors per protocol, faults, version
GET    /api/v1/simulator                {phase, spec (overrides + effective), lastError, warnings}
POST   /api/v1/simulator                SimulatorSpec {topics[], producers[], groups[]}
                                        declarative create + start, atomic -> 202 | 400 | 409
DELETE /api/v1/simulator                stop and remove everything -> 202 | 204 if idle
GET    /api/v1/simulator/snapshot       full SimulatorSnapshot
GET    /api/v1/simulator/events         SSE (see below)

# dynamic, only while RUNNING (409 otherwise)
POST   /api/v1/simulator/topics                          TopicSpec
DELETE /api/v1/simulator/topics/{name}
POST   /api/v1/simulator/producers                       ProducerSpec
DELETE /api/v1/simulator/producers/{id}
PUT    /api/v1/simulator/producers/{id}/state            {"paused": true|false}
POST   /api/v1/simulator/groups                          GroupSpec
DELETE /api/v1/simulator/groups/{groupId}
PUT    /api/v1/simulator/groups/{groupId}/autopilot      {enabled, minIntervalMs, maxIntervalMs}
POST   /api/v1/simulator/groups/{groupId}/consumers      ConsumerSpec
DELETE /api/v1/simulator/groups/{groupId}/consumers/{id}
PUT    /api/v1/simulator/groups/{groupId}/consumers/{id}/faults/{fault}   freeze | network-isolation | connection-reset
DELETE /api/v1/simulator/groups/{groupId}/consumers/{id}/faults/{fault}   (.../faults clears all)
POST   /api/v1/simulator/groups/{groupId}/consumers/{id}/actions
                                        {"type":"join"} | {"type":"leave"}
                                        | {"type":"switch-protocol","protocol":"classic|consumer"}
GET    /api/v1/openapi.yaml
```

**Mapping and UI usage**
- The mapping to the Java API is one-to-one.
- The UI uses only a subset:
  - `POST /simulator` with one group, one producer and the topics
    `<groupId>-1..N`;
  - `DELETE /simulator`;
  - the faults, actions and autopilot endpoints;
  - SSE.
- The remaining endpoints exist for other frontends, and for a
  multi-group UI.

**SSE events**
- `snapshot` events use `id=revision` and carry the full snapshot.
- `status` events are sent on every change.
- The current `status` and `snapshot` are both sent on connect.
- A `: ping` heartbeat is sent every 15 s.
- Slow clients are coalesced to the latest snapshot.

**Errors**
- RFC 9457 `application/problem+json` with `violations[{field,message}]`.
- 400 for validation failures, 404 for an unknown resource, 409 for the
  wrong phase or a resource in use.
- Jackson runs with `FAIL_ON_UNKNOWN_PROPERTIES`.

**Snapshot shape**
- `revision`, `phase`, `stale`.
- `topics[{name,partitions}]`.
- `producers[{id,topics,state,ratePerPartition}]`.
- `groups[]`, each with:
  - `id`, `type`, `state`, `epoch`, `targetAssignmentEpoch`,
    `generation`;
  - `subscribedTopics`, `autopilot`;
  - `lag{topic:[…dense…]}`;
  - `consumers[]`.
- Each entry of `consumers[]` has:
  - `id`, `clientId`, `protocol`;
  - `subscribed`, `faults[]`;
  - `memberId`, `instanceId`, `memberEpoch`, `generation`;
  - `coordinatorState`, `status`;
  - `partitions[{topic,partition,state}]`;
  - `raw{assignment,targetAssignment,localAssignment}`.

**Lifecycle handling**
- `SimulationService` owns a single-thread `kaos-lifecycle` executor.
- All blocking operations are submitted there and the request returns
  202. These are start, stop, add, remove and switch-protocol.
- Failures surface as `lastError` and as an SSE `status` event.

**Metadata and static files**
- `/meta` is served from `ConfigMetadata`. `ConfigValidator` uses the
  same table, so there is one source of truth.
- `StaticUi` serves the classpath `/ui` directory with an SPA fallback.
  Hashed assets are immutable; `index.html` is `no-cache`.

### Frontend (`kaos-ui`)

Status: planned.

**State (Zustand)**
- Components use selector subscriptions.
- On SSE ingest, unchanged consumer objects are reused (structural
  sharing), so `React.memo` holds.
- The config store keeps `groups[]` and the Rows view reads its group
  through a `useGroup(groupId)` selector, so no component assumes a
  single group.

**Heatmap**
- `PartitionStrip` is memoized on a cheap signature.
- Cells are plain `<div class="pcell" style="--fill">`.
- There is one delegated tooltip.

**CSS**
- The styles are the prototype's `<style>` block (`simulator.html` on
  `initial-setup`), lifted **verbatim** and split by region into
  `styles/*.css`. The class names are kept.
- The Bento rules and the dead rules are not carried over.
- No CSS modules. The visual result is identical to the prototype.

**Dev loop**
- The Vite dev server proxies `/api`, including SSE, to `localhost:8081`.
- Command: `./mvnw -pl kaos-server -am -DskipUi compile exec:java` plus
  `npm run dev`. The `compile` phase lets the reactor resolve
  `kaos-core` from its `target/classes`. The backend half works today;
  `exec:java` is skipped in every module except `kaos-server`.

## Features

Status: planned.

The UI is at parity with the prototype: one group, one producer, the
same screen. Behind the UI, the core and HTTP APIs additionally support
independent producers (pause and rate), multiple groups, and adding or
removing components at runtime.

**Config panel**
- Fields: group id, protocol (consumer or classic), static membership,
  number of topics (1–20) and number of partitions (1–100).
- Assignor. The presets depend on the protocol. A custom free-text name
  is accepted for the consumer protocol.
- Optional group settings, added with "+ Add setting":
  - session timeout, heartbeat interval, assignment interval;
  - poll timeout, poll delay, max poll interval.
- The consumer list has add, duplicate-last and remove.
- Each consumer has collapsible per-consumer **overrides**: protocol,
  assignor, rack id, poll timeout, poll delay, max poll interval, static
  member.
- A collapsed consumer shows chips and a protocol tag. A consumer with no
  overrides shows the "inherits all settings" note.
- The panel is locked while a simulation runs.

**Lifecycle**
- Start: create the topics, apply the group configs, start the proxy,
  the producer, the consumers and the poller.
- Stop: tear everything down, delete the topics and revert the group
  configs.
- Reloading the page rehydrates the UI from the server, including the
  per-consumer overrides.

**Per-consumer actions**

| UI label | Java API |
|---|---|
| Stop / Start consumer | `leave()` / `join()` |
| Freeze / Unfreeze | `Fault.freeze()` (stop polling) |
| Partition network / Heal | `Fault.networkIsolation()` (blackhole). The UI does not expose `Fault.connectionReset()`. |
| Upgrade to consumer / Downgrade to classic | `switchProtocol(..)` |

Mixed-protocol groups are supported.

**Auto-Pilot**
- Randomly flips one consumer's subscription every 10–30 s.

**Load and lag**
- The producer writes 1 record per partition every 500 ms.
- Per-partition lag is the latest offset minus the committed offset.

**Rows view**
- Each consumer has a heatmap strip per topic.
- Cell states are assigned, pending, revoking or empty.
- The lag gauge rounds up to a multiple of 5 and scales to 100.
- An axis rail with ticks.
- A state badge and a hover meta display: member id, instance, epoch or
  generation.
- An "Actions ▾" menu.
- A floating tooltip, placed below the cell and clamped to the viewport.
- The empty states for out-of-group and eager mid-rebalance.

**Header**
- Group type and state badges; epoch and target epoch, or generation for
  classic groups.
- A legend.
- A ⚙ menu with the S/M/L strip height, persisted in `localStorage`.

**Differences from the prototype**
- The Bento view and the New/Old toggle do not exist.
- The per-row classic generation shows the member's generation. (The
  prototype always shows "—".)
- The eager mid-rebalance blanking uses the per-consumer assignor.
- Errors show inline or as a banner instead of `alert()`.
- A "disconnected" banner appears when SSE drops.

## Verification

Status: planned, except that `./mvnw verify` already builds the modules,
runs the unit tests and the integration-test harness below, and produces
the shaded jar, that `-DskipITs` works, that `./run.sh` builds and
starts the server, and that the CI workflow runs the build.

**Build**
- `./mvnw verify` runs the unit tests, the Testcontainers ITs (Docker is
  required) and the UI build with Vitest.
- `-DskipUi` and `-DskipITs` give fast local loops.

**Continuous integration**
- One GitHub Actions workflow, `.github/workflows/ci.yml`, runs
  `./mvnw -B -ntp verify` on `ubuntu-latest` with Temurin 21 and the
  Maven cache. The integration tests use the runner's Docker through
  Testcontainers, so CI runs exactly the local build.
- It triggers on every `pull_request`, whatever the base branch, so
  stacked PRs are covered, and on `push` to `main`.
- `concurrency` cancels the superseded runs of a pull request only, so
  every push to `main` runs to completion. The token is limited to
  `contents: read`, and the surefire and failsafe reports are uploaded
  as the `test-reports` artifact when the build fails.
- The UI build will join the same job through `frontend-maven-plugin`,
  with no workflow change.

**Integration-test harness**
- All test classes are named `*Test`. An integration test is a test
  class annotated with `@IntegrationTest`, which tags it with the JUnit
  tag `integration`. The class name plays no role in the split.
- The parent POM configures the split once for every module: surefire
  excludes the tag `integration` and runs the unit tests; failsafe scans
  the `*Test` classes, runs only the tagged ones in the
  `integration-test` phase, and `-DskipITs` skips them only. Each test
  runs exactly once.
- `@IntegrationTest` lives in the `kaos-core` test sources
  (`io.github.dajac.kaos.test`) and also registers the JUnit extension
  `KafkaClusterExtension`. A test declares a `KafkaCluster` parameter,
  which gives `bootstrapServers()`, `createAdmin()` and
  `uniqueName(prefix)`.
- The broker is a Testcontainers `apache/kafka` container whose tag is
  the version of the kafka-clients on the classpath, so the broker and
  the clients match by default. The system property `kaos.test.kafka.image`
  replaces the whole image name, for a clients version that has no
  published image; failsafe forwards it, as in
  `./mvnw verify -Dkaos.test.kafka.image=apache/kafka:4.2.0`.
- The broker starts on first use and is kept in the root store of the
  JUnit engine. All the integration tests of a JVM share it, JUnit stops it
  when the engine finishes, and no static state is involved. Because the
  cluster is shared, tests use unique topic and group names and never
  assume an empty cluster.
- `KafkaClusterTest` is the smoke test: it creates a topic with an
  `Admin` and describes it.
- When `kaos-server` needs the harness, `kaos-core` will publish its
  test sources as a `test-jar` that the server consumes in test scope.
  This is planned.

**Backend end-to-end**
1. Start a local broker:
   `docker run -p 9092:9092 apache/kafka:4.3.1`.
2. Run `./run.sh` and open `http://localhost:8081`.
3. Exercise start, every action and stop.
4. Confirm the API from the command line:
   `curl localhost:8081/api/v1/simulator/snapshot` and
   `curl -N localhost:8081/api/v1/simulator/events`.

**Java API**
- The ITs drive `new Simulator(..)`, `add…` and `start()` without HTTP,
  including a two-group scenario.
- The README example compiles as a test.
