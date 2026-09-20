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
- All test classes are named `*Test`; there is no `*IT` suffix. A test
  that needs Docker or a broker is annotated with `@IntegrationTest`
  (JUnit tag `integration`): surefire excludes the tag, failsafe runs
  only the tagged tests, and `-DskipITs` skips them.
- The prototype lives on the frozen `initial-setup` branch. It is a
  read-only reference (`git show initial-setup:<path>`) and never lands
  on `main`.

## Code style

- An empty body is `{}` on one line: `private Assignors() {}`,
  `g -> {}`.
- When the arguments of a call or a declaration span several lines, the
  closing parenthesis goes on its own line, aligned with the start of
  the statement: `);`, `) {`, `),`. This applies to main and test code.
- Prefer `var` for local variables, enhanced-`for` variables included,
  when the type is evident from the right-hand side or irrelevant to
  the reader. With a diamond, move the type arguments to the right:
  `var ids = new HashSet<String>()`.
- Prefer modern Java 21 idioms when they make the code clearer: records,
  sealed types with pattern `switch`, pattern matching for `instanceof`,
  switch expressions, `List.of`, `Stream.toList()`, text blocks. Do not
  force them where they hurt readability.

## Module map

| Module | Role |
|---|---|
| `kaos-core` | Embeddable library with the fluent Java API (`Simulator`, topics, producers, consumer groups, faults, snapshots) |
| `kaos-server` | Javalin adapter exposing the HTTP API v1 (REST + SSE, OpenAPI); builds the shaded runnable jar |
| `kaos-ui` | React + TypeScript UI, built by Vite and embedded in the jar under `/ui` |

`kaos-core` and `kaos-server` exist as skeletons and `kaos-ui` is
planned; see `docs/ARCHITECTURE.md` for their status.

## Build and run

```sh
./mvnw verify                 # unit tests and Testcontainers ITs (needs Docker); later also UI build + Vitest
./mvnw verify -DskipITs       # skip the integration tests, keep the unit tests
./mvnw verify -DskipUi        # skip the frontend build (planned, no effect yet)
./run.sh [options]            # build and run; all options go to the app, see ./run.sh --help
java -jar kaos-server/target/kaos-*.jar [--bootstrap-server localhost:9092] [--port 8081]
```

The runnable jar is the shaded `kaos-server/target/kaos-server-<version>.jar`.

CI (`.github/workflows/ci.yml`) runs `./mvnw -B -ntp verify`, integration
tests included, on every pull request, whatever its base branch, and on
every push to `main`. The test reports are uploaded as an artifact when
the build fails.

Frontend dev loop:

```sh
./mvnw -pl kaos-server -am -DskipUi compile exec:java   # backend on localhost:8081
npm run dev                                             # in kaos-ui/ (planned); Vite proxies /api
```

A local broker for manual testing:

```sh
docker run -p 9092:9092 apache/kafka:4.3.1
```
