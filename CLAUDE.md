# Kaos

Kaos is an interactive Kafka consumer group (KIP-848) simulator. The
design lives in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Read it
before changing anything.

## Rules

- `docs/ARCHITECTURE.md` always describes the code as merged. Any change
  that alters or refines the design updates that document in the same
  PR. Parts that are not built yet are marked "planned" there; remove
  the marker when the part lands.
- Public API vs `internal`:
  - The package `io.github.dajac.kaos` in `kaos-core` is the public API.
  - Everything under `io.github.dajac.kaos.internal` is not API. Public
    types never expose an `internal` type, and code outside `kaos-core`
    (the server included) never imports one.
  - No kafka-clients class appears in the public API or in the JSON.
- No Java type name contains "Kaos".
- No static mutable state. Several `Simulator`s can coexist in one JVM.
- All threads are created through the single internal `ThreadFactory`.
- The prototype lives on the frozen `initial-setup` branch. It is a
  read-only reference (`git show initial-setup:<path>`) and never lands
  on `main`.

## Module map

| Module | Role |
|---|---|
| `kaos-core` | Embeddable library with the fluent Java API (`Simulator`, topics, producers, consumer groups, faults, snapshots) |
| `kaos-server` | Javalin adapter exposing the HTTP API v1 (REST + SSE, OpenAPI); builds the shaded runnable jar |
| `kaos-ui` | React + TypeScript UI, built by Vite and embedded in the jar under `/ui` |

All three modules are planned; see `docs/ARCHITECTURE.md` for their
status.

## Build and run

These commands apply once the Maven build exists (planned).

```sh
./mvnw verify                 # unit tests, Testcontainers ITs (needs Docker), UI build + Vitest
./mvnw verify -DskipITs       # skip the integration tests
./mvnw verify -DskipUi        # skip the frontend build
./run.sh [bootstrap] [port]   # build and run; defaults localhost:9092 and 8081
java -jar kaos-server/target/kaos-*.jar --bootstrap-server localhost:9092
```

Frontend dev loop:

```sh
./mvnw -pl kaos-server -am -DskipUi exec:java   # backend on localhost:8081
npm run dev                                     # in kaos-ui/; Vite proxies /api
```

A local broker for manual testing:

```sh
docker run -p 9092:9092 apache/kafka:4.3.1
```
