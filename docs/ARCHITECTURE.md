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
| `kaos-core` | the spec, options and validation model is built (specs, options lambdas, `ConfigMetadata`, `Fault`, `Protocol`, `Simulator.validate`, `internal/config`), and so are the `internal.proxy` package (see [Proxy without static state](#proxy-without-static-state)), the snapshot model with its pure logic (the snapshot records and enums, `internal/state`), and the harness with topics, producers and consumer groups, added and removed before `start()` or while running (`Simulator`, `Topic`, `ProducerHandle`, `ConsumerGroup`, `ConsumerHandle`, `Autopilot`, listeners, the `kaos.events` change log, `internal/engine` and `internal/kafka`) |
| `kaos-server` | the HTTP API v1 is built (`Main`, `Server`, `ApiRoutes`, `SimulationService`, `SseHub`, `ProblemHandler`, `dto/*`: every route, SSE, problem details, `/meta`), and so is the OpenAPI document `openapi/kaos-v1.yaml`, served at `GET /api/v1/openapi.yaml` and checked by `OpenApiContractTest`, and so is `StaticUi`, which serves the UI at `/` |
| `kaos-ui` | built: the Maven module with the frontend build and `-DskipUi`, Vite, React and TypeScript, the fonts, `styles/tokens.css` and `styles/base.css`, the layout shell, the generated `api/schema.d.ts` with its check, and Vitest; the pure UI model (`model/`: the config form, the assignors, the lag gauge, the axis, the tooltip placement, the rows and the structural sharing); the config panel (`api/client.ts`, `state/configStore.ts`, `components/config/`, `styles/config.css` and `styles/controls.css`); and the Rows view (`api/events.ts`, `state/simulationStore.ts`, `state/uiSettingsStore.ts`, `components/rows/`, `styles/rows.css`) |

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
Javalin, Jackson, the command line, logging, packaging, the code format,
and JUnit 6 with AssertJ, Awaitility, Testcontainers and failsafe).
The API contract is in place on the Java side: the hand-written OpenAPI
document and its contract test, and so is the frontend build with the
generated TypeScript types, Zustand, and React Testing Library with
jsdom. Mockito is planned.

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
| Code format | `spotless-maven-plugin` 3.10.x with the Eclipse JDT formatter (`config/eclipse-formatter.xml`), import ordering and the Apache License 2.0 header, checked in `verify` |
| Java tests | JUnit (junit-bom), AssertJ 3.27.x, Mockito 5.x, Awaitility 4.3, Testcontainers 2.0.x `testcontainers-kafka` with `apache/kafka:4.3.1`, failsafe for the tests tagged `integration`, `com.networknt:json-schema-validator` 2.0.x (the Jackson 2 line) for the contract test |
| Frontend | Node 24 LTS (pinned by `frontend-maven-plugin` 2.0.x + `.nvmrc`), Vite 8, React 19, TypeScript 6, Zustand 5, `@fontsource-variable/{jetbrains-mono,dm-sans}` |
| Frontend tests | Vitest 5, React Testing Library 16 with `user-event` 14 and jsdom 30. Playwright is deferred. |
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
- **Code format.**
  - The Java code style is the Eclipse JDT formatter profile
    `config/eclipse-formatter.xml`. Spotless applies it to the main and
    test sources of every module, orders the imports (static, then
    `java`/`javax`, then the rest, groups separated by a blank line) and
    removes the unused ones.
  - Every Java source file starts with the Apache License 2.0 header,
    the `license.header` property of the parent `pom.xml`, with a fixed
    year so that it never changes with the calendar. Spotless's
    `licenseHeader` step puts it in place; that step always skips
    `package-info.java`, so a `replaceRegex` step does the same there.
  - `spotless:check` runs in `verify` in every module, and
    `./mvnw spotless:apply` fixes the sources. `pom.xml` and Markdown
    files are not formatted.
  - Spotless's up-to-date index is off, because it does not see a change
    of the formatter profile.
- **Version.** Maven filters `version.properties` in `kaos-server`, and
  `/api/v1/meta` reports the version read from it.
- **Jackson in the server.** `kaos-server` adds `jackson-datatype-jdk8`
  for the `Optional`s of `ConsumerOverrides`.
- **Contract enforcement.** The committed `schema.d.ts` is checked with a
  diff: the build runs `npm run check:api`, which is `openapi-typescript
  --check` on the OpenAPI document of `kaos-server`, and fails when a
  fresh generation differs; `npm run gen:api` regenerates the file. A
  Java contract test, `OpenApiContractTest`, validates the fixtures
  against the spec with the OpenAPI 3.1 dialect of
  `json-schema-validator`.
- **TypeScript 6.** TypeScript 7 has no compiler API, which
  `openapi-typescript` needs, so the build steps back to 6.
  `openapi-typescript` 7.x declares a peer range of TypeScript 5, and an
  `overrides` entry of `package.json` gives it the project's TypeScript.
- **Frontend build.** `frontend-maven-plugin` installs the pinned Node,
  with its npm, into `kaos-ui/node`, then runs `npm ci`, the check of
  the generated types, `tsc --noEmit`, `vite build` into
  `target/classes/ui` and `vitest run`. There is no linter. `-DskipUi`
  skips all these steps: `kaos-ui` is then an empty jar and the server
  serves no UI.
- **License headers of the UI.** The TypeScript and CSS sources of
  `kaos-ui` and `vite.config.ts` start with the same header, the
  `license.header` property of the parent `pom.xml`, checked by Spotless's `licenseHeader` step in a generic
  format of `kaos-ui/pom.xml`, with the same `spotless:check` and
  `spotless:apply`. The header ends at the first line that is neither
  `/*` nor inside a block comment. The generated `schema.d.ts` has no
  header.

## Project structure

Status: the parent POM, the Maven wrapper, `run.sh`, and `Main`,
`CliOptions`, `Version`, `Server`, `ApiRoutes`, `SimulationService`,
`SseHub`, `ProblemHandler`, `Json`, `ApiException` and `dto/*` of
`kaos-server` exist, with the OpenAPI document `openapi/kaos-v1.yaml`, next to `LICENSE`, `.gitignore`, `CLAUDE.md`, the
CI workflow, `config/` and this document. In `kaos-core`, the spec, options and
validation model exists: the five spec records with `ConsumerSettings`
and `ConsumerOverrides`, the three options classes, `ConfigMetadata`,
`Fault`, `Protocol`, `Violation`, `SimulationException`,
`InvalidConfigException`, the whole `internal/config` package and the
whole `internal/proxy` package. The snapshot model exists too: the seven
snapshot records, the six enums next to `Protocol`, and the whole
`internal/state` package. So does the harness for topics and producers:
`Simulator`, `Topic`, `ProducerHandle`, `SnapshotListener`,
`Registration`, `SimulationStateException`, and, in `internal`,
`engine/` (`SimulatorEngine`, `TopicComponent`, `ProducerRunner`,
`Pacer`, `Resources`, `Threads`) and `kafka/` (`TopicManager`,
`ClientFactory`). So does the harness for consumer groups:
`ConsumerGroup`, `ConsumerHandle`, `Autopilot`, `engine/GroupRuntime`,
`ConsumerRunner` and `AutopilotImpl`, `kafka/GroupConfigManager`,
`GroupPoller` and `GroupCleanup`, and `state/EventLog`. Everything else
in the tree below is planned. `StaticUi` exists, and so does the
`kaos-ui` scaffold with the `-DskipUi` switch: `package.json`,
`vite.config.ts`, `tsconfig.json`, `index.html`, `.nvmrc`, `pom.xml`,
`main.tsx`, `App.tsx`, `api/schema.d.ts`, `styles/tokens.css` and
`styles/base.css`; so do `model/`, `api/client.ts`,
`state/configStore.ts`, `components/config/`, `styles/config.css` and
`styles/controls.css`. The test harness of `kaos-core` also exists; see
[Verification](#verification).

```
kaos/
  pom.xml                      parent: BOMs, pluginManagement, -DskipUi, -DskipITs
  README.md  LICENSE  .gitignore  run.sh  CLAUDE.md
  docs/ARCHITECTURE.md         living design doc, kept up to date in every PR
  docs/images/                 the screenshot of the README
  config/                      eclipse-formatter.xml, the Spotless profile
  .github/workflows/ci.yml     CI: ./mvnw verify on every PR and on push to main
  kaos-core/                   library: kafka-clients, kroxylicious-runtime, slf4j-api
    src/main/java/io/github/dajac/kaos/            PUBLIC API
      Simulator, Topic, ProducerHandle, ConsumerGroup, ConsumerHandle, Autopilot, Fault
      ProducerOptions, GroupOptions, ConsumerOptions, Registration, SnapshotListener
      SimulatorSpec, TopicSpec, ProducerSpec, GroupSpec, ConsumerSpec (records), ConfigMetadata
      ConsumerSettings, ConsumerOverrides (records), Violation
      SimulatorSnapshot, ProducerSnapshot, GroupSnapshot, AutopilotSnapshot, ConsumerSnapshot,
      PartitionView, PartitionRef
      enums: Protocol, Phase, GroupState, GroupType, PartitionState, ConsumerStatus, ProducerState
      SimulationException, InvalidConfigException (List<Violation>), SimulationStateException
    src/main/java/io/github/dajac/kaos/internal/   NOT API
      config/   ConfigValidator, EffectiveConfigResolver, Assignors
      engine/   SimulatorEngine, TopicComponent, GroupRuntime, ConsumerRunner, ProducerRunner,
                Pacer, AutopilotImpl, Resources, Threads
      kafka/    TopicManager, GroupConfigManager, GroupPoller (-> AdminView), GroupCleanup, ClientFactory
      proxy/    NetworkProxy, ProxyLauncher, NetworkFaults, NetworkFaultFilterFactory,
                BoundPluginRegistry (+ META-INF/services FilterFactory entry)
      state/    SnapshotAssembler, PartitionClassifier, StatusDeriver, SnapshotPublisher, EventLog,
                AdminView, GroupLocalState, ConsumerLocalState (input records)
    src/test/java/io/github/dajac/kaos/test/       IT harness: IntegrationTest, KafkaClusterExtension, KafkaCluster
  kaos-server/                 Javalin adapter + shaded runnable jar
    src/main/java/io/github/dajac/kaos/server/
      Main (CLI), CliOptions, Version, Server, ApiRoutes, SimulationService, SseHub, ProblemHandler,
      Json, ApiException, StaticUi, dto/*
    src/test/scripts/curl-session.sh   scripted curl session against a running server
    src/main/resources/openapi/kaos-v1.yaml
  kaos-ui/                     packaging jar; vite build -> target/classes/ui
    package.json package-lock.json vite.config.ts tsconfig.json index.html .nvmrc pom.xml
    src/ main.tsx App.tsx
      api/        schema.d.ts (generated), client.ts, events.ts (EventSource + backoff)
      state/      simulationStore.ts, configStore.ts, uiSettings.ts ('kaos.uiSettings')
      model/      configModel.ts, assignors.ts, violations.ts, lag.ts, axis.ts, tooltip.ts   (pure, tested)
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
snapshot records and the enums of "Own small types" are built as well;
see [Snapshot pipeline](#snapshot-pipeline). The harness is built: the
`with…` methods, `addTopic`, `addProducer`, `addGroup`, `start`,
`stop`, `close`, `describe`, `snapshot`, the listeners and
`awaitSnapshot`, and, on the groups, `consumer(..)`, `addConsumer`,
`autopilot()`, `warnings()`, `snapshot()` and `awaitSnapshot`, every
method of `ConsumerHandle`, and `remove()` on every component, before
`start()` and while the simulator runs.

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
- `with…` methods configure the harness: `withClientProperty` (a
  property of every Kafka client), `withProxyPort` and `withListener`.
  They throw `IllegalStateException` after `start()`, that is whenever
  the phase is not `IDLE`.
- `start()` runs proxy -> topics -> producers -> groups and blocks
  until everything runs. It validates the declared components first,
  and throws `SimulationStateException` when the simulator is already
  started. The consumers are running when it returns; they join their
  group on their own afterwards, which `awaitSnapshot` observes.
- `stop()` undoes everything and also removes every component, so the
  simulator is `IDLE` and empty afterwards. `close()` is `stop()`. Both
  are idempotent, and the simulator can be configured, declared and
  started again; the assigned ids start again too.
- **Start failure.** There is no `FAILED` phase. A failed `start()`
  undoes the steps that succeeded (see
  [Threading and failure model](#threading-and-failure-model)), throws
  a `SimulationException`, and brings the phase back to `IDLE` with the
  components still declared, so the start can be tried again. The
  failure is observable on the snapshot: `lastError` holds the message
  of the last failed `start()` or `stop()` and is empty otherwise. A
  listener attached before `start()` therefore receives `STARTING`, then
  `IDLE` with a non-empty `lastError`. The HTTP API serves the same
  value as `lastError`.
- The next `start()` resets `lastError`, and so does the `stop()` of a
  running simulator. A `stop()` that finds nothing running keeps it, so
  a caller that removes the declared components after a failed start,
  as the server does, does not lose the failure.
- A `stop()` or `close()` that is concurrent with `start()` waits for
  the start to finish and then stops what it started.
- **Listeners.** `withListener(listener)` attaches a listener for the
  life of the simulator. `addListener(listener)` works at any time and
  returns a `Registration`, whose `close()` removes the listener. A
  listener receives the snapshots published after it was added;
  `snapshot()` gives the current one. `awaitSnapshot(condition,
  timeout)` tests the current snapshot and then every new one, returns
  the first match, and throws `SimulationException` at the timeout.
- A listener must not call a blocking method of the simulator (`start`,
  `stop`, `close`, `with…`, `add…`, `remove()`, `awaitSnapshot`): they throw
  `SimulationStateException` on the publisher thread. See "Locks" in
  [Threading and failure model](#threading-and-failure-model).
- `Topic`, `ProducerHandle`, `ConsumerGroup`, `ConsumerHandle` and
  `Autopilot` are interfaces, implemented in `internal/engine`.
  `topics()`, `producers()` and `groups()` list the components.

**Components**
- The components are `Topic`, `ProducerHandle`, `ConsumerGroup` and
  `ConsumerHandle`.
- They are created with `add…` and destroyed with `remove()`.
- The same calls work before `start()` (declared, then started by
  `start()`) and while running (started immediately; the call blocks
  until the component runs or is stopped). A component is added to a
  group with `group.addConsumer(options)` or `addConsumer(ConsumerSpec)`,
  and to the simulator with the other `add…` methods.
- Each component has its own internal start and stop, and its own undo
  entries.
- A failed `add…` while running unwinds what the component started and
  leaves the simulator unchanged, in its state and on the cluster. A
  failure that is not already a `SimulationException`, such as a Kafka
  client that refuses its properties, is wrapped in one (`Could not
  start <component>`). It does not set `lastError`, which is kept for
  the start and the stop.
- `remove()` rules:
  - Removing a topic that a producer or group still uses throws
    `SimulationStateException`, and changes nothing.
  - Removing a group turns its autopilot off, stops its consumers,
    removes static members, deletes the group and reverts its group
    configs (the Kafka steps best-effort).
  - Removing a consumer stops it, which leaves the group, and removes
    its static member if the coordinator still lists it.
  - Removing a simulator-owned topic deletes it and waits for the
    deletion.
  - A component is removed even when one of its undo actions failed;
    the failure is then thrown as a `SimulationException`.
- A removed component is gone from `describe()`, the lists and the
  snapshot. Its handle keeps answering its accessors (`id()`,
  `name()`, …), and the methods that act on it (`remove()`, `pause`,
  `join`, `inject`, `switchProtocol`, `addConsumer`, …) throw
  `SimulationStateException`. `stop()` removes every component, so the
  handles of a stopped simulator behave the same.
- Topic names and group ids are supplied, not assigned, so a name can
  be used again once its component is removed.

**Decoupled producers**
- A `ProducerHandle` targets topics, not groups.
- It has `pause()`/`resume()`, and a configurable
  `ratePerPartition(double recordsPerSecond)`, which is set in the
  options and fixed afterwards.
- The default rate is the prototype's: 1 record per partition every
  500 ms.
- A producer writes in rounds: at each tick of its rate, one record to
  every partition of its topics. A round that fails is logged and not
  retried; the next tick starts a new round. A loop that falls behind
  does not catch up with a burst (`Pacer`).
- `pause()` lets the round in flight finish. A producer paused before
  `start()` starts paused.
- Assigned producer ids are `producer-N`. They are never reused until
  `stop()`, not even the id of a removed producer, and skip the ids that
  were supplied.

**Consumer groups**
- A group is declared with `addGroup(id, options)` or
  `addGroup(GroupSpec)`. Its consumers are reached with
  `consumer(int)`, which is `consumer("consumer-N")`, and
  `consumer(String)`; both throw `IllegalArgumentException` for an
  unknown consumer. `consumers()` lists them in order.
- `group.snapshot()` is the slice of the simulator snapshot for that
  group; `group.awaitSnapshot(condition, timeout)` waits on that slice.
  A group that is declared but not published yet is shown as the
  simulator knows it: its type and state `UNKNOWN`, its consumers
  `JOINING` and its lag unknown.
- Starting a group sets its group configs, starts its consumers and
  opens its autopilot. The group configs it sets are the explicit
  settings of the spec, those whose value differs from the default in
  `ConfigMetadata`; a setting with that default is left to the broker,
  even when the user set it to that value. `warnings()` lists what the start could not
  apply and went on without; it is empty while the group does not run.
- The session timeout and the heartbeat interval apply to both
  protocols. A consumer-protocol member takes them from the group
  configs (`consumer.session.timeout.ms`,
  `consumer.heartbeat.interval.ms`), and its client refuses them. A
  classic member takes them from its client, so `ConsumerRunner` sets
  `session.timeout.ms` and `heartbeat.interval.ms` on the client of a
  consumer that speaks the classic protocol, with the same rule: only
  the explicit settings are set, and the others keep the client
  defaults (45 s and 3 s). The broker bounds the classic session
  timeout by `group.min.session.timeout.ms` (6 s by default) and
  `group.max.session.timeout.ms`, not by the consumer-protocol bounds,
  so a timeout out of them leaves the classic consumer `FAILED` with
  `InvalidSessionTimeoutException`. The classic client refuses a
  heartbeat interval that is not lower than the session timeout, client
  defaults included: such a consumer is `FAILED` with a message that
  gives both values, without a Kafka consumer, until it switches to the
  consumer protocol.
- The consumers connect through the proxy. Their Kafka settings come
  from the effective settings of the consumer: `group.protocol`,
  `max.poll.interval.ms`, `client.rack`, `group.instance.id` (the
  client id, for a static member), `partition.assignment.strategy` for
  a classic assignor and `group.remote.assignor` for a consumer-protocol
  one, `session.timeout.ms` and `heartbeat.interval.ms` from the group
  for a classic consumer, `auto.offset.reset=latest`, and a `request.timeout.ms` of 10 s
  unless the client properties of the simulator set it, so that a
  consumer whose request the proxy dropped recovers within that time.
  The poll timeout and the poll delay drive the loop itself.
- A consumer starts subscribed and without faults. `join()`, `leave()`,
  `inject` and `clear` work before `start()` too: the consumer then
  starts in that state.
- `switchProtocol(protocol)` closes the Kafka consumer, which leaves
  the group, and creates a new one with the other protocol. The
  subscription and the faults are kept. The assignor is set for the
  protocol of the consumer's settings; the other protocol keeps it only
  when it lists the same name (`range`) and uses its default otherwise.
  Switching to the current protocol does nothing. A switch whose Kafka
  consumer cannot be created throws `SimulationException` and leaves
  the consumer `FAILED`.
- The autopilot is per group. `start(min, max)` validates
  `1 ms ≤ min ≤ max`, throws `SimulationStateException` while the group
  does not run, and gives new intervals to an autopilot that is on.
  `stop()` keeps the intervals. The initial intervals are the
  prototype's 10 s and 30 s.
- Removing a static member that the coordinator still lists after its
  consumer is closed, deleting the group, and setting a group config the
  broker refuses are best-effort: they are logged (the config also as a
  warning) and never fail a start or a stop.

**Multiple groups**
- Settings, consumers, autopilot, snapshot and lag are per group.
- Client ids are `<groupId>-<consumerId>`, so `<groupId>-consumer-N`
  for an assigned id. They must be unique across groups, because the
  network fault matches on `client.id`. A group id and a consumer id
  may both contain a dash, so `addGroup` and `addConsumer` also refuse,
  as an `InvalidConfigException`, a consumer whose client id another
  group already uses.
- Consumer ids (`consumer-N`) are stable within their group and are
  never reused. A supplied id is honored and an empty one is assigned:
  the group counts from `consumer-0` and skips every id that it has or
  had, supplied or assigned, so an assigned id never collides with a
  supplied one, even one that is added later, and the id of a removed
  consumer is not assigned again. A refused `addConsumer` uses up no
  id. A supplied id that a removed consumer had is honored.

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
  `topics("orders")`) or by `Topic` (`subscribe(orders)`,
  `topics(orders)`), which only reads the name.
- Consumer values that are not set inherit from the group's
  `consumerDefaults`. Values that `consumerDefaults` does not set keep
  the defaults of `ConfigMetadata`. The order of the calls in a group
  lambda does not matter.

**Records as the data view**
- `simulator.describe()` returns an immutable `SimulatorSpec` with
  `topics[]`, `producers[]` and `groups[]`, with the assigned ids.
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
  records are accepted by overloads: `addTopic(TopicSpec)`,
  `addProducer(ProducerSpec)`, `addGroup(GroupSpec)` and
  `addConsumer(ConsumerSpec)`. The server uses these overloads for
  request bodies.

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
| group | `numConsumers` | 3 | |
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
- `add…` and `start()` throw `InvalidConfigException` with a list of
  `Violation(field, message)`. `field` is a path such as
  `groups[0].consumers[1].overrides.pollDelayMs`.
- An `add…`, before `start()` or while running, validates the whole
  spec with the new component appended (a consumer to its group), so it
  also catches duplicates, unknown topics and the topic limit, and its
  paths are those of the whole spec (`topics[1].name`,
  `groups[0].consumers[2].overrides.assignor`). A refused component
  changes nothing.
- `Simulator.validate(SimulatorSpec)` runs the same check without side
  effects. The server uses it to return 400 before going async.
- `ConfigValidator` is a set of pure functions that return every
  violation at once. Its only entry point is the whole-spec check,
  which the harness runs for every `add…` and `start()`, and
  `Simulator.validate` exposes.
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
    assigned consumer ids within their group;
  - the consumers of a group that speak the classic protocol have an
    assignor in common. The coordinator only admits a classic member
    that supports a protocol every other classic member supports, and
    refuses the others for good (`InconsistentGroupProtocolException`),
    so the mix is a `Violation` on `consumers` rather than a `FAILED`
    consumer. A consumer with a named assignor supports that one; a
    consumer with the empty name supports the defaults of the client,
    `range` and `cooperative-sticky`. The consumers of the consumer
    protocol take no part in it, so a mixed-protocol group is valid.

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
  - `Protocol`: `CONSUMER`, `CLASSIC`.
  - `Phase`: `IDLE`, `STARTING`, `RUNNING`, `STOPPING`.
  - `GroupType`: `UNKNOWN`, `CONSUMER`, `CLASSIC`.
  - `GroupState`: `UNKNOWN`, `EMPTY`, `ASSIGNING`, `RECONCILING`,
    `STABLE`, `PREPARING_REBALANCE`, `COMPLETING_REBALANCE`, `DEAD`.
  - In both `GroupType` and `GroupState`, `UNKNOWN` stands for a group
    the coordinator has not reported yet.
  - `PartitionState`: `ASSIGNED`, `PENDING`, `REVOKING`, `EMPTY`.
  - `ConsumerStatus`: `FAILED`, `STOPPED`, `FROZEN`, `ISOLATED`,
    `STOPPING`, `JOINING`, `RECONCILING`, `STABLE`.
  - `ProducerState`: `STOPPED` (declared, not running), `RUNNING`,
    `PAUSED`.
- There is a `PartitionRef` record, ordered by topic and then by
  partition.
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

Status: built, for the components that are declared before `start()`.
The per-runner `switchLock` and the consumer removal it guards are
built with the runner, and so is the removal.

**Threads**
- `kaos-<groupId>-<consumerId>` is the sole owner of its
  `KafkaConsumer`. The loop subscribes, unsubscribes and polls
  according to the flags, waits with `park` while the consumer is
  frozen or out of the group, and pauses for the poll delay between two
  polls. A flag flip unparks it and calls `wakeup()`, so it reacts at
  once wherever it waits.
- The loop subscribes before it checks the freeze, as the prototype
  does. So a consumer frozen before it joins still subscribes: with the
  consumer protocol, whose heartbeats run in the background, it joins
  the group and is later evicted by the max poll interval; with the
  classic protocol, which only joins in `poll`, it never joins.
- There is one `kaos-producer-<id>` thread per producer.
- `kaos-poller` runs one thread for the whole simulator. It covers all
  groups, because `describeConsumerGroups` takes a list.
- `kaos-publisher` runs one thread. It assembles, diffs and stores the
  snapshot, and calls the listeners. It runs from the first declaration
  (or `start()`) until `stop()`, so that declarations show up in the
  snapshot, and it is the only thread of an idle simulator. A simulator
  with nothing declared has no thread at all. `stop()` joins the thread
  before it returns, so a stop and start cycle never runs two
  publishers.
- There is one `kaos-<groupId>-autopilot` thread while that group's
  autopilot is on.
- `inject`, `clear`, `join`, `leave`, `pause` and `resume` only flip
  atomics that the owning loop reads. The exceptions are `wakeup()` and
  `switchProtocol`.

**Thread creation**
- Threads are named platform threads. On JDK 21, virtual threads would
  pin their carrier inside the Kafka client (`synchronized` blocks and
  selector I/O).
- All threads of `kaos-core` are created through one internal
  `ThreadFactory` in `engine/`, `Threads`, one instance per simulator.
  The server's `kaos-lifecycle` thread is the exception (see below). Switching to
  virtual threads later is then a one-line change. The threads are
  daemons, so a simulator that is never closed does not keep the JVM
  alive. An exception that ends one of them is logged through an
  uncaught exception handler. For a producer loop, the unexpected
  exception also brings the producer back to `STOPPED` in the snapshot.
- The admin client and the producers have client ids that start with
  `kaos-` (`kaos-admin`, `kaos-producer-<id>`), so their threads can be
  told apart as well. The consumers do not: their client id is
  `<groupId>-<consumerId>`, which the network faults match on, and
  their loop threads are `kaos-<groupId>-<consumerId>`.
- Javalin serves HTTP requests on virtual threads (a config flag).
  Handlers only flip flags, read the snapshot reference or submit work
  to the lifecycle executor. An SSE client keeps its request thread for
  the life of its stream.
- The server has one platform thread of its own, `kaos-lifecycle`, a
  daemon that runs the blocking operations one at a time (see
  [HTTP API v1](#http-api-v1-kaos-server)). The server never imports
  `internal`, so it creates this thread itself rather than through
  `Threads`. Like the proxy's event loops, it is an exception to the
  single `ThreadFactory`.

**Shared state**
- Only three things cross threads:
  - the per-runner atomics and local assignment, which the publisher
    copies into an immutable `ConsumerLocalState`;
  - `NetworkFaults`;
  - the `AtomicReference` holding the latest snapshot.
- Component collections are immutable lists. They are swapped atomically
  under the structure lock (copy-on-write). Readers such as the poller,
  the publisher and the autopilot never see a list being mutated. The
  engine keeps the phase, `lastError` and the lists in one immutable
  state record behind a single volatile reference, so a reader sees them
  change together.

**Locks**
- A single `structureLock` per `Simulator` serializes `start`, `stop`,
  the `with…` methods and every `add…`/`remove()`.
- A per-runner `switchLock` is held during `switchProtocol` and during
  consumer removal, and while a runner starts or stops. `stop` takes
  each `switchLock` in turn.
- Flag flips take no lock.
- The publisher takes no locks.
- A phase change is published synchronously: the lifecycle thread waits
  until the publisher thread has published it and the listeners have
  returned (`SnapshotPublisher.flush()`), so `STARTING`, `STOPPING` and
  a start failure are never coalesced away, and `start()` only returns
  or throws once the listeners have seen its outcome.
- A listener that called a method taking the `structureLock` during
  such a flush would wait for the lock while its holder waits for the
  listener. So every method that takes the lock (`start`, `stop`,
  `close`, `with…`, `add…`, `remove()`), and `awaitSnapshot`, fails fast: on the
  publisher thread it throws `SimulationStateException` before taking
  the lock. The publisher logs it like any listener exception and the
  simulator stays healthy. `SnapshotPublisher.stop()`, `flush()` and
  `await()` likewise refuse to run on the publisher thread.

**Teardown**
- `Resources` is a stack of undo actions. Each component has its own
  sub-stack (`child`), and the simulator has the top-level stack. A
  sub-stack is undone at its position in its parent, or on its own, in
  which case the parent forgets it.
- Undo actions are registered only after their step succeeded.
- The order of `start()` is: admin client, proxy, topics, producers,
  poller, groups. The undo order is the reverse, so the poller still
  reports the members while the consumers leave. The topics, the
  producers and the groups each have a sub-stack of the top-level stack,
  and a component has a sub-stack of the one of its kind; a component
  added while running goes there too, so `stop()` keeps that order
  whenever the components were added. Within a group: the group
  configs are set, the consumers are started, the autopilot is opened.
  The group keeps one stack for its consumers, created before the step
  that asks every consumer to stop and the autopilot, and each consumer,
  added later or not, has its sub-stack there: stopping the consumer,
  then removing its static member if the coordinator still lists it.
  Undoing a group turns the autopilot off, asks every consumer loop to
  end, then closes the stack of the consumers, which waits for each and
  removes its static member, deletes the group, and reverts the group
  configs. Removing a consumer closes its sub-stack only.
- A failed `start`, a failed `add…`, `remove()` and `close()` all unwind
  through the same code. An `add…` while running starts the component on
  a new sub-stack (`Resources.start`) before the component joins the
  state; a failure closes that sub-stack at once. `remove()` closes the
  sub-stack of its component and swaps the lists.
- Each undo runs in its own try/catch. Failures are attached as
  suppressed exceptions on a single `SimulationException`, flat across
  the sub-stacks: the failure of the start when a start is undone, a new
  exception otherwise. A pending interrupt does not skip the undo
  actions; it is restored once they have all run.
- A failed start leaves no thread, no port and no topic behind. The
  proxy launcher's `IllegalStateException` is wrapped in a
  `SimulationException`.
- The simulator only creates the topics it owns. A topic that already
  exists fails the start: adopting it would mean deleting, at `stop()`,
  a topic the simulator did not create. (The prototype adopts and
  deletes it.) The message of the failure ends with "delete it or use
  another topic name". Topics get the default replication factor of the
  cluster. Creating a topic waits until the cluster reports it, as
  deleting one waits until it no longer does: the controller
  acknowledges both before the brokers that serve the metadata know. The
  creation, at `start()` or in an `addTopic` while running, also waits
  until every partition has a leader that answers a `ListOffsets`
  request, bounded like the rest of the step: a broker lists a partition
  in its metadata before it hosts its log, and a produce request sent in
  between gets `NOT_LEADER_OR_FOLLOWER`. When the first batch of the
  idempotent producer fails that way while a later batch of the same
  partition is accepted, the producer retries the first one with
  `OUT_OF_ORDER_SEQUENCE_NUMBER` until its delivery timeout and the
  partition gets little or no load. So the producers only start once the
  topics are ready.
- Stopping a producer unparks its loop and joins it for 5 s, then
  interrupts it, since the blocking calls of the Kafka producer are
  interruptible, and joins again; its client is then closed with a 5 s
  bound.
- Stopping a consumer ends its loop, which closes its Kafka consumer
  with a 5 s bound and leaves the group; the loop is joined for 10 s,
  then interrupted, and joined again.
- `close` is idempotent.
- Waits are interruptible (`parkNanos` or a `Condition`, plus
  `consumer.wakeup()`). There are no sleep-slice loops.

**Runtime failures**
- A runner poll error is logged to `kaos.events` and the loop
  continues, after at least one second so that a failing consumer does
  not spin.
- An unrecoverable runner error ends the loop, closes the Kafka
  consumer and sets the consumer status to `FAILED` with a message, the
  `failure` of the consumer snapshot. The simulator keeps running. The
  errors counted as unrecoverable are those after which a Kafka
  consumer stays out of its group whatever is polled: a fenced or
  unreleased instance id, an inconsistent group protocol, an
  unsupported assignor or version, a full group, an invalid group id,
  an invalid session timeout, and authentication and authorization
  failures.
- A poller error keeps the last `AdminView` and sets `stale` on the
  snapshot.
- Autopilot validates `min ≤ max`. It does nothing when its group has no
  consumers, and it stops when its group is removed. A consumer that is
  removed while the autopilot picks it is skipped.

**Change log**
- The logger `kaos.events` reports what changes in the groups: a group
  or a consumer added or removed, the group type, state, epoch, target
  assignment epoch and generation, the autopilot, and, per consumer, a
  join or leave request, a protocol switch, a fault injected or
  cleared, a member joining, leaving or rejoining (the member id
  changes), the member epoch, the generation, the status, a failure and
  the partitions assigned and revoked. The autopilot logs its own flips
  there too.
- `EventLog` is a snapshot listener registered first by the engine, so
  every line is the difference between two published snapshots and the
  log has the same source as the listeners. `EventLog.diff` is a pure
  function.

### Snapshot pipeline

Status: built.

1. `GroupPoller` polls every 200 ms, with one `describeConsumerGroups`
   call for all groups, one `listConsumerGroupOffsets` call for their
   committed offsets and one `listOffsets` call for the end offsets of
   their subscribed topics. It produces an `AdminView` per group. A
   group the coordinator does not know yet (`GroupIdNotFoundException`)
   is `AdminView.unknown()` with its lag. The lag of a partition is
   known once the group has a committed offset for it.
2. `SnapshotAssembler`, a pure function, merges each group's
   `AdminView` with the group's `GroupLocalState` into a
   `GroupSnapshot`. The publisher puts the group snapshots together
   with the phase, the topics and the producers into a
   `SimulatorSnapshot`.
3. `SnapshotPublisher` compares the result with the last snapshot and
   ignores the revision number.
4. If the snapshot changed, the publisher bumps `revision`, stores it in
   an `AtomicReference` and notifies the listeners.
5. Local flag flips and structural changes poke the publisher directly,
   so they show up immediately. Several pokes may end up in one
   snapshot; phase changes are flushed instead (see
   [Threading and failure model](#threading-and-failure-model)).

The initial snapshot has the revision 0, the phase `IDLE` and nothing
else. A failure to assemble and a listener exception are logged; the
publisher keeps the last snapshot and goes on. While the publisher
thread does not run (nothing declared, or after `stop()`), a poke has no
effect; `stop()` publishes the final empty `IDLE` snapshot before the
thread ends.

`simulator.snapshot()`, `group.snapshot()` (one slice of it),
`GET /snapshot` and SSE all read the same reference.

**Inputs of the assembler.** They are immutable records in
`internal/state` and hold no Kafka type; the poller and the publisher
fill them.
- `AdminView` is what the coordinator and the offsets say about one
  group: `type`, `state`, `epoch`, `targetAssignmentEpoch`, the
  `members` and the `lag` per `PartitionRef` (end offset minus committed
  offset, never negative).
  - A member has `clientId`, `memberId`, `instanceId`, `memberEpoch`,
    `assignment` and an optional `targetAssignment`.
  - `AdminView.unknown()` stands for a group that is not reported yet.
- `GroupLocalState` is what the simulator knows about the group: `id`,
  the subscribed topics with their partition counts, the `autopilot`
  state (an `AutopilotSnapshot`) and one `ConsumerLocalState` per
  consumer.
- `ConsumerLocalState` is the copy of a runner's state: `id`,
  `clientId`, the current `protocol`, the effective `assignor`,
  `subscribed`, `faults`, `failure`, `generation` and
  `localAssignment`. `failure` is the message of the unrecoverable error
  of the runner. It is never empty for a failed runner and empty
  otherwise, so it is the single source of "failed".
- A consumer and a member are matched by `client.id`. A member without a
  simulated consumer behind it is ignored.

**Domain logic owned by core** (any frontend, a TUI included, gets it
for free):
- Target mirroring (`AdminView.targetAssignmentOf`). A member without a
  reported target has an empty target in a consumer group. In any other
  group, which has no notion of target, the target mirrors the current
  assignment.
- Coordinator state (`StatusDeriver`), one of `STOPPED`, `STOPPING`,
  `JOINING`, `RECONCILING` and `STABLE`:
  - a consumer the coordinator does not list is `JOINING` when it is
    subscribed and `STOPPED` otherwise;
  - a listed consumer that is not subscribed is `STOPPING`;
  - a listed member of a consumer group is `RECONCILING` while its
    assignment differs from its target or its epoch is behind the target
    assignment epoch, and `STABLE` otherwise;
  - a listed member of any other group is `RECONCILING` while the group
    is in `PREPARING_REBALANCE` or `COMPLETING_REBALANCE`, and `STABLE`
    otherwise.
- Partition classification (`PartitionClassifier`: assigned, pending,
  revoking, empty).
  - Consumer protocol: a target partition is assigned when the consumer
    holds it locally and pending otherwise; a partition that is not a
    target and that the consumer or the coordinator still holds is
    revoking.
  - Classic protocol: a partition of the current assignment is assigned.
    There is no pending or revoking.
- Out-of-group blanking. A consumer that is not subscribed, or for which
  the coordinator has neither a current nor a target partition, has no
  partition. Not being subscribed counts at once, before the coordinator
  catches up.
- Classic eager mid-rebalance blanking. A classic consumer whose
  assignor is eager (every classic assignor except `cooperative-sticky`,
  the client default included) has no partition while the group is in
  `PREPARING_REBALANCE` or `COMPLETING_REBALANCE`: it gave them all up
  to rejoin, while the coordinator still reports the last assignment. It
  uses the **per-consumer** effective assignor, so the consumers of one
  group can differ. (The prototype uses the group-level assignor.)
- The Stable→Reconciling override. A consumer of the consumer protocol
  that is `STABLE` for the coordinator has the status `RECONCILING`
  while a partition is in flight: a target it does not hold locally, or
  a partition held locally or by the coordinator that is not a target.
- Status priority `FAILED > STOPPED > FROZEN > ISOLATED > …`, where `…`
  is the coordinator state with the override above. `STOPPED` means not
  subscribed, so `STOPPING` only shows in `coordinatorState`.
  `connectionReset` counts as ISOLATED. `FAILED` applies when the
  consumer has a failure message. It comes first because a failed runner
  no longer reacts to anything else.
- Dense lag: one array per subscribed topic, indexed by partition. A
  partition whose lag is not known yet has `-1`, which keeps it apart
  from a partition that is caught up (`0`).
- Group generation: the highest generation its consumers report.
- Effective config.
- Consumer ids.

**Owned by the UI** (presentational only):
- Lag rounding and fill.
- Axis stride.
- Tooltip placement.
- Hiding the badge for healthy classic members.
- S/M/L strip height.

### Proxy without static state

Status: built, in `io.github.dajac.kaos.internal.proxy`. `Simulator`
launches its proxy in `start()`; nothing connects through it until the
consumers exist.

- There is one proxy and one `NetworkFaults` instance per `Simulator`.
  All groups share them.
- Per-consumer independence comes from the filter deciding on **every
  request** by `header.clientId()`.
  - Client ids are `<groupId>-consumer-N`, so they are unique across
    groups.
  - All of a consumer's connections (coordinator, leaders, metadata)
    carry the same id, so they go dark together.
  - Nothing else is affected.
- `NetworkFaults` maps each client id to a mode. It is a plain
  thread-safe instance; a change applies to the client's next request.
  - `BLACKHOLE` drops the request (`drop()` on the filter result).
    Clearing it does not heal a connection that had a request dropped:
    Kroxylicious delivers responses in request order, so later
    responses on that connection wait behind the dropped request. The
    client recovers when it times out on that request and reconnects,
    within its `request.timeout.ms`, or earlier if it closes the
    connection itself (the classic coordinator does on a session
    timeout). A filter only acts while handling a request, so it cannot
    close the connection when the fault is cleared. Simulated consumers
    should therefore keep `request.timeout.ms` short, and a frontend
    must not promise instant healing.
  - `RESET` closes the client connection instead of forwarding
    (`withCloseConnection()` on the filter result, which Kroxylicious
    supports natively). Kroxylicious closes a connection by draining it
    first: it waits up to the virtual cluster's `drainTimeout` for the
    in-flight requests, and the request that triggered the close never
    completes. The launcher sets `drainTimeout` to 100 ms, so the
    client sees the disconnect about 100 ms after its request.
- Adding or removing consumers or groups at runtime needs nothing from
  the proxy.
- Producers and the admin client bypass the proxy.
- The `NetworkProxy` interface (`bootstrapServers()`, `faults()`,
  `close()`) keeps the topology swappable. A proxy per group or a port
  per consumer would only matter for connection-level faults that need
  identity by port.
- `NetworkFaultFilterFactory` has two constructors: a no-arg "unbound"
  one used by the ServiceLoader, and a bound one that takes a
  `NetworkFaults`.
  - The `META-INF/services` entry and the unbound constructor exist
    because the Kroxylicious configuration parser resolves a filter
    type through the ServiceLoader. An unbound factory refuses to
    initialize.
  - The shade `ServicesResourceTransformer` merges the entry into the
    runnable jar.
- `BoundPluginRegistry implements PluginFactoryRegistry` returns the
  bound instance for the Kaos filter type. It delegates everything else
  to `ConfigParser`.
- `ProxyLauncher.launch(Admin, NetworkFaults)` does the following:
  - It discovers node ids with `describeCluster` on the caller's admin
    client, bounded by a timeout.
  - It builds the Kroxylicious configuration as a `Map`, renders it to
    YAML and lets `ConfigParser` parse it. It does not use string
    concatenation or the unstable record constructors. The target is a
    `clusterDefinitions` entry, because `targetCluster` on a virtual
    cluster is deprecated in 0.24.
  - It emits explicit `nodeIdRanges`, one per run of consecutive node
    ids, because the default 0..2 breaks other clusters.
  - It looks for a free contiguous port block starting at the requested
    port (`--proxy-port`, default 19092): the bootstrap port, then one
    port per broker. A port is probed on the wildcard and the loopback
    addresses with address reuse, as the proxy binds it. If `startup()`
    fails, the launcher closes that proxy and probes the block again:
    when a port of it is now busy, it was taken between the probe and
    the bind, and the launcher moves on to the next block, at most 10
    times; when the block is still free, the launch fails. The
    exception is not inspected, because the bind failure depends on the
    Netty transport (`NativeIoException` with kqueue and epoll).
  - It calls `KafkaProxy.startup()`, which in 0.24 binds the ports
    synchronously; the future it returns completes when the proxy
    *stops*, so it is not awaited. A failed start closes the proxy.
  - It confines the unavoidable `internal.config.Features` import.
- `close()` is idempotent and bounded. The configuration sets the Netty
  shutdown quiet period to 0 and the shutdown timeout to 2 s (the
  defaults are 2 s and 15 s), and the 100 ms drain timeout also bounds
  the wait for blackholed requests. After `close()` the ports are free
  and the proxy's threads are gone.
- Kroxylicious runs its own Netty event loops, so the proxy's threads
  are an exception to the single internal `ThreadFactory` of
  `kaos-core`.
- Several proxies coexist in one JVM, each with its own faults and port
  block. Kroxylicious itself registers its metrics in Micrometer's
  global registry, which the proxies share; Kaos does not use these
  metrics.

### HTTP API v1 (`kaos-server`)

Status: built. The command line is
`--bootstrap-server <host:port>` (default `localhost:9092`),
`--port <port>` (default 8081), `--proxy-port <port>` (default 19092,
1-65535, the port the proxy requests first), `--help` and `--version`.
picocli parses
it: `CliOptions` holds the annotated options, `Main` is the command that
mixes them in, and `Version` provides `--version`. The application owns
every option: `run.sh` builds the jar and passes all its arguments
through unchanged, so a new option needs no change in the script.

Exit codes: `--help` and `--version` exit with status 0. Invalid options
print the error and the usage, and exit with status 2. A failed startup,
such as a port already in use, releases the server, prints the reason
and exits with status 1.

`Main` creates the `Simulator` for `--bootstrap-server`, with
`withProxyPort` set to `--proxy-port`, and hands it to
the `Server`, which owns it. A JVM shutdown hook closes the server: it
ends the event streams, stops Javalin, drops the operations that have not
begun and closes the simulator, which waits for a start in progress and
removes what it created on the cluster. The simulator's threads are
daemons, so without the hook a Ctrl-C would leave its topics and groups
behind.

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
GET    /api/v1/openapi.yaml             this API as an OpenAPI 3.1 document (application/yaml)
```

**Status codes**
- An operation that runs on the lifecycle executor answers 202 with the
  status (see below): `POST` and `DELETE /simulator`, every `POST` that
  adds a component, every `DELETE` that removes one, and the
  `switch-protocol` action.
- An operation that only flips a flag answers 204: the producer state,
  the autopilot, the faults, and the `join` and `leave` actions.
- `DELETE /simulator` answers 204 when there is nothing to stop, and
  keeps `lastError` then.
- `/` and every other path outside `/api` belong to the UI (see
  "Metadata and static files" below).

**Status**
- The status is `{phase, lastError, warnings}`. It is the body of the
  202 answers and the data of the SSE `status` event, and
  `GET /simulator` returns it with the `spec` (`SimulatorSpec`, with the
  overrides and the effective settings of each consumer).
- `phase` is the phase of the simulator, except that it is `STARTING`
  from the acceptance of a start until the start ends, and `STOPPING`
  likewise for a stop, so that a client sees its accepted request at
  once, and never sees `RUNNING` while a dynamic request would still be
  refused because a start is pending.
- `lastError` is the message of the last failed operation of the
  executor: an operation clears it when it begins and sets it if it
  fails. It thus also reports a failed add, remove or protocol switch,
  which the `lastError` of the snapshot does not.
- `warnings` lists the `warnings()` of every group.

**Requests**
- The checks of a request run before it is accepted, against the
  components as they are, not against the operations still queued:
  - a spec with a violation is a 400, returned synchronously:
    `POST /simulator` runs `Simulator.validate` on the spec, and an
    add runs it on the current spec with the component appended, so its
    paths are those of the whole spec;
  - an unknown topic, producer, group, consumer or fault in the path is
    a 404;
  - a start while the simulator is not idle, a dynamic request while it
    is not `RUNNING` (or while a start or a stop is pending), and the
    removal of a topic that a producer or a group uses are 409. A topic
    whose users have a removal queued is therefore still in use.
- A dynamic operation checks the phase and joins the queue under the
  same lock as the acceptance of a start or a stop, so the queue keeps
  the order of acceptance and no dynamic operation is accepted behind a
  stop. It also checks the phase again when it begins: if the simulator
  no longer runs, it does nothing and sets `lastError`, so it never
  declares a component on an idle simulator.
- A failed start removes the components it declared, so the simulator
  is empty again, and `lastError` holds the failure.
- The request bodies of topics, producers and groups bind to `dto/*`
  records whose numbers are boxed: a missing value, also inside a partial
  `consumerDefaults`, takes the default of `ConfigMetadata` rather than
  0. Consumers bind to `ConsumerSpec` directly; the `effective` settings
  a client sends back are accepted and ignored. So the `spec` of
  `GET /simulator` can be posted back as it is.
- The autopilot body needs `enabled`; a missing interval keeps the
  current one. A `min` below 1 or a `max` below `min` is a 400.
- The actions need `type`; `switch-protocol` also needs `protocol`.

**JSON**
- A `Protocol` is its lower-case `id()` (`consumer`, `classic`), also as
  a map key in `/meta`, and is read in any case. A fault is its `id()`.
  The other enums are their names (`RUNNING`, `STABLE`).
- The jdk8 module binds the `Optional`s of `ConsumerOverrides`, and the
  output leaves out an empty one (`NON_ABSENT`).
- `/meta` returns `{version, fields, nameRule, assignors, faults}`, the
  `ConfigMetadata` table with the build version.

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
- A `: ping` heartbeat is sent after 15 s without an event.
- Slow clients are coalesced to the latest snapshot.
- `SseHub` serves each client on its request thread: the thread sends
  what changed, then waits on a condition that the snapshot listener
  and the service signal. It reads the latest snapshot and status when
  it wakes up, so a client that is slow to write skips the snapshots in
  between. The stream needs no `Accept` header; a client that went away
  is noticed at the next write, a ping at the latest.

**Errors**
- RFC 9457 `application/problem+json` with `violations[{field,message}]`.
  `ProblemHandler` writes `{type: "about:blank", title, status, detail,
  violations}`, and leaves `violations` out when it is empty. `detail`
  is always present: an exception without a message gives its class
  name for a 500, and the title otherwise.
- 400 for validation failures, 404 for an unknown resource, 409 for the
  wrong phase or a resource in use. A `SimulationStateException` is a
  409, an `IllegalArgumentException` a 400, and anything else a 500,
  which is logged. An unknown route is a 404 problem too.
- Jackson runs with `FAIL_ON_UNKNOWN_PROPERTIES`, and also refuses a
  `null` element of a list, a `null` for a primitive, a number for an
  enum, a fraction for an integer and trailing content. A body that does
  not bind is a 400 whose violation names the path of the value, as the
  core does (`groups[0].consumers[1].overrides.bogus`); a body that is
  not JSON, or is missing, is a 400 without violations.

**Snapshot shape**

Status: built. Unknown values follow two conventions: a
number (epoch, generation, lag) is `-1`, and a string (member id,
instance id) is empty. `failure` is empty when the consumer has not
failed.

- `revision`, `phase`, `stale`, `lastError` (the message of the last
  failed start or stop, empty otherwise).
- `topics[{name,partitions}]` (`TopicSpec`).
- `producers[{id,topics,state,ratePerPartition}]`.
- `groups[]`, each with:
  - `id`, `type`, `state`, `epoch`, `targetAssignmentEpoch`,
    `generation`;
  - `subscribedTopics`;
  - `autopilot{enabled,minIntervalMs,maxIntervalMs}`, the body of the
    autopilot endpoint; the intervals are the last ones given and are
    kept while it is off;
  - `lag{topic:[…dense…]}`, where an unknown lag is `-1`;
  - `consumers[]`.
- Each entry of `consumers[]` has:
  - `id`, `clientId`, `protocol`;
  - `subscribed`, `faults[]`;
  - `memberId`, `instanceId`, `memberEpoch`, `generation`;
  - `coordinatorState`, `status` (both a `ConsumerStatus`), `failure`;
  - `partitions[{topic,partition,state}]`, sorted, without the `EMPTY`
    ones;
  - `raw{assignment,targetAssignment,localAssignment}`, each a sorted
    list of `{topic,partition}`.

**Lifecycle handling**
- `SimulationService` owns a single-thread `kaos-lifecycle` executor.
- All blocking operations are submitted there and the request returns
  202. These are start, stop, add, remove and switch-protocol.
- Failures surface as `lastError` and as an SSE `status` event.
- Closing the service drops the operations that have not begun and
  closes the simulator.

**OpenAPI document**
- `kaos-server/src/main/resources/openapi/kaos-v1.yaml` is a hand-written
  OpenAPI 3.1 document of every route above: parameters, request
  bodies, responses with their status codes, and a schema for every
  body. `ApiRoutes` reads it from the classpath once and serves it at
  `GET /api/v1/openapi.yaml` as `application/yaml`.
- Every object schema has `additionalProperties: false`: the server
  refuses an unknown property in a request, and writes none that the
  document does not list. The request schemas carry the ranges and
  defaults of `ConfigMetadata` and the name rule; the response schemas
  do not repeat them. The `effective` settings of a consumer in a
  request are complete when sent, since they bind to `ConsumerSettings`.
  An action is a `oneOf` discriminated by `type`, so that the generated
  types give `switch-protocol` a required `protocol`.
- OpenAPI 3.1 does not model the events of an SSE stream. The events
  route describes them, and its `x-sse-events` extension maps each event
  name to the schema of its data (`status` to `Status`, `snapshot` to
  `SimulatorSnapshot`).
- `OpenApiContractTest` keeps the document and the server in step,
  without a broker:
  - fixtures serialized by the server's mapper (a full snapshot with
    both protocols, faults, lag and a failed consumer; the status; the
    simulator with a spec with overrides; `/meta`; problems with
    violations) match their schemas, and so do the live answers of a
    server whose start fails (200, 202, 400, 404, 409), a 500 and a
    400 from exceptions without a message, and the first SSE events;
  - request bodies that match their schemas bind, and the `spec` of
    `GET /simulator` matches the start request;
  - the routes registered by `ApiRoutes` are exactly the documented
    ones;
  - the enums, the name rule, and the ranges and defaults are those of
    the Java model and of `ConfigMetadata`;
  - a few invalid documents are refused, so that the checks are not
    vacuous.

**Metadata and static files**
- `/meta` is served from `ConfigMetadata`. `ConfigValidator` uses the
  same table, so there is one source of truth.
- `StaticUi` serves the classpath `/ui` directory, which the `kaos-ui`
  jar holds, at `/`:
  - the hashed assets of the Vite build, under `/assets`, are
    `Cache-Control: public, max-age=31536000, immutable`;
  - every other file, `index.html` included, is `no-cache`;
  - the SPA fallback answers `index.html` (`no-cache`) to any other
    `GET` that accepts HTML, except under `/api` and `/assets`, which
    answer a 404 problem detail, so that a missing asset, such as a
    chunk of a previous build, is not answered with HTML.
- Without the UI on the classpath, a build with `-DskipUi`, `StaticUi`
  registers nothing and `/` is a 404.

### Frontend (`kaos-ui`)

Status: built: the build, the fonts, the tokens and base styles, the
layout of the prototype, the pure model under `model/`, the API client
and the event stream, the config panel, the Rows view, their stores and
their styles.

**Model (`model/`)**
- Framework-free TypeScript: no React and no DOM. Every function is
  pure and returns a new state. The API shapes are the generated types
  of `api/schema.d.ts`; the defaults, labels, ranges and assignors come
  from `/api/v1/meta`, never from constants in the UI.
- `configModel.ts` is the state of the config panel. It holds
  `groups[]`: each group has its id, its number of topics and of
  partitions (its topics are `<groupId>-1..N`), protocol, assignor,
  static membership, the optional settings in the order they were
  added, and its consumers. A consumer holds only its overrides, in the
  order they were added, and inherits the rest from its group. The
  module derives the chips of a collapsed consumer, the "inherits all
  settings" note and the entries of the "+ Add …" menus, and has
  duplicate-last and remove.
- `buildStartRequest` gives the body of `POST /simulator`: the topics
  of every group, one producer that writes to all of them at the rate
  of `/meta`, and each group with its `consumerDefaults` and the
  overrides of its consumers, whose ids the group assigns
  (`consumer-0..N-1`). `startProblems` refuses a start while a group
  has no consumer ("Add at least one consumer before starting."), as
  the prototype did; the core accepts such a group.
- Rehydration follows the prototype: on load, the form is rebuilt from
  `GET /simulator` only when its phase is `RUNNING`, so that the locked
  panel shows the running config; in any other phase the form is the
  initial one. `configFromSimulator` applies this rule. It rebuilds the
  form from the `spec`, overrides included; an optional setting is
  added when it differs from its default. The spec holds a value for
  every setting and not which ones were set, so a setting that was
  added at its default value is not restored by a reload, as in the
  prototype.
- `assignors.ts`: the presets of a protocol are the names of `/meta`,
  then "custom" when the protocol accepts a custom name (the consumer
  protocol). A new group starts on the first preset of its protocol
  (`uniform`), and a switch to a protocol that refuses the assignor
  falls back to the first preset of that protocol (`range` for
  classic). On the consumer protocol, a name outside the presets, the
  empty one included, is a custom name; a custom name left empty sends
  the empty name, the default of the protocol. A spec's empty name,
  which only a start through the API gives, shows as the first preset
  of its protocol, as on a new group and as in the prototype.
- `violations.ts`: the key of each field of the panel that can show an
  error, and the mapping of the violations of a refused start (`field`
  is a path of the start request, such as
  `groups[0].consumers[1].overrides.pollDelayMs`) to these fields. A
  consumer is found by its position in the request and keyed by its
  stable form key; a topic path goes to the group that names the topic
  (`name` to its id, `partitions` to its partitions); an optional
  setting maps only when it was added. A violation that names no field
  of the panel goes to the banner.
- `lag.ts`: the gauge rounds a lag up to a multiple of 5 and scales it
  to 100; `-1`, an unknown lag, fills nothing.
- `axis.ts`: the tick stride, 20 from 100 partitions, 10 from 40, 5
  from 15, and a quarter of the partitions below.
- `tooltip.ts`: the tooltip is 8 px below the cell, centred on it and
  clamped 4 px inside the viewport, and its text.
- `rows.ts`: what the Rows view shows of a group snapshot. The states
  come from the core (the status of a consumer, the states of its
  partitions, with the out-of-group and mid-rebalance blanking already
  applied); the module turns them into the classes and texts of the
  prototype: the header badges (`PREPARING REBALANCE`, a dash for an
  unknown type or number), the consumer badge (`ISOLATED` shows as
  `PARTITIONED`, `FAILED` in the red of `stopping`, hidden for a classic
  member that is neither stopped, faulty nor failed), and the cells of a
  strip with their gauge, their tooltip and the signature that memoizes
  the strip.
- `share.ts`: structural sharing of JSON values. `shareStructure(prev,
  next)` is `next` with every part equal to that of `prev` replaced by
  the object of `prev`; array elements are matched by `id` when they
  all have one, by position otherwise.

**API client (`api/client.ts`)**
- A thin `fetch` wrapper typed from `schema.d.ts`: `getMeta`,
  `getSimulator`, `startSimulator` and `stopSimulator` (which returns
  `null` for a 204), and the requests of the Rows view: `setAutopilot`,
  `injectFault`, `clearFault` and `runAction`. An error status throws an
  `ApiError` with the problem detail when the body is one, and its
  `violations`.

**Event stream (`api/events.ts`)**
- `openEventStream(handlers, options)` opens
  `/api/v1/simulator/events` and hands over each `status` and
  `snapshot` event, and whether the stream is up.
- On an error it closes the `EventSource` and opens a new one after a
  pause that starts at 500 ms and doubles up to 10 s; the pause starts
  over once a stream opens. The native reconnect of `EventSource` is not
  relied on, since it gives up for good on some failures. Events of a
  stream that failed are ignored.

**State (Zustand)**
- Components use selector subscriptions.
- `configStore` (a vanilla Zustand store, `useConfigStore(selector)` in
  components; `createConfigStore({client, pollIntervalMs})` for tests)
  holds `/meta`, the form (`groups[]`), the phase, the inline errors by
  field and the banner. `main.tsx` starts loading it before the first
  render, without waiting: `/meta` and `GET /simulator` together, with
  `configFromSimulator` for the form. Until they answer, the panel shows
  the sidebar header and a disabled Start.
- Edits go through `editGroup(index, update, field)`, where `update` is
  a function of `model/configModel.ts`; they are ignored while the panel
  is locked (any phase but `IDLE`), and clear the error of `field`.
  Expanding a consumer works while locked, as in the prototype.
- Start runs `startProblems` first, then posts `buildStartRequest`. A
  400 with violations shows them on their fields (see `violations.ts`)
  and the rest in the banner; any other failure goes to the banner.
- Start and Stop answer 202 and run on the server. The store follows
  them through the `status` events: `main.tsx` hands every one to
  `applyStatus`, which sets the phase. The answer of the request does
  not set it, so a status that came first is kept. A start or a stop
  that ends `IDLE` with a `lastError` shows it in the banner (the
  failed-start surface); a stop answered with 204 is `IDLE` at once.
- A reload during a start shows the running spec once the phase is
  `RUNNING`: `load()` then fetches `GET /simulator` again. A status that
  arrived before the answer of `load()` keeps its phase.
- `simulationStore` (`useSimulationStore(selector)`,
  `createSimulationStore({client})` for tests) holds the connection
  (`connecting`, `open`, `disconnected`), the last `status` and the
  last `snapshot`. `connect()` opens the event stream. Each snapshot is
  merged into the previous one with `shareStructure`, so unchanged
  groups, consumers and lag arrays keep their identity and `React.memo`
  holds. The revision is not used to skip a snapshot: it starts over
  when the server restarts.
- It also sends the requests of the Rows view (actions, faults,
  autopilot) and keeps the message of the last one that failed. That
  message, or the `lastError` of the status while the simulation runs
  (a failed protocol switch, for instance), shows in a dismissible
  banner of the main area.
- The Rows view reads its group through `useGroup(groupId)`, and every
  component of it takes the group id: no component assumes a single
  group. The shown group is the first of the snapshot
  (`selectShownGroupId`), for parity with the prototype.
- `uiSettingsStore` holds the S/M/L strip height (48, 72 or 120 px),
  kept in `localStorage` under `kaos.uiSettings` as
  `{"stripHeightPreset": "M"}`. The `mode` of the prototype is ignored,
  and a missing, corrupt or unavailable storage gives `M`.

**Rows view (`components/rows/`)**
- `RowsView` is the main area: the empty state while the simulation is
  idle, and from a start until the stop has ended, the header and the
  grid of the shown group. The banners float over its bottom right: the
  event stream is down ("Disconnected from the server.
  Reconnecting…"), or a request failed.
- `GroupHeader`: the type and state badges, then the epoch and target
  epoch, or the generation of a classic group; `STARTING` until the
  group is reported. Then the `Legend` and the `UiSettingsMenu` (⚙).
- `RowsGrid` renders one memoized `ConsumerRow` per consumer and sets
  `--strip-height`. The lag map of the group changes with the lag of any
  partition, so `ConsumerRow` compares it with `sameLagOf` (`rows.ts`):
  a row renders again only when its consumer changes or the lag of one
  of its own partitions does. `Legend` and `UiSettingsMenu` are
  memoized, since the header renders again with every change of the
  group. `ConsumerRow` renders a `PartitionStrip` per topic,
  the axis rail, the name (the consumer id, its client id as the title)
  with the badge, the hover meta (protocol, member id, instance, epoch
  or generation) and the `ActionMenu`.
- `ActionMenu` ("Actions ▾"): Stop or Start consumer (`leave`/`join`),
  Upgrade to consumer or Downgrade to classic (`switch-protocol`),
  Partition network or Heal network (`network-isolation`), Freeze or
  Unfreeze consumer (`freeze`). A consumer out of the group has only
  Start and the ways back from its faults. The menus share
  `useDropdown` with the config panel's `Dropdown`.
- The Auto-Pilot button of the sidebar turns the autopilot of the shown
  group on and off with `{enabled}`; the intervals are those of the
  core, 10 to 30 s. It shows the `autopilot` of the snapshot.

**Heatmap**
- `PartitionStrip` is memoized on a cheap signature: the state and the
  tooltip of each cell.
- Cells are plain `<div class="pcell" style="--fill">`.
- There is one delegated tooltip (`cellTooltip.ts`): the grid hands its
  `mouseover` and `mouseout` to it, it reads the `data-tip` of the cell,
  and it is driven imperatively, so hovering renders nothing. It is
  portaled to `<body>` as `#pcell-tip`, hides on scroll, resize and
  click, and a strip that renders again refreshes its text in place.

**CSS**
- The styles are the prototype's `<style>` block (`simulator.html` on
  `initial-setup`), lifted **verbatim** and split by region into
  `styles/*.css`. The class names are kept. `tokens.css` (the `:root`
  block) and `base.css` (the `*`, `html, body` and `body` rules) exist,
  and so do `controls.css` (the buttons, the `.dd` dropdown, the ⚙
  menu and the "Actions" menu), `config.css` (the layout shell, the
  sidebar and the config panel, including the `#config-mode.locked`
  rules) and `rows.css` (the main area, the header, the legend, the
  empty state, the `#rows-grid` rules and `#pcell-tip`).
- `config.css` and `rows.css` end with the only rules that are not the
  prototype's: the inline field errors (`.field-error`,
  `.consumers-error`) and the error banner (`.config-banner`), which
  replace `alert()`, and the banners of the main area
  (`.rows-banner*`).
- The fonts are bundled from fontsource instead of Google Fonts.
  `--font-mono` and `--font-body` name the variable families,
  `'JetBrains Mono Variable'` and `'DM Sans Variable'`, first, then the
  families of the prototype.
- The Bento rules, the lag gradient of the legend and the other dead
  rules are not carried over.
- No CSS modules. The visual result is identical to the prototype.

**Dev loop**
- The Vite dev server proxies `/api`, including SSE, to `localhost:8081`.
- Command: `./mvnw -pl kaos-server -am -DskipUi compile exec:java` plus
  `npm run dev`. The `compile` phase lets the reactor resolve
  `kaos-core` from its `target/classes`; `exec:java` is skipped in every
  module except `kaos-server`. `.nvmrc` names the Node of the build.

## Features

Status: built, and checked side by side against the prototype: every
field of the config panel, every action in every state, classic,
consumer and mixed groups, the Auto-Pilot, a reload during a run, the
failed starts, the tooltip, the lag gauge and the S/M/L strip height.
The differences that remain are intended and listed below.

The UI is at parity with the prototype: one group, one producer, the
same screen. Behind the UI, the core and HTTP APIs additionally support
independent producers (pause and rate), multiple groups, and adding or
removing components at runtime.

**Config panel**
- Fields: group id, protocol (consumer or classic), static membership,
  number of topics (1–20) and number of partitions (1–100).
- Assignor. The presets depend on the protocol. A custom free-text name
  is accepted for the consumer protocol. A name typed in its text input
  keeps the field custom while it is the assignor, even when it is a
  preset (`range` on the way to `range-sticky`), and picking "custom"
  again keeps it. The field leaves the input when a preset is picked,
  when the name changes from outside the field (the fallback of a
  protocol switch), or when the protocol refuses a custom name. A field
  that did not type the name, such as one shown again after a reload,
  shows a preset name as that preset.
- Optional group settings, added with "+ Add setting":
  - session timeout, heartbeat interval, assignment interval; the first
    two apply to the consumers of both protocols;
  - poll timeout, poll delay, max poll interval.
- The consumer list has add, duplicate-last and remove.
- Each consumer has collapsible per-consumer **overrides**: protocol,
  assignor, rack id, poll timeout, poll delay, max poll interval, static
  member.
- A collapsed consumer shows chips and a protocol tag. A consumer with no
  overrides shows the "inherits all settings" note.
- The panel is locked while a simulation runs.
- Status: built (`components/config/`: `ConfigPanel`, `GroupSection`,
  `ConsumerSection`, `Dropdown`, `AssignorField`, `NumberInput`,
  `FieldError`), with Start and Stop, the Auto-Pilot, inline validation
  errors and the error banner.
- Keyboard: a dropdown closes on Escape and when the focus leaves it,
  and its button carries `aria-expanded`. The header of a consumer is
  a `div` that toggles on a click anywhere in it and holds two sibling
  buttons: the toggle (chevron, name and protocol tag, with
  `aria-expanded`) and the ×. A button inside an element with the role
  of a button would be hidden from assistive technologies. The layout
  is the prototype's, pixel for pixel.

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
- Status: built (see [Frontend](#frontend-kaos-ui)), with the header
  and the Auto-Pilot.
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
- A failed request of the Rows view, or a failed protocol switch, shows
  in a banner; the prototype ignored them.
- A consumer whose runner failed shows a `FAILED` badge, with the
  failure as its title; the prototype had no such state.
- Before the first commit of a partition, its lag is unknown (`-1`) and
  its gauge is empty; the prototype counted a missing commit as 0, so
  the gauge showed the whole log-end offset. Each gauge stays empty
  until its partition's first commit; the steady-state lag is the same.
- A reload during a run restores the overrides of each consumer; the
  prototype restored only their number.
- A start is validated first: out-of-range fields (topics above 20,
  partitions above 100, a negative setting) and an empty group id show
  inline, where the prototype started anyway or showed the error of the
  broker.
- A start refuses a topic that already exists, since the simulator
  deletes the topics it creates; the prototype reused it. The error
  ends with the way out: "delete it or use another topic name" (in the
  UI, the topics are named after the group id).
- A start against an unreachable cluster fails once the proxy cannot
  describe it, in about 30 s; the prototype failed after 60 s on the
  creation of the topics.
- Stop deletes the consumer group, so a new run of the same group id
  starts from epoch 1; the prototype kept the group and showed its
  stale state at the start of the next run.
- The title of a consumer's name is its client id, `<group>-<consumer>`,
  unique across groups; the prototype's client id was the consumer id.

## Verification

Status: planned, except that `./mvnw verify` already builds the modules,
runs the unit tests, the integration-test harness below, the code
format check and the UI build with Vitest, and produces the shaded jar
with the UI, that `-DskipITs` and `-DskipUi` work, that
`./run.sh` builds and starts the server, that the CI workflow runs
the build, the server tests and the scripted curl session below, and
that the README example compiles.

**Build**
- `./mvnw verify` runs the unit tests, the Testcontainers ITs (Docker is
  required), the code format check and the UI build with Vitest.
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
- The UI build runs in the same job through `frontend-maven-plugin`,
  which downloads its own Node, with no workflow change.

**Integration-test harness**
- The broker sets `group.consumer.min.session.timeout.ms` to 1 s and
  `group.consumer.min.heartbeat.interval.ms` to 500 ms, so the tests
  can shorten the eviction and rebalance times of consumer-protocol
  groups through the group configs.
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
  which gives `bootstrapServers()`, `createAdmin()`, `uniqueName(prefix)`,
  `awaitTopic(admin, name)` and `proxyPort()`.
- `awaitTopic(admin, name)` polls the broker until it reports a topic:
  `createTopics` completes when the controller has accepted the topic,
  and a describe right after it can still fail with
  `UnknownTopicOrPartitionException` until the metadata has reached the
  broker.
- `proxyPort()` is the port the tests of a JVM request for their
  proxies. It is drawn at random from 20000-29999 when the cluster
  starts: above the default proxy port, below the ephemeral ports that
  the clients and the container runtime allocate from. Two builds on
  one machine, or a build next to a running simulator, thus bind far
  apart from each other and never take each other's ports. Tests never
  request the default proxy port and never assert on absolute ports:
  they assert relative to the ports the launcher probed or chose.
- `TestThreads.startedSince(before)` lists the threads a test started,
  for the checks that nothing leaks a thread. It leaves out Awaitility
  and the workers of the JVM's common pool, which the JVM starts on
  first use and keeps idle for a minute.
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
  `Admin`, waits for it and describes it, and checks that the JVM draws
  one proxy port.
- `NetworkFaultInjectionTest` drives classic-protocol consumers through
  proxies launched from `proxyPort()`, with a 6 s session timeout:
  blackhole eviction and rejoin,
  one of two clients isolated, `RESET` disconnects and quick recovery,
  two proxies in one JVM, the port fallback, a port taken between the
  probe and the bind, failed launches that leak no port and no thread,
  and a bounded `close()` that frees its ports and threads.
- `SimulatorLifecycleTest` drives the public `Simulator` against the
  broker: start and close with topics and a producer (topics created and
  deleted, proxy port taken and released, threads gone); pausing a
  producer stops the offsets from advancing; a start that fails on a
  topic that already exists, one on a producer whose client cannot be
  created, and one against an unreachable cluster, leak no thread, no
  port and no topic, are seen by a listener as `STARTING` then `IDLE`
  with `lastError`, and can be retried; a `stop()` after a failed start
  keeps `lastError`; `with…`
  after the start throw; two simulators run in one JVM.
- `ConsumerGroupLifecycleTest` drives consumer groups through the
  `Simulator`: a full lifecycle with one group (join, leave, rejoin,
  lag, the group deleted at stop); `switchProtocol` in both directions;
  `Fault.freeze()` leads to eviction by the max poll interval and the
  consumer rejoins once cleared; `Fault.networkIsolation()` fences the
  member and it rejoins once healed; an isolated classic static member
  leaves after the 10 s session timeout of the group, not the 45 s of
  the client default; a classic session timeout under the broker's
  `group.min.session.timeout.ms` leaves the consumer `FAILED`; only the explicit group configs
  are set and they are reverted; a config the broker refuses is a
  warning; a quick stop and start with static members works with both
  protocols; listeners fire only on change; an unsupported
  consumer-protocol assignor leaves the consumer `FAILED` while the
  simulator runs; the autopilot flips subscriptions.
- `DynamicComponentsTest` drives components added and removed while the
  simulator runs: two groups with different protocols on one topic, the
  second added while running, report independent assignments and lag;
  adding or removing a consumer triggers a rebalance that the snapshot
  shows, and a removed consumer's id is not assigned again; removing a
  group (static members, group configs, autopilot) leaves the other
  group's members and epoch untouched; every partition of a topic gets
  records right after `start()` and after an `addTopic` while running;
  topics and producers are added and removed while running, a topic in
  use refuses its removal, and a removed topic is deleted; a failed
  `add…` of a topic, a producer or a group leaves the simulator
  unchanged. Each test checks that `close()` leaves no thread, topic or
  group behind.
- `TopicManagerTest` checks that every partition of a topic accepts a
  produce request without retries once `awaitReady` returns, and that a
  topic that does not exist is never ready.
- `kaos-core` publishes its test sources as a `test-jar`, which
  `kaos-server` consumes in test scope for the harness.

**Server tests**
- `ServerTest`, `ApiRoutesTest`, `SseHubTest`, `JsonTest` and
  `OpenApiContractTest` (see [OpenAPI document](#http-api-v1-kaos-server))
  run without a broker. The simulator's bootstrap server does not resolve, so a start
  fails at once. They cover `/meta`, the idle state, the 400s (a
  violation, an unknown property with its path, a `null` element, an
  unknown protocol, a malformed or missing body), the 404s, the 409 of
  every dynamic route while idle, the problem detail shape, a failed
  start as `lastError`, the SSE status and snapshot on connect and on
  change, the ping, the end of the streams at close, and the binding of
  partial bodies, of overrides and of a spec sent back.
- `SimulationServiceTest` (`@IntegrationTest`) holds the lifecycle
  thread busy to show that a dynamic operation is refused once a stop is
  accepted, and that one queued before a stop that ran first does
  nothing but set `lastError`.
- `HttpRunTest` (`@IntegrationTest`) drives a full run through HTTP
  against the broker: start, a second start refused, the 404s and 409s,
  a 400 on a dynamic add, a fault injected and cleared, leave and join,
  a protocol switch, a consumer added and removed, the autopilot, the
  producer paused and resumed, a topic, a producer and a group added and
  removed, a failed add as `lastError`, and the stop, which deletes the
  topics and leaves no `kaos-` thread.
- `ShadedJarTest` (`@IntegrationTest`) runs the shaded jar with
  `java -jar` as a separate process, on a free HTTP port, against the
  broker and with `--proxy-port` set to `proxyPort()`, starts a
  simulator with a topic, a producer and a group over HTTP, waits for it
  to run with its consumer stable, stops it, checks that the topic and
  the group are deleted and kills the process. A class missing from the
  runtime classpath of the jar, which the other tests do not see because
  they run on the test classpath, thus fails `./mvnw verify`. Failsafe
  runs after `package`, so the jar exists, and passes its path in the
  system property `kaos.test.jar`. A JVM shutdown hook kills the process
  if the test JVM stops during the test.
- `ApiClient`, the HTTP client of the server tests, gives every request
  a 10 s timeout, so a server that never answers fails the test instead
  of hanging it.
- `kaos-server/src/test/scripts/curl-session.sh [base-url]` is the
  scripted curl session: against a running server, it creates and
  starts a simulator, watches the SSE stream, injects and clears a
  fault, shows the problem details and stops.

**UI tests**
- Vitest runs the pure model tests in Node, and the component tests in
  jsdom (`// @vitest-environment jsdom` at the top of the file) with
  React Testing Library and `user-event`. The client is mocked by
  stubbing `fetch`, so a test checks the method, the path and the body
  of each request.
- `ConfigPanel.test.tsx` drives the config panel: the consumer header
  (a toggle and a × that are separate buttons, mouse and keyboard),
  adding, editing and removing an override, the chips of a collapsed
  consumer in menu order, the "inherits all settings" note,
  duplicate-last and add, a custom assignor name, a preset one included
  and how the field leaves it, the fallback to the first preset after a
  protocol switch (override and group), a violation of a refused start
  on its field, cleared by an edit, and the locked panel.
- `ConsumerRow.test.tsx` checks the state badge of every status on both
  protocols (hidden for a healthy classic member), and the "Actions ▾"
  menu for every protocol, membership and set of faults, whatever the
  status: the items, their order, which are destructive and enabled, as
  in the prototype, and the request each enabled item sends.
- `rerender.test.tsx` renders the Rows view with 3 topics of 100
  partitions and 12 consumers and feeds it snapshots: only the row and
  the strip of what changed render again (a lag, a status, a partition
  state), a change of the group alone renders no row, and a snapshot
  that changes nothing renders nothing. `src/test/renderTracker.ts`
  records the components rendered by each commit through the global
  hook of React DevTools, as its profiler does.

**Backend end-to-end**
1. Start a local broker:
   `docker run -p 9092:9092 apache/kafka:4.3.1`.
2. Run `./run.sh` and open `http://localhost:8081`.
3. Exercise start, every action and stop.
4. Confirm the API from the command line:
   `curl localhost:8081/api/v1/simulator/snapshot` and
   `curl -N localhost:8081/api/v1/simulator/events`, or run
   `kaos-server/src/test/scripts/curl-session.sh`.

**Java API**
- The ITs drive `new Simulator(..)`, `add…` and `start()` without HTTP,
  including a two-group scenario.
- The README example compiles as a test: `ReadmeExampleTest` holds the
  same code, compiled and not run since it needs a broker on
  `localhost:9092`, and checks that the Java block of `README.md` is that
  code, up to the indentation.
